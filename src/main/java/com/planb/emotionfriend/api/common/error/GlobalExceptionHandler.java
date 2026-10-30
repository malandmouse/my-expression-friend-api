package com.planb.emotionfriend.api.common.error;

import com.planb.emotionfriend.api.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ApiErrorWriter errors;

    public GlobalExceptionHandler(ApiErrorWriter errors) { this.errors = errors; }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> business(ApiException exception, HttpServletRequest request) {
        return errors.response(request, exception.code().status(), exception.code(), exception.details());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> constraint(HttpServletRequest request) { return error(request, ErrorCode.INVALID_SCHEMA); }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> forbidden(HttpServletRequest request) { return error(request, ErrorCode.CHILD_ACCESS_DENIED); }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> authentication(HttpServletRequest request) { return error(request, ErrorCode.AUTH_REQUIRED); }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                            HttpStatusCode status, WebRequest request) {
        HttpServletRequest servlet = ((ServletWebRequest) request).getRequest();
        ErrorCode code = switch (status.value()) {
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_SCHEMA;
        };
        int actualStatus = status.value() == 400 ? 422 : status.value();
        ResponseEntity<Object> result = errors.response(servlet, actualStatus, code, ErrorDetails.forError(code));
        HttpHeaders merged = new HttpHeaders();
        merged.putAll(headers);
        merged.putAll(result.getHeaders());
        return new ResponseEntity<>(result.getBody(), merged, actualStatus);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
        // Never log exception messages: database/provider exceptions can contain submitted text or credentials.
        log.error("Unhandled request failure type={} traceId={}", exception.getClass().getSimpleName(), TraceIdFilter.traceId(request));
        return error(request, ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<Object> error(HttpServletRequest request, ErrorCode code) {
        return errors.response(request, code.status(), code, ErrorDetails.forError(code));
    }
}
