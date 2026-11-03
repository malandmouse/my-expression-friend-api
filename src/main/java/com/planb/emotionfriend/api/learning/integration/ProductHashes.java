package com.planb.emotionfriend.api.learning.integration;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;

/** Port of the delivered product/contract.py rules for the existing 0.4 and 0.6 product JSON. */
public final class ProductHashes {
    static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Comparator<String> CODE_POINT_ORDER = (left, right) ->
            java.util.Arrays.compare(left.codePoints().toArray(), right.codePoints().toArray());

    private ProductHashes() {}

    public static String textHash(String text) {
        return bytesHash(utf8(text));
    }

    public static String bytesHash(byte[] bytes) {
        return bytesHash(ByteBuffer.wrap(bytes));
    }

    private static String bytesHash(ByteBuffer bytes) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(bytes);
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ByteBuffer utf8(String value) {
        try {
            return StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid Unicode");
        }
    }

    public static String digest(JsonNode value) {
        try {
            return textHash(JSON.writeValueAsString(canonical(value)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid product JSON");
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var keys = new ArrayList<String>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.sort(CODE_POINT_ORDER);
            var object = JSON.createObjectNode();
            for (String key : keys) {
                utf8(key);
                object.set(key, canonical(value.get(key)));
            }
            return object;
        }
        if (value.isArray()) {
            var array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        if (value.isTextual()) {
            utf8(value.textValue());
            return value;
        }
        if (value.isIntegralNumber() || value.isBoolean() || value.isNull()) return value;
        throw new IllegalArgumentException("Invalid product JSON value");
    }

    static boolean isHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}

