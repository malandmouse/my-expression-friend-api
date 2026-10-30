package com.planb.emotionfriend.api.common.error;

import com.planb.emotionfriend.api.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ApiErrorWriter {
    private final JsonMapper mapper;

    public ApiErrorWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public ApiError body(HttpServletRequest request, ErrorCode code, ErrorDetails details) {
        return new ApiError(code, code.message(), TraceIdFilter.traceId(request), code.retryable(), details);
    }

    public ResponseEntity<Object> response(HttpServletRequest request, int status, ErrorCode code, ErrorDetails details) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .header("X-Trace-Id", TraceIdFilter.traceId(request)).body(body(request, code, details));
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Trace-Id", TraceIdFilter.traceId(request));
        if (code.status() == 401) response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(response.getOutputStream(), body(request, code, ErrorDetails.forError(code)));
    }
}
