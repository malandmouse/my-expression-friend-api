package com.planb.emotionfriend.api.mail;

import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * No provider response bodies, addresses, authorization headers or verification URLs are logged.
 */
public final class ResendMailSender implements MailSender {
    private final HttpClient client;
    private final URI endpoint;
    private final MailProperties properties;
    private final JsonMapper mapper;

    public ResendMailSender(MailProperties properties, JsonMapper mapper) {
        this(
                properties,
                mapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                URI.create("https://api.resend.com/emails"));
    }

    ResendMailSender(
            MailProperties properties, JsonMapper mapper, HttpClient client, URI endpoint) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
        this.endpoint = endpoint;
    }

    @Override
    public Result send(Message message, String idempotencyKey) {
        CompletableFuture<HttpResponse<byte[]>> exchange = null;
        try {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("from", message.from());
            fields.put("to", List.of(message.to()));
            fields.put("subject", message.subject());
            fields.put("text", message.text());
            byte[] body = mapper.writeValueAsBytes(fields);
            var request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(8))
                            .header("Authorization", "Bearer " + properties.apiKey())
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .header("Idempotency-Key", idempotencyKey)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build();
            exchange = client.sendAsync(request, info -> new BoundedBody());
            var response = exchange.get(8, TimeUnit.SECONDS);
            byte[] data = response.body();
            int status = response.statusCode();
            if (status == 429 || status == 408 || status >= 500)
                return Result.retry(
                        "PROVIDER_TEMPORARY",
                        retryAfter(response.headers().firstValue("Retry-After").orElse(null)));
            if (data.length > 8192) return Result.retry("INVALID_PROVIDER_RESPONSE", null);
            if (status == 409) {
                try {
                    if ("concurrent_idempotent_requests"
                            .equals(mapper.readTree(data).path("name").asText()))
                        return Result.retry("PROVIDER_CONCURRENT", Duration.ofSeconds(2));
                } catch (RuntimeException ignored) {
                }
            }
            if (status >= 200 && status < 300) {
                try {
                    String id = mapper.readTree(data).path("id").asText();
                    if (id.matches("[A-Za-z0-9_-]{1,128}")) return Result.accepted(id);
                } catch (RuntimeException ignored) {
                }
                return Result.retry("INVALID_PROVIDER_RESPONSE", null);
            }
            return Result.failed("PROVIDER_REJECTED");
        } catch (InterruptedException interrupted) {
            if (exchange != null) exchange.cancel(true);
            Thread.currentThread().interrupt();
            return Result.retry("INTERRUPTED", null);
        } catch (TimeoutException failure) {
            if (exchange != null) exchange.cancel(true);
            return Result.retry("NETWORK_TIMEOUT", null);
        } catch (ExecutionException failure) {
            return Result.retry("NETWORK_FAILURE", null);
        }
    }

    private Duration retryAfter(String value) {
        if (value == null) return null;
        try {
            long seconds = Long.parseLong(value);
            return Duration.ofSeconds(Math.clamp(seconds, 1, 900));
        } catch (NumberFormatException ignored) {
            try {
                return Duration.ofSeconds(
                        Math.clamp(
                                Duration.between(
                                                Instant.now(),
                                                ZonedDateTime.parse(
                                                                value,
                                                                DateTimeFormatter
                                                                        .RFC_1123_DATE_TIME)
                                                        .toInstant())
                                        .getSeconds(),
                                1,
                                900));
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 8192) {
                    subscription.cancel();
                    result.completeExceptionally(
                            new IOException("Provider response exceeded limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
