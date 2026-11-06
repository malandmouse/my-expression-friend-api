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
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="emotion-friend.prototype.publication-enabled",havingValue="false",matchIfMissing=true)
    CoreMediaDelivery denyUnconfiguredMedia() {
        return artifact->{throw new ApiException(ErrorCode.CONTENT_NOT_APPROVED);};
    }
}
