package com.planb.emotionfriend.api.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

@ConfigurationProperties("emotion-friend.mail")
public record MailProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String from,
        @DefaultValue("") String frontendBaseUrl) {
    public MailProperties {
        if (enabled) {
            if (apiKey == null || !apiKey.matches("re_[A-Za-z0-9_-]{8,}"))
                throw new IllegalArgumentException(
                        "Configure EF_RESEND_API_KEY for enabled email delivery");
            if (from == null
                    || from.length() > 254
                    || !from.matches("[^\\s<>@]+@[^\\s<>@]+\\.[^\\s<>@]+"))
                throw new IllegalArgumentException(
                        "Configure EF_MAIL_FROM as a verified sender email address");
            try {
                URI base = URI.create(frontendBaseUrl);
                boolean local =
                        java.util.Set.of("localhost", "127.0.0.1", "[::1]")
                                .contains(base.getHost());
                if (base.getHost() == null
                        || !(base.getScheme().equals("https")
                                || (base.getScheme().equals("http") && local))
                        || base.getRawUserInfo() != null
                        || base.getRawQuery() != null
                        || base.getRawFragment() != null) throw new IllegalArgumentException();
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(
                        "Configure EF_FRONTEND_BASE_URL as an HTTPS web origin/path, or loopback"
                            + " HTTP for development");
            }
        }
    }

    public String verificationLink(String verificationId, String token) {
        String base = frontendBaseUrl.replaceAll("/+$", "");
        return base + "/#/verify-email?verificationId=" + verificationId + "&token=" + token;
    }

    @Override
    public String toString() {
        return "MailProperties[redacted]";
    }
}
