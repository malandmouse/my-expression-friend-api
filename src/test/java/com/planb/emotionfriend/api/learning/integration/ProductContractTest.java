package com.planb.emotionfriend.api.learning.integration;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductContractTest {
    @Test void deliveredHashVectorsPreserveTheExactWireValues() throws Exception {
        var vectors = fixture("hash-vectors.json");
        for (var row : vectors.path("vectors")) {
            var value = row.get("value");
            String hash;
            if ("text".equals(row.path("kind").asText())) {
                String text = value.textValue();
                assertThat(text.getBytes(StandardCharsets.UTF_8)).hasSize(row.path("utf8ByteLength").asInt());
                assertThat(text.codePointCount(0, text.length())).isEqualTo(row.path("codePointLength").asInt());
                hash = ProductHashes.textHash(text);
            } else {
                hash = ProductHashes.digest(value);
            }
            assertThat(hash).as(row.path("id").asText()).isEqualTo(row.path("sha256").asText());
        }
        for (var row : vectors.path("invalidInputs")) {
            assertThatIllegalArgumentException().isThrownBy(() -> ProductHashes.digest(row.get("value")));
        }
    }

    @Test void productDigestRejectsFloatsAndInvalidUnicodeAndDistinguishesNullFromOmission() throws Exception {
        var json = ProductHashes.JSON;
        assertThat(ProductHashes.digest(json.readTree("{\"x\":null}")))
                .isNotEqualTo(ProductHashes.digest(json.readTree("{}")));
        assertThat(ProductHashes.digest(json.readTree("{\"n\":123456789012345678901234567890,\"truth\":true}")))
                .isEqualTo(ProductHashes.textHash("{\"n\":123456789012345678901234567890,\"truth\":true}"));
        assertThat(ProductHashes.digest(json.readTree("{\"😀\":1,\"\":2}")))
                .isEqualTo(ProductHashes.textHash("{\"\":2,\"😀\":1}"));
        for (String input : List.of("1.0", "1e0", "1.5")) {
            assertThatIllegalArgumentException().isThrownBy(() -> ProductHashes.digest(json.readTree(input)));
        }
        assertThat(ProductHashes.digest(json.getNodeFactory().textNode(String.valueOf((char) 0x0B))))
                .isEqualTo(ProductHashes.textHash("\"\\u000b\""));
        assertThatIllegalArgumentException().isThrownBy(() -> ProductHashes.textHash(String.valueOf((char) 0xD800)));
        assertThatThrownBy(() -> json.readTree("{\"x\":1,\"x\":2}")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> json.readTree("{} {}")).isInstanceOf(IOException.class);
    }

    @Test void validatesTheDraftAsAnOfflineShapeWithEveryDeliveredPositiveAndNegativeVector() throws Exception {
        var schema = new ContractSchemas("/backend-integration/proposed-boundaries.schema.json");
        for (var example : fixture("index.json")) {
            String filename = example.path("path").asText().replace("examples/proposed-http/", "");
            schema.check(example.path("schema").asText(), fixture(filename));
        }
        for (var vector : fixture("invalid-shape-vectors.json")) {
            assertThatIllegalArgumentException().as(vector.path("id").asText())
                    .isThrownBy(() -> schema.check(vector.path("schema").asText(), vector.get("value")));
        }
    }

    @Test void validatesExistingPreviewAndBindsBothGoldenCandidates() throws Exception {
        var data = fixture("examples.json");
        var catalog = PreviewBinding.validateCatalog(data.get("catalog"));
        Instant now = Instant.parse(data.path("now").asText());
        for (int i = 0; i < data.path("requests").size(); i++) {
            var request = data.path("requests").get(i);
            var expected = data.path("results").get(i);
            var result = PreviewBinding.bind(catalog, request, catalog.cursor(), now);
            assertThat(result.bindingId()).isEqualTo(expected.path("bindingId").asText());
            assertThat(result.requestHash()).isEqualTo(expected.path("requestHash").asText());
            assertThat(result.reviewTargetHash()).isEqualTo(expected.path("reviewTargetHash").asText());
        }
        var forged = (ObjectNode) data.path("requests").get(0).deepCopy();
        ((ObjectNode) forged.path("cursor")).put("catalogHash", "a".repeat(64));
        assertThatIllegalArgumentException().isThrownBy(() -> PreviewBinding.bind(catalog, forged, catalog.cursor(), now));
        assertThatIllegalArgumentException().isThrownBy(() -> PreviewBinding.bind(catalog, data.path("requests").get(0),
                catalog.cursor(), catalog.context().expiresAt()));
    }

    @Test void rejectsRelabelledOrTamperedPreviewAndContradictoryAudit() throws Exception {
        var original = (ObjectNode) fixture("examples.json").get("catalog");
        for (String version : List.of("0.3.0", "0.4.0", "0.5.0")) {
            var relabelled = original.deepCopy().put("schemaVersion", version);
            assertThatIllegalArgumentException().isThrownBy(() -> PreviewBinding.validateCatalog(relabelled));
        }
        var tampered = original.deepCopy();
        ((ObjectNode) tampered.path("candidates").get(0)).put("reactionText", "tampered");
        seal(tampered);
        assertThatIllegalArgumentException().isThrownBy(() -> PreviewBinding.validateCatalog(tampered));

        var contradictory = original.deepCopy();
        ((ObjectNode) contradictory.path("review").path("deck")).put("verdict", "UNCERTAIN");
        seal(contradictory);
        assertThatIllegalArgumentException().isThrownBy(() -> PreviewBinding.validateCatalog(contradictory));

        var omitted = original.deepCopy();
        ((ObjectNode) omitted.path("context")).remove("inputHash");
        seal(omitted);
        assertThatIllegalArgumentException().isThrownBy(() -> PreviewBinding.validateCatalog(omitted));

        var incomplete = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) incomplete.path("review").path("cards")).remove(0);
        seal(incomplete);
        assertThatIllegalArgumentException().isThrownBy(() -> PreviewBinding.validateCatalog(incomplete));
    }

    static JsonNode fixture(String name) throws IOException {
        try (var in = ProductContractTest.class.getResourceAsStream("/backend-integration/" + name)) {
            return ProductHashes.JSON.readTree(in);
        }
    }

    static ObjectNode seal(ObjectNode catalog) {
        catalog.put("contextHash", ProductHashes.digest(catalog.get("context")));
        var content = ProductHashes.JSON.createObjectNode();
        for (String key : List.of("context", "contextHash", "cards", "candidates")) content.set(key, catalog.get(key));
        ((ObjectNode) catalog.get("review")).put("contentHash", ProductHashes.digest(content));
        catalog.remove("catalogHash");
        return catalog.put("catalogHash", ProductHashes.digest(catalog));
    }

    static ObjectNode request(ObjectNode catalog, int candidateIndex) {
        var candidate = catalog.path("candidates").get(candidateIndex);
        var request = ProductHashes.JSON.createObjectNode().put("schemaVersion", "0.6.0")
                .put("requestId", "synthetic-request-current");
        var cursor = request.putObject("cursor").put("schemaVersion", "0.6.0");
        for (String key : List.of("clientSession", "storyHash", "sourceVersion", "catalogId")) {
            cursor.set(key, catalog.path("context").get(key));
        }
        for (String key : List.of("contextHash", "catalogHash")) cursor.set(key, catalog.get(key));
        for (String key : List.of("candidateId", "orderedCardIds", "expressionMode", "answerHash", "reactionTextHash",
                "reactionSpeakerId")) request.set(key, candidate.get(key));
        return request;
    }
}

