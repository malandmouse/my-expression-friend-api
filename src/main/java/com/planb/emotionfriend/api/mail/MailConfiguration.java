package com.planb.emotionfriend.api.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailProperties.class)
@EnableScheduling
public class MailConfiguration {
    @Bean
    @ConditionalOnMissingBean(MailSender.class)
    MailSender mailSender(MailProperties properties, JsonMapper mapper) {
        return new ResendMailSender(properties, mapper);
    }
}
