package myexpressionfriend_api.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import myexpressionfriend_api.security.filter.JWTCheckFilter;
import myexpressionfriend_api.security.handler.CustomAccessDeniedHandler;
import myexpressionfriend_api.security.handler.CustomAuthenticationEntryPoint;
import myexpressionfriend_api.security.util.JWTUtil;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

    private final JWTUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;

    @Value("${app.cors.allowed-origin-patterns:http://localhost:5173}")
    private String allowedOriginPatterns;

    @Bean
    public JWTCheckFilter jwtCheckFilter() {
        return new JWTCheckFilter(jwtUtil, objectMapper);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ASYNC/ERROR dispatch 타입은 인가 재검사 없이 통과
                        // SseEmitter 비동기 처리 시 SecurityContext가 없는 스레드에서 재실행되므로 필수
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                "/api/llm/health-check",
                                "/api/llm/error-pattern/run",
                                "/api/llm/dialogue/rebuild"
                        ).hasRole("ADMIN")
                        .requestMatchers(
                                "/api/auth/**",
                                "/api/unity/scenarios",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/actuator/health",
                                "/uploads/**",
                                "/error"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtCheckFilter(), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(exception -> exception
                        .accessDeniedHandler(accessDeniedHandler)
                        .authenticationEntryPoint(authenticationEntryPoint)
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(parseAllowedOriginPatterns());
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Cache-Control", "X-Requested-With", "Last-Event-ID"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private List<String> parseAllowedOriginPatterns() {
        return Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
