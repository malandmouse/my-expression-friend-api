package com.planb.emotionfriend.api.common.web;

import com.planb.emotionfriend.api.common.error.ApiErrorWriter;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import com.planb.emotionfriend.api.common.error.ErrorDetails;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Keeps servlet/container error dispatches in the same safe JSON format. */
@RestController
@Hidden
public class ApiErrorController implements ErrorController {
    private final ApiErrorWriter errors;

    public ApiErrorController(ApiErrorWriter errors) { this.errors = errors; }

    @RequestMapping("/error")
    ResponseEntity<Object> error(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = attribute instanceof Integer value ? value : 500;
        ErrorCode code = switch (status) {
            case 401 -> ErrorCode.AUTH_REQUIRED;
            case 403 -> ErrorCode.CHILD_ACCESS_DENIED;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_SCHEMA;
        };
        return errors.response(request, status, code, ErrorDetails.forError(code));
    }
}
