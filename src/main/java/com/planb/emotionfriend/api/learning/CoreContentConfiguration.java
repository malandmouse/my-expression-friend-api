package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.common.error.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration(proxyBeanMethods=false)
public class CoreContentConfiguration {
    @Bean @ConditionalOnMissingBean(ContentAuthority.class)
    ContentAuthority denyUnconfiguredCapabilities() {
        return (actor,child,capability)->{throw new ApiException(ErrorCode.POLICY_REVIEW_FORBIDDEN);};
    }
    @Bean @ConditionalOnMissingBean(CoreMediaDelivery.class)
    CoreMediaDelivery denyUnconfiguredMedia() {
        return artifact->{throw new ApiException(ErrorCode.CONTENT_NOT_APPROVED);};
    }
}
