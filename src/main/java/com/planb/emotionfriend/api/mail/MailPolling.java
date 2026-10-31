package com.planb.emotionfriend.api.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "emotion-friend.mail",
        name = {"enabled", "scheduling-enabled"},
        havingValue = "true")
public class MailPolling {
    private static final Logger log = LoggerFactory.getLogger(MailPolling.class);
    private final EmailDelivery delivery;

    public MailPolling(EmailDelivery delivery) {
        this.delivery = delivery;
    }

    @Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT5S")
    public void poll() {
        try {
            delivery.dispatchBatch(10);
        } catch (RuntimeException failure) {
            log.error("Mail worker failure type={}", failure.getClass().getSimpleName());
        }
    }
}
