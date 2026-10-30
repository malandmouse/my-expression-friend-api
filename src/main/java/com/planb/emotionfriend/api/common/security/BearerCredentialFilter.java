package com.planb.emotionfriend.api.common.security;

import com.planb.emotionfriend.api.common.error.ApiErrorWriter;
import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class BearerCredentialFilter extends OncePerRequestFilter {
    private final AccessCredentialVerifier verifier;
    private final ApiErrorWriter errors;

    BearerCredentialFilter(AccessCredentialVerifier verifier, ApiErrorWriter errors) {
        this.verifier = verifier;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        ActorPrincipal actor;
        try {
            String header = headers.getFirst();
            if (headers.size() != 1 || header.length() > 4103 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw new ApiException(ErrorCode.AUTH_REQUIRED);
            }
            String token = header.substring(7);
            if (token.isEmpty() || !token.matches("[A-Za-z0-9._~+/-]+=*")) throw new ApiException(ErrorCode.AUTH_REQUIRED);
            actor = verifier.verify(token);
            if (actor == null) throw new ApiException(ErrorCode.AUTH_REQUIRED);
        } catch (ApiException exception) {
            SecurityContextHolder.clearContext();
            // A verifier may signal only authentication failures across this boundary.
            errors.write(request, response, exception.code() == ErrorCode.TOKEN_EXPIRED
                    ? ErrorCode.TOKEN_EXPIRED : ErrorCode.AUTH_REQUIRED);
            return;
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            errors.write(request, response, ErrorCode.AUTH_REQUIRED);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority("CREDENTIAL_" + actor.kind().name()))));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
