package com.planb.emotionfriend.api.mail;

import java.time.Duration;

public interface MailSender {
    Result send(Message message, String idempotencyKey);

    record Message(String from, String to, String subject, String text) {
        @Override
        public String toString() {
            return "MailMessage[redacted]";
        }
    }

    enum Outcome {
        ACCEPTED,
        RETRY,
        FAILED
    }

    record Result(Outcome outcome, String providerId, Duration retryAfter, String code) {
        public static Result accepted(String id) {
            return new Result(Outcome.ACCEPTED, id, null, null);
        }

        public static Result retry(String code, Duration after) {
            return new Result(Outcome.RETRY, null, after, code);
        }

        public static Result failed(String code) {
            return new Result(Outcome.FAILED, null, null, code);
        }
    }
}
