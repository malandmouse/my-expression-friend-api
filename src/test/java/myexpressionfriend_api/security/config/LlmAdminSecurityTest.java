package myexpressionfriend_api.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import myexpressionfriend_api.security.handler.CustomAccessDeniedHandler;
import myexpressionfriend_api.security.handler.CustomAuthenticationEntryPoint;
import myexpressionfriend_api.security.util.JWTUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = LlmAdminSecurityTest.TestConfiguration.class)
@WebAppConfiguration
class LlmAdminSecurityTest {
    private static final String[] ADMIN_PATHS = {
            "/api/llm/health-check", "/api/llm/error-pattern/run", "/api/llm/dialogue/rebuild"
    };
    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousRequestsCannotTriggerExternalAiOrAdminWork() throws Exception {
        for (String path : ADMIN_PATHS) mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void regularUsersCannotTriggerAdminWork() throws Exception {
        for (String path : ADMIN_PATHS) {
            mvc.perform(get(path).with(user("parent").roles("PARENT"))).andExpect(status().isForbidden());
        }
    }

    @Test
    void adminsCanAccessProtectedPathsAndRenderHealthRemainsPublic() throws Exception {
        for (String path : ADMIN_PATHS) {
            mvc.perform(get(path).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        }
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, CustomAccessDeniedHandler.class, CustomAuthenticationEntryPoint.class})
    static class TestConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean JWTUtil jwtUtil() { return mock(JWTUtil.class); }
        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    @RestController
    static class ProbeController {
        @GetMapping({"/api/llm/health-check", "/api/llm/error-pattern/run", "/api/llm/dialogue/rebuild", "/actuator/health"})
        public String probe() { return "OK"; }
    }
}
