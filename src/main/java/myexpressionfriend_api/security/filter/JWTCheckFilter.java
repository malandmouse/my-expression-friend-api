package myexpressionfriend_api.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import myexpressionfriend_api.auth.dto.UserDTO;
import myexpressionfriend_api.common.dto.common.ApiResponse;
import myexpressionfriend_api.security.util.CustomJWTException;
import myexpressionfriend_api.security.util.JWTUtil;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class JWTCheckFilter extends OncePerRequestFilter {

    private final JWTUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }

        return path.startsWith("/api/auth/")
                || path.startsWith("/api/public/")
                || path.equals("/api/unity/scenarios")
                || path.startsWith("/actuator/")
                || path.startsWith("/uploads/")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/api-docs")
                || path.equals("/error")
                || path.equals("/swagger-ui.html");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new CustomJWTException("MissingToken");
            }

            String token = authHeader.substring(7);
            Map<String, Object> claims = jwtUtil.validateToken(token);

            String tokenType = (String) claims.get("type");
            if (!"access".equals(tokenType)) {
                throw new CustomJWTException("InvalidTokenType");
            }

            String userId = (String) claims.get("userId");
            String email = (String) claims.get("email");
            String name = (String) claims.get("name");
            @SuppressWarnings("unchecked")
            List<String> roleNames = (List<String>) claims.get("roles");

            if (roleNames.stream().anyMatch("PENDING"::equalsIgnoreCase)) {
                throw new CustomJWTException("PendingAccount");
            }

            List<SimpleGrantedAuthority> authorities = roleNames.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .collect(Collectors.toList());

            UserDTO userDTO = UserDTO.builder()
                    .userId(UUID.fromString(userId))
                    .email(email)
                    .name(name)
                    .build();

            UsernamePasswordAuthenticationToken authenticationToken =
                    new UsernamePasswordAuthenticationToken(userDTO, null, authorities);

            SecurityContextHolder.getContext().setAuthentication(authenticationToken);
        } catch (CustomJWTException e) {
            log.warn("JWT authentication failed: {}", e.getMessage());
            handleJWTException(response, e);
            return;
        } catch (Exception e) {
            log.error("JWT filter error: {}", e.getMessage(), e);
            handleJWTException(response, new CustomJWTException("Error"));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void handleJWTException(HttpServletResponse response, CustomJWTException e)
            throws IOException {
        String errorCode = e.getMessage();
        int statusCode;
        String message;

        switch (errorCode) {
            case "MissingToken" -> {
                statusCode = HttpServletResponse.SC_UNAUTHORIZED;
                message = "Authentication token is required";
            }
            case "MalFormed" -> {
                statusCode = HttpServletResponse.SC_UNAUTHORIZED;
                message = "Malformed token";
            }
            case "Expired" -> {
                statusCode = HttpServletResponse.SC_UNAUTHORIZED;
                message = "Token expired";
            }
            case "Invalid", "InvalidTokenType" -> {
                statusCode = HttpServletResponse.SC_UNAUTHORIZED;
                message = "Invalid token";
            }
            case "PendingAccount" -> {
                statusCode = HttpServletResponse.SC_FORBIDDEN;
                message = "Account approval is required";
            }
            default -> {
                statusCode = HttpServletResponse.SC_UNAUTHORIZED;
                message = "Authentication failed";
            }
        }

        response.setStatus(statusCode);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(message, errorCode));
    }
}
