package com.planb.emotionfriend.api.mail;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MailPropertiesTest {
    @Test
    void disabledModeNeedsNoSecrets() {
        assertThat(new MailProperties(false, "", "", "").enabled()).isFalse();
    }

    @Test
    void enabledModeRequiresKeySenderAndSafeFrontendUrl() {
        assertThatThrownBy(
                        () ->
                                new MailProperties(
                                        true, "", "sender@example.com", "https://web.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new MailProperties(
                                        true,
                                        "re_synthetic_key",
                                        "bad\naddress",
                                        "https://web.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String base :
                new String[] {
                    "http://public.example.com",
                    "https://web.example.com/#/bad",
                    "https://web.example.com/?token=x",
                    "https://user:pass@web.example.com",
                    "javascript:alert(1)"
                })
            assertThatThrownBy(
                            () ->
                                    new MailProperties(
                                            true, "re_synthetic_key", "sender@example.com", base))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linksKeepSecretsInTheFragmentAndSupportBasePaths() {
        var properties =
                new MailProperties(
                        true,
                        "re_synthetic_key",
                        "sender@example.com",
                        "https://web.example.com/app/");
        String link = properties.verificationLink("verification-id", "secret-token");
        var uri = java.net.URI.create(link);
        assertThat(uri.getQuery()).isNull();
        assertThat(uri.getPath()).isEqualTo("/app/");
        assertThat(uri.getFragment())
                .contains("/verify-email?verificationId=verification-id&token=secret-token");
        assertThat(properties.toString()).doesNotContain("re_synthetic_key");
        assertThat(
                        new MailProperties(
                                        true,
                                        "re_synthetic_key",
                                        "sender@example.com",
                                        "http://localhost:5173")
                                .enabled())
                .isTrue();
    }
}
