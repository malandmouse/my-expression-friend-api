package com.planb.emotionfriend.api.common.idempotency;

import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/** Exact successful wire bytes; never include this object's body in logs. */
public record StoredResponse(int status, String contentType, byte[] body) {
    public StoredResponse {
        if (status < 200 || status > 299) throw new IllegalArgumentException("Only accepted responses can be replayed");
        body = Objects.requireNonNull(body).clone();
        if (status == 204) {
            if (body.length != 0 || contentType != null) throw new IllegalArgumentException("204 must have no body or content type");
        } else if (!MediaType.APPLICATION_JSON_VALUE.equals(contentType)) {
            throw new IllegalArgumentException("Only JSON or bodyless 204 responses are supported");
        }
    }

    public static StoredResponse json(JsonMapper mapper, int status, Object body) {
        return new StoredResponse(status, MediaType.APPLICATION_JSON_VALUE, mapper.writeValueAsBytes(body));
    }

    public static StoredResponse noContent() { return new StoredResponse(204, null, new byte[0]); }
    @Override public byte[] body() { return body.clone(); }
    @Override public String toString() { return "StoredResponse[status=" + status + ", body=redacted]"; }

    public ResponseEntity<byte[]> toResponseEntity() {
        var builder = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (contentType != null) builder.contentType(MediaType.parseMediaType(contentType));
        return builder.body(body());
    }
}
