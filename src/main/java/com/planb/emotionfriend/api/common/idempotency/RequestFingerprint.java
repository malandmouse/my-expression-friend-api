package com.planb.emotionfriend.api.common.idempotency;

import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class RequestFingerprint {
    private final JsonMapper mapper;
    private final SecretKeySpec key;

    public RequestFingerprint(JsonMapper mapper, IdempotencyProperties properties) {
        this.mapper = mapper;
        this.key = new SecretKeySpec(IdempotencyProperties.decode(properties.fingerprintKey()), "HmacSHA256");
    }

    public String actorScope(ActorPrincipal actor) {
        ArrayNode identity = mapper.createArrayNode().add(actor.kind().name()).add(actor.actorId());
        if (actor.kind() == ActorPrincipal.Kind.CHILD) identity.add(actor.childId()).add(actor.deviceId());
        return digest("actor", mapper.writeValueAsBytes(identity));
    }

    public String request(Object body) {
        return digest("request", mapper.writeValueAsBytes(canonical(mapper.valueToTree(body))));
    }

    public String key(String value) { return digest("key", value.getBytes(StandardCharsets.UTF_8)); }

    public String scope(String actor, String method, String path, String keyDigest) {
        return digest("scope", mapper.writeValueAsBytes(mapper.createArrayNode()
                .add(actor).add(method).add(path).add(keyDigest)));
    }

    private String digest(String purpose, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(value));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Request fingerprint algorithm unavailable");
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            var names = new ArrayList<>(value.propertyNames());
            names.sort(String::compareTo);
            for (String name : names) result.set(name, canonical(value.get(name)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }
}
