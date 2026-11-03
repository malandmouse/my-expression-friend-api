package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.common.error.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

/** Separate credential verifier and chain; child tokens never pass through adult JWT verification. */
@Configuration(proxyBeanMethods=false)
public class RuntimeSecurityConfiguration {
    @Bean @Order(1)
    SecurityFilterChain runtimeSecurity(HttpSecurity http,RuntimeCredentials credentials,ApiErrorWriter errors) throws Exception {
        return http.securityMatcher("/api/v1/child-sessions/**","/api/v1/sessions/**","/api/v1/daily-plans/*/sessions",
                "/api/v1/children/*/learning-home","/api/v1/children/*/mission-entries","/api/v1/playback-leases/**")
            .csrf(AbstractHttpConfigurer::disable).cors(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/api/v1/child-sessions/exchange","/api/v1/child-sessions/refresh").permitAll().anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->errors.write(q,r,ErrorCode.AUTH_REQUIRED))
                    .accessDeniedHandler((q,r,x)->errors.write(q,r,ErrorCode.CHILD_ACCESS_DENIED)))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest q,HttpServletResponse r,FilterChain chain) throws ServletException,IOException {
                    var headers=Collections.list(q.getHeaders("Authorization"));
                    if(headers.isEmpty()){chain.doFilter(q,r);return;}
                    try {
                        if(headers.size()!=1 || headers.getFirst().length()>140 || !headers.getFirst().startsWith("Bearer "))throw new ApiException(ErrorCode.AUTH_REQUIRED);
                        var actor=credentials.verify(headers.getFirst().substring(7));
                        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of(new SimpleGrantedAuthority("CREDENTIAL_CHILD"))));
                        SecurityContextHolder.setContext(context);
                    }catch(ApiException e){SecurityContextHolder.clearContext();errors.write(q,r,e.code()==ErrorCode.TOKEN_EXPIRED?e.code():ErrorCode.AUTH_REQUIRED);return;}
                    try{chain.doFilter(q,r);}finally{SecurityContextHolder.clearContext();}
                }
            },UsernamePasswordAuthenticationFilter.class).build();
    }
}
