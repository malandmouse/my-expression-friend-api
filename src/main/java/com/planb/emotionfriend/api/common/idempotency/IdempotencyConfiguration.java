package com.planb.emotionfriend.api.common.idempotency;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyConfiguration {
}
