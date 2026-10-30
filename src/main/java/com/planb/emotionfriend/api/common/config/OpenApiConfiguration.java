package com.planb.emotionfriend.api.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfiguration {
    private static final Set<String> PUBLIC_POSTS = Set.of(
            "/api/v1/auth/login", "/api/v1/auth/refresh",
            "/api/v1/child-sessions/exchange", "/api/v1/child-sessions/refresh");

    @Bean
    OpenAPI emotionFriendOpenApi() {
        return new OpenAPI()
                .info(new Info().title("표정친구 백엔드 API").version("0.0.1")
                        .description("현재 서버에 구현된 API를 표시합니다. 공통 기반 단계에서는 health 조회를 확인할 수 있습니다. "
                                + "기능 명세의 Core 58개 API는 구현되는 순서대로 추가됩니다. "
                                + "인증 기능 연결 전에는 보호 API가 모든 자격 증명을 거부합니다."))
                .components(new Components()
                        .addSecuritySchemes("adultBearer", bearer("성인 자격 증명. 해당 아동의 현재 접근 권한을 별도로 확인합니다."))
                        .addSecuritySchemes("childBearer", bearer("아동·기기·학습 범위의 자격 증명. 성인 자격 증명과 구분합니다.")))
                .addSecurityItem(new SecurityRequirement().addList("adultBearer"))
                .addSecurityItem(new SecurityRequirement().addList("childBearer"));
    }

    @Bean
    OpenApiCustomizer publicOperationSecurity() {
        return api -> {
            if (api.getPaths() == null) return;
            api.getPaths().forEach((path, item) -> {
                if (path.equals("/actuator/health") || path.equals("/actuator/health/liveness")) {
                    if (item.getGet() != null) item.getGet().setSecurity(List.of());
                }
                if (PUBLIC_POSTS.contains(path) && item.getPost() != null) {
                    item.getPost().setSecurity(List.of());
                }
            });
        };
    }

    private SecurityScheme bearer(String description) {
        return new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").description(description);
    }
}
