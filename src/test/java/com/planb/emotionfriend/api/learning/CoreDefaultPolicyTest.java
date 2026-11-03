package com.planb.emotionfriend.api.learning;

import static org.assertj.core.api.Assertions.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import org.junit.jupiter.api.Test;
import java.util.UUID;

class CoreDefaultPolicyTest {
    @Test void unconfiguredCapabilitiesAndMediaAlwaysDeny() {
        var config=new CoreContentConfiguration();var actor=new ActorPrincipal(UUID.randomUUID().toString(),ActorPrincipal.Kind.ADULT,null,null,null);
        for(var cap:ContentAuthority.Capability.values())assertThatThrownBy(()->config.denyUnconfiguredCapabilities().require(actor,UUID.randomUUID(),cap))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(ErrorCode.POLICY_REVIEW_FORBIDDEN));
        assertThatThrownBy(()->config.denyUnconfiguredMedia().read(UUID.randomUUID())).isInstanceOf(ApiException.class);
    }
}
