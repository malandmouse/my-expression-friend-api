package com.planb.emotionfriend.api.learning.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import emotionfriend.contracts.ExpressionBindingModels.ExpressionBindingCatalog;
import emotionfriend.contracts.ExpressionBindingModels.ExpressionBindingRequest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Existing 0.6 preview boundary. Its validation supplies no runtime authorization or receipt. */
public final class PreviewBinding {
    private static final ContractSchemas SCHEMA = new ContractSchemas("/contracts/expression-preview-0.6.0.schemas.json");
    private static final com.fasterxml.jackson.databind.ObjectMapper DECLARATIONS = ProductHashes.JSON.copy()
            .setPropertyNamingStrategy(new PropertyNamingStrategies.NamingBase() {
                @Override public String translate(String name) { return name.substring(2); }
            });

    private PreviewBinding() {}

    public enum Mode { ORIGINAL, AI_COMPOSED }
    public enum Quality { SELECTABLE, REJECTED, UNCERTAIN }
    public record Card(String id, String text) {}
    public record Candidate(String id, Mode mode, List<String> orderedCardIds, String answerText,
                            String answerHash, String reactionText, String reactionHash, String speakerId) {
        public Candidate { orderedCardIds = List.copyOf(orderedCardIds); }
        @Override public String toString() { return "Candidate[redacted]"; }
    }
    public record Cursor(String clientSession, String storyHash, long sourceVersion, String catalogId,
                         String contextHash, String catalogHash) {}
    public record Context(String clientSession, String storyHash, String inputHash, List<String> path,
                          List<String> playedSpeakers, String recognitionTargetNpcId, String recognitionEmotionId,
                          String reactionNpcId, long sourceVersion, String sourceText, String sourceHash,
                          String catalogId, Instant expiresAt) {
        public Context {
            path = List.copyOf(path);
            playedSpeakers = List.copyOf(playedSpeakers);
        }
        @Override public String toString() { return "Context[redacted]"; }
    }

    public static final class Catalog {
        private final Context context;
        private final Cursor cursor;
        private final List<Card> cards;
        private final List<Candidate> candidates;
        private final Quality quality;
        private final String reviewTargetHash;
        private final ObjectNode wire;

        private Catalog(Context context, Cursor cursor, List<Card> cards, List<Candidate> candidates,
                        Quality quality, String reviewTargetHash, ObjectNode wire) {
            this.context = context;
            this.cursor = cursor;
            this.cards = List.copyOf(cards);
            this.candidates = List.copyOf(candidates);
            this.quality = quality;
            this.reviewTargetHash = reviewTargetHash;
            this.wire = wire;
        }

        public Context context() { return context; }
        public Cursor cursor() { return cursor; }
        public List<Card> cards() { return cards; }
        public List<Candidate> candidates() { return candidates; }
        public Quality quality() { return quality; }
        public String reviewTargetHash() { return reviewTargetHash; }
        public Candidate original() {
            return candidates.stream().filter(candidate -> candidate.mode() == Mode.ORIGINAL).findFirst().orElseThrow();
        }
    }

    public static Catalog validateCatalog(JsonNode value) {
        ObjectNode wire = (ObjectNode) checkedCopy("ExpressionBindingCatalog", value);
        var dto = decode(wire, ExpressionBindingCatalog.class);
        var c = dto.p_context();
        var context = new Context(c.p_clientSession(), c.p_storyHash(), c.p_inputHash(), c.p_path(),
                c.p_playedSpeakers(), c.p_recognitionTargetNpcId(), c.p_recognitionEmotionId(),
                c.p_reactionNpcId(), c.p_sourceVersion().longValueExact(), c.p_sourceText(), c.p_sourceHash(),
                c.p_catalogId(), Instant.parse(c.p_expiresAt()));
        require(c.p_expiresAt().endsWith("Z") && !blank(context.sourceText())
                && context.sourceHash().equals(ProductHashes.textHash(context.sourceText()))
                && c.p_pathHash().equals(ProductHashes.digest(wire.path("context").path("path")))
                && c.p_path().size() == c.p_playedSpeakers().size());
        require(dto.p_contextHash().equals(ProductHashes.digest(wire.path("context"))));

        var cards = dto.p_cards().stream().map(card -> new Card(card.p_cardId(), card.p_text())).toList();
        var cardById = index(cards, Card::id);
        require(cards.stream().map(card -> cardLabel(card.text())).distinct().count() == cards.size());

        var candidates = dto.p_candidates().stream().map(candidate -> new Candidate(candidate.p_candidateId(),
                Mode.valueOf(candidate.p_expressionMode()), candidate.p_orderedCardIds(), candidate.p_answerText(),
                candidate.p_answerHash(), candidate.p_reactionText(), candidate.p_reactionTextHash(),
                candidate.p_reactionSpeakerId())).toList();
        var byId = index(candidates, Candidate::id);
        require(candidates.stream().map(Candidate::orderedCardIds).distinct().count() == candidates.size());
        require(candidates.stream().filter(candidate -> candidate.mode() == Mode.ORIGINAL).count() == 1);
        for (Candidate candidate : candidates) {
            require(new HashSet<>(candidate.orderedCardIds()).size() == candidate.orderedCardIds().size()
                    && cardById.keySet().containsAll(candidate.orderedCardIds())
                    && candidate.answerHash().equals(ProductHashes.textHash(candidate.answerText()))
                    && candidate.reactionHash().equals(ProductHashes.textHash(candidate.reactionText()))
                    && candidate.speakerId().equals(context.reactionNpcId()));
            if (candidate.orderedCardIds().isEmpty()) {
                require(candidate.mode() == Mode.ORIGINAL && candidate.answerText().equals(context.sourceText()));
            } else {
                require(candidate.mode() == Mode.AI_COMPOSED);
            }
        }

        var review = dto.p_review();
        require(index(review.p_cards(), row -> row.p_cardId()).keySet().equals(cardById.keySet())
                && index(review.p_candidates(), row -> row.p_candidateId()).keySet().equals(byId.keySet()));
        var verdicts = new ArrayList<String>();
        verdicts.add(review.p_deck().p_verdict());
        review.p_cards().forEach(row -> verdicts.add(row.p_verdict()));
        for (JsonNode candidate : wire.path("review").path("candidates")) {
            candidate.path("checks").forEach(check -> verdicts.add(check.textValue()));
        }
        // A review may be more conservative than its individual checks.
        verdicts.add(review.p_status());
        String verdict = verdicts.contains("REJECT") ? "REJECT"
                : verdicts.contains("UNCERTAIN") ? "UNCERTAIN" : "PASS";
        require(verdict.equals(review.p_status()));
        var content = ProductHashes.JSON.createObjectNode();
        for (String field : List.of("context", "contextHash", "cards", "candidates")) content.set(field, wire.get(field));
        var hashInput = wire.deepCopy();
        hashInput.remove("catalogHash");
        require(review.p_contentHash().equals(ProductHashes.digest(content))
                && dto.p_catalogHash().equals(ProductHashes.digest(hashInput)));

        var cursor = new Cursor(context.clientSession(), context.storyHash(), context.sourceVersion(),
                context.catalogId(), dto.p_contextHash(), dto.p_catalogHash());
        var quality = switch (verdict) {
            case "PASS" -> Quality.SELECTABLE;
            case "REJECT" -> Quality.REJECTED;
            default -> Quality.UNCERTAIN;
        };
        return new Catalog(context, cursor, cards, candidates, quality, review.p_sourceTargetHash(), wire);
    }

    public record Bound(Candidate candidate, String bindingId, String requestHash, String reviewTargetHash) {}

    public static Bound bind(Catalog catalog, JsonNode value, Cursor serverCurrent, Instant now) {
        var wire = checkedCopy("ExpressionBindingRequest", value);
        var request = decode(wire, ExpressionBindingRequest.class);
        var c = request.p_cursor();
        var cursor = new Cursor(c.p_clientSession(), c.p_storyHash(), c.p_sourceVersion().longValueExact(),
                c.p_catalogId(), c.p_contextHash(), c.p_catalogHash());
        require(cursor.equals(serverCurrent) && cursor.equals(catalog.cursor())
                && catalog.quality() == Quality.SELECTABLE && now.isBefore(catalog.context().expiresAt()));
        var candidate = catalog.candidates().stream().filter(row -> row.id().equals(request.p_candidateId()))
                .findFirst().orElseThrow(PreviewBinding::invalid);
        require(candidate.orderedCardIds().equals(request.p_orderedCardIds())
                && candidate.mode().name().equals(request.p_expressionMode())
                && candidate.answerHash().equals(request.p_answerHash())
                && candidate.reactionHash().equals(request.p_reactionTextHash())
                && candidate.speakerId().equals(request.p_reactionSpeakerId()));
        return new Bound(candidate, bindingId(catalog, candidate, wire.get("cursor")),
                ProductHashes.digest(wire), catalog.reviewTargetHash());
    }

    static String bindingId(Catalog catalog, Candidate candidate) {
        ObjectNode cursor = ProductHashes.JSON.valueToTree(catalog.cursor());
        cursor.set("schemaVersion", catalog.wire.get("schemaVersion"));
        return bindingId(catalog, candidate, cursor);
    }

    private static String bindingId(Catalog catalog, Candidate candidate, JsonNode cursor) {
        var target = ProductHashes.JSON.createObjectNode();
        target.set("cursor", cursor);
        for (JsonNode row : catalog.wire.path("candidates")) {
            if (candidate.id().equals(row.path("candidateId").asText())) target.set("candidate", row);
        }
        target.put("reviewTargetHash", catalog.reviewTargetHash());
        return ProductHashes.digest(target);
    }

    private static JsonNode checkedCopy(String name, JsonNode value) {
        var copy = value.deepCopy();
        SCHEMA.check(name, copy);
        ProductHashes.digest(copy); // Reject floating point nodes, including integer-looking 1.0.
        return copy;
    }

    private static <T> T decode(JsonNode value, Class<T> type) {
        try {
            return DECLARATIONS.treeToValue(value, type);
        } catch (JsonProcessingException e) {
            throw invalid();
        }
    }

    private static <T> Map<String, T> index(List<T> rows, Function<T, String> key) {
        var result = rows.stream().collect(Collectors.toMap(key, Function.identity(), (first, second) -> {
            throw invalid();
        }));
        return result;
    }

    static boolean blank(String value) {
        return value.codePoints().allMatch(PreviewBinding::space);
    }

    private static boolean space(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
    }

    private static String cardLabel(String text) {
        require(!blank(text) && !space(text.codePointAt(0)) && !space(text.codePointBefore(text.length()))
                && text.codePoints().noneMatch(c -> Set.of(Character.CONTROL, Character.FORMAT,
                        Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED).contains((byte) Character.getType(c)))
                && !text.matches(".*[.!?。！？:：].*")
                && !text.matches(".*(?:싶어(?:요)?|(?:했|였|었|았|겠)어(?:요)?|입니다|습니다|해요|아요|어요|이에요|예요)$"));
        int words = 0;
        boolean separating = true;
        for (int c : text.codePoints().toArray()) {
            if (separating && !space(c)) words++;
            separating = space(c);
        }
        require(words <= 2);
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
        var label = new StringBuilder();
        normalized.codePoints().filter(c -> !space(c)).forEach(label::appendCodePoint);
        require(!Set.of("조금더", "다음에는", "그리고", "그래서", "함께", "천천히").contains(label.toString()));
        return label.toString();
    }

    private static void require(boolean valid) {
        if (!valid) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid preview binding");
    }
}

