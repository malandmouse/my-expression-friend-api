package com.planb.emotionfriend.api.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class FoundationConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JsonMapper strictJson() {
        var factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(64).maxStringLength(1_048_576).build()).build();
        return JsonMapper.builder(factory).findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    }
}
