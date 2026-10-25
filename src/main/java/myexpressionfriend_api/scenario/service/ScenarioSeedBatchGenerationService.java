package myexpressionfriend_api.scenario.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import myexpressionfriend_api.game.domain.ScenarioSource;
import myexpressionfriend_api.common.service.FileStorageService;
import myexpressionfriend_api.rag.dto.RagGenerateRequest;
import myexpressionfriend_api.rag.dto.RagGenerateResponse;
import myexpressionfriend_api.rag.service.RagGenerationService;
import myexpressionfriend_api.scenario.domain.ScenarioApprovalStatus;
import myexpressionfriend_api.scenario.dto.ScenarioBulkImportResultDTO;
import myexpressionfriend_api.scenario.dto.ScenarioDTO;
import myexpressionfriend_api.scenario.dto.ScenarioSeedBatchGenerateRequestDTO;
import myexpressionfriend_api.scenario.dto.ScenarioSeedBatchGenerateResponseDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScenarioSeedBatchGenerationService {

    private static final String SCENARIO_TEMPLATE_KEY = "scenario-generation-default";
    private static final Charset MS949 = Charset.forName("MS949");

    private final RagGenerationService ragGenerationService;
    private final ScenarioService scenarioService;
    private final ObjectMapper objectMapper;
    private final ScenarioRenderAssetNormalizer scenarioRenderAssetNormalizer;
    private final FileStorageService fileStorageService;

    @Value("${app.scenario.backup-dir:uploads/exports/scenarios}")
    private String backupDir;

    public ScenarioSeedBatchGenerateResponseDTO generate(
            ScenarioSeedBatchGenerateRequestDTO request,
            MultipartFile seedCsv
    ) {
        List<SeedRow> rows = readSeedRows(seedCsv).stream()
                .filter(row -> row.character().equalsIgnoreCase(request.character().trim()))
                .toList();

        int startIndex = request.startIndex() == null ? 0 : request.startIndex();
        int endIndex = request.endIndex() == null ? rows.size() : Math.min(request.endIndex(), rows.size());
        if (startIndex >= endIndex) {
            throw new IllegalArgumentException("startIndex must be smaller than endIndex for the selected character.");
        }

        List<ScenarioDTO> generatedScenarios = new ArrayList<>();
        List<ScenarioSeedBatchGenerateResponseDTO.Item> items = new ArrayList<>();
        int totalToProcess = endIndex - startIndex;

        log.info("Scenario seed batch start. character={}, startIndex={}, endIndex={}, total={}, persistToDb={}, writeBackupJson={}, topK={}, similarityThreshold={}, useProModel={}, think={}",
                request.character(), startIndex, endIndex, totalToProcess,
                shouldPersist(request), shouldWriteBackup(request), request.topK(), request.similarityThreshold(),
                request.useProModel(), request.think());

        for (int i = startIndex; i < endIndex; i++) {
            SeedRow row = rows.get(i);
            int progress = i - startIndex + 1;
            log.info("Scenario seed item start. character={}, progress={}/{}, index={}, scenarioId={}, stage={}, theme={}",
                    request.character(), progress, totalToProcess, i, row.scenarioId(), row.stage(), row.theme());
            try {
                RagGenerateResponse generated = ragGenerationService.generateScenario(new RagGenerateRequest(
                        null,
                        buildUserRequest(row),
                        buildRetrievalQuery(row),
                        "",
                        buildAdditionalContext(row),
                        SCENARIO_TEMPLATE_KEY,
                        request.topK(),
                        request.similarityThreshold(),
                        request.useProModel(),
                        false,
                        request.think()
                ));

                ScenarioDTO scenario = parseAndFixScenario(generated.generatedText(), row);
                generatedScenarios.add(scenario);
                items.add(new ScenarioSeedBatchGenerateResponseDTO.Item(i, row.scenarioId(), "GENERATED", null));
                log.info("Scenario seed item generated. character={}, progress={}/{}, index={}, scenarioId={}, model={}, dialogueTurns={}",
                        request.character(), progress, totalToProcess, i, row.scenarioId(),
                        generated.model(), scenario.dialogueFlow() == null ? 0 : scenario.dialogueFlow().size());
            } catch (Exception ex) {
                items.add(new ScenarioSeedBatchGenerateResponseDTO.Item(i, row.scenarioId(), "FAILED", ex.getMessage()));
                log.warn("Scenario seed item failed. character={}, progress={}/{}, index={}, scenarioId={}, reason={}",
                        request.character(), progress, totalToProcess, i, row.scenarioId(), ex.getMessage());
            }
        }

        ScenarioBulkImportResultDTO importResult = shouldPersist(request)
                ? scenarioService.bulkImport(generatedScenarios)
                : new ScenarioBulkImportResultDTO(generatedScenarios.size(), 0, 0);

        String backupPath = shouldWriteBackup(request) && !generatedScenarios.isEmpty()
                ? writeBackup(request.character(), startIndex, endIndex, generatedScenarios)
                : null;

        log.info("Scenario seed batch finish. character={}, requested={}, generated={}, saved={}, skipped={}, failed={}, backupPath={}",
                request.character(), totalToProcess, generatedScenarios.size(), importResult.savedCount(),
                importResult.skippedCount(), totalToProcess - generatedScenarios.size(), backupPath);

        return new ScenarioSeedBatchGenerateResponseDTO(
                request.character(),
                startIndex,
                endIndex,
                endIndex - startIndex,
                generatedScenarios.size(),
                importResult.savedCount(),
                importResult.skippedCount(),
                backupPath,
                items
        );
    }

    private ScenarioDTO parseAndFixScenario(String generatedText, SeedRow row) {
        ScenarioDTO parsed = parseScenario(generatedText);

        ScenarioDTO.Metadata metadata = new ScenarioDTO.Metadata(
                row.stage(),
                row.theme(),
                row.stage() + "단계",
                row.seedText(),
                parsed.metadata() != null ? parsed.metadata().lobbyTitle() : null,
                parsed.metadata() != null ? parsed.metadata().backgroundImageId() : null
        );

        ScenarioDTO.Cast cast = new ScenarioDTO.Cast(
                row.character(),
                "Center",
                parsed.cast() != null ? parsed.cast().subCharacters() : null,
                parsed.cast() != null ? parsed.cast().subCharPos() : null
        );

        return scenarioRenderAssetNormalizer.normalize(new ScenarioDTO(
                row.scenarioId(),
                null,
                ScenarioSource.SERVER_LLM,
                ScenarioApprovalStatus.DRAFT,
                metadata,
                cast,
                parsed.dialogueFlow(),
                parsed.finalSummary()
        ));
    }

    private ScenarioDTO parseScenario(String generatedText) {
        String json = stripMarkdownFence(generatedText);
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) {
                node = node.get(0);
            }
            if (node != null && node.isArray()) {
                node = node.get(0);
            }
            return objectMapper.treeToValue(node, ScenarioDTO.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("LLM generated invalid scenario JSON.", ex);
        }
    }

    private String stripMarkdownFence(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            int firstNewLine = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewLine >= 0 && lastFence > firstNewLine) {
                return text.substring(firstNewLine + 1, lastFence).trim();
            }
        }
        return text;
    }

    private String writeBackup(String character, int startIndex, int endIndex, List<ScenarioDTO> scenarios) {
        try {
            if (fileStorageService.isObjectStorage()) {
                String key = fileStorageService.saveBytes(
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(scenarios),
                        "exports/scenarios", character + "_batch_" + startIndex + "_" + endIndex + ".json",
                        "application/json");
                return fileStorageService.toPublicUrl(key);
            }
            Path directory = Path.of(backupDir);
            Files.createDirectories(directory);
            Path path = directory.resolve(character + "_batch_" + startIndex + "_" + endIndex + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), scenarios);
            log.info("Scenario seed backup written. character={}, startIndex={}, endIndex={}, path={}, count={}",
                    character, startIndex, endIndex, path.toAbsolutePath(), scenarios.size());
            return path.toAbsolutePath().toString();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write scenario backup JSON.", ex);
        }
    }

    private String buildUserRequest(SeedRow row) {
        return """
                [입력 데이터]
                - scenario_id: %s
                - character: %s
                - stage: %d
                - theme: %s
                - seed_text: %s

                위 데이터를 바탕으로 시스템 지침에 따라 시나리오 JSON을 생성해줘.
                scenario_id, character, stage, theme, seed_text는 반드시 그대로 반영해.
                """.formatted(row.scenarioId(), row.character(), row.stage(), row.theme(), row.seedText());
    }

    private String buildRetrievalQuery(SeedRow row) {
        return row.theme() + "\n" + row.seedText() + " 시나리오 생성";
    }

    private String buildAdditionalContext(SeedRow row) {
        return "Seed 기반 배치 생성입니다. 캐릭터와 stage는 seed 값을 우선합니다.";
    }

    private boolean shouldPersist(ScenarioSeedBatchGenerateRequestDTO request) {
        return request.persistToDb() == null || Boolean.TRUE.equals(request.persistToDb());
    }

    private boolean shouldWriteBackup(ScenarioSeedBatchGenerateRequestDTO request) {
        return request.writeBackupJson() == null || Boolean.TRUE.equals(request.writeBackupJson());
    }

    private List<SeedRow> readSeedRows(MultipartFile seedCsv) {
        if (seedCsv == null || seedCsv.isEmpty()) {
            throw new IllegalArgumentException("seedCsv file is required.");
        }

        byte[] bytes;
        try {
            bytes = seedCsv.getBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read seed CSV.", ex);
        }
        String content = decodeSeedCsv(bytes);

        List<String> lines = content.lines()
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.size() < 2) {
            throw new IllegalArgumentException("seed CSV must contain a header and at least one row.");
        }

        List<String> headers = parseCsvLine(lines.get(0));
        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            headerIndex.put(headers.get(i).trim(), i);
        }

        List<SeedRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> values = parseCsvLine(lines.get(i));
            rows.add(new SeedRow(
                    get(values, headerIndex, "scenario_id"),
                    get(values, headerIndex, "character"),
                    Integer.parseInt(get(values, headerIndex, "stage")),
                    get(values, headerIndex, "theme"),
                    get(values, headerIndex, "seed_text")
            ));
        }
        return rows;
    }

    private String decodeSeedCsv(byte[] bytes) {
        try {
            return decodeStrict(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ex) {
            return new String(bytes, MS949);
        }
    }

    private String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .replace("\uFEFF", "");
    }

    private String get(List<String> values, Map<String, Integer> headerIndex, String key) {
        Integer index = headerIndex.get(key);
        if (index == null || index >= values.size()) {
            throw new IllegalArgumentException("seed CSV is missing required column: " + key);
        }
        return values.get(index).trim();
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private record SeedRow(
            String scenarioId,
            String character,
            int stage,
            String theme,
            String seedText
    ) {
    }
}
