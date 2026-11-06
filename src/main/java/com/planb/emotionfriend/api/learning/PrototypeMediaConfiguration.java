package com.planb.emotionfriend.api.learning;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="emotion-friend.prototype.publication-enabled",havingValue="true")
public class PrototypeMediaConfiguration {
    @Bean @ConditionalOnMissingBean(CoreMediaDelivery.class)
    CoreMediaDelivery prototypeMedia(CorePublicationService content){return id->content.readArtifact(id).bytes();}
}
