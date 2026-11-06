package com.planb.emotionfriend.api.agent;
import com.planb.emotionfriend.api.common.error.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
/** Only the narrowly registered storage port accepts the worker secret. All other /internal routes stay denied. */
@Configuration(proxyBeanMethods=false)
public class AgentStorageSecurity {
    @Bean @Order(0) SecurityFilterChain agentStorage(HttpSecurity http,ApiErrorWriter errors,
        @Value("${emotion-friend.agent.token:}") String token,@Value("${emotion-friend.agent.enabled:false}") boolean enabled)throws Exception {
        return http.securityMatcher("/internal/v2/agent-storage/**").csrf(AbstractHttpConfigurer::disable).cors(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(a->a.anyRequest().permitAll())
            .addFilterBefore(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest q,HttpServletResponse r,FilterChain c)throws ServletException,IOException {
                    var h=Collections.list(q.getHeaders("Authorization"));
                    if(!enabled||token.length()<32||h.size()!=1||h.getFirst().length()>512||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),h.getFirst().getBytes(StandardCharsets.UTF_8))) {
                        errors.write(q,r,ErrorCode.AUTH_REQUIRED);return;
                    }
                    c.doFilter(q,r);
                }
            },UsernamePasswordAuthenticationFilter.class).build();
    }
}
