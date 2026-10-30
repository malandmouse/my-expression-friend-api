package com.planb.emotionfriend.api.common.security;

import com.planb.emotionfriend.api.common.error.ApiErrorWriter;
import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    @ConditionalOnMissingBean(AccessCredentialVerifier.class)
    AccessCredentialVerifier rejectingVerifier() {
        return credential -> { throw new ApiException(ErrorCode.AUTH_REQUIRED); };
    }

    @Bean
    @ConditionalOnMissingBean(ChildAccessChecker.class)
    ChildAccessChecker rejectingChildAccess() {
        return (actor, childId, capability) -> { throw new ApiException(ErrorCode.CHILD_ACCESS_DENIED); };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccessCredentialVerifier verifier, ApiErrorWriter errors,
            @Value("${springdoc.api-docs.enabled:true}") boolean apiDocsEnabled)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    if (apiDocsEnabled) {
                        auth.requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll();
                    }
                    auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/child-sessions/exchange", "/api/v1/child-sessions/refresh").permitAll()
                        .requestMatchers("/internal/**").denyAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll();
                })
                .exceptionHandling(errorsConfig -> errorsConfig
                        .authenticationEntryPoint((request, response, exception) -> errors.write(request, response, ErrorCode.AUTH_REQUIRED))
                        .accessDeniedHandler((request, response, exception) -> errors.write(request, response, ErrorCode.CHILD_ACCESS_DENIED)))
                .addFilterBefore(new BearerCredentialFilter(verifier, errors), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
