package com.planb.emotionfriend.api.learning.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Validates closed definitions within one specific release or draft schema document. */
final class ContractSchemas {
    private final ObjectNode root;
    private final ConcurrentHashMap<String, Schema> definitions = new ConcurrentHashMap<>();

    ContractSchemas(String resource) {
        try (var input = ContractSchemas.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing contract schema");
            root = (ObjectNode) ProductHashes.JSON.readTree(input);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable contract schema", e);
        }
    }

    void check(String name, JsonNode value) {
        Schema schema = definitions.computeIfAbsent(name, this::compile);
        if (!schema.validate(value, context -> context.executionConfig(
                config -> config.formatAssertionsEnabled(true))).isEmpty()) {
            throw new IllegalArgumentException("Invalid " + name);
        }
    }

    private Schema compile(String name) {
        if (!root.path("$defs").has(name)) throw new IllegalArgumentException("Unknown contract definition");
        var schema = root.deepCopy().put("$ref", "#/$defs/" + name);
        return SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(schema);
    }
}

