package com.planb.emotionfriend.api.auth;

import com.planb.emotionfriend.api.common.error.ApiException;
import com.planb.emotionfriend.api.common.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public AuthRateLimiter(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void consume(String scope, String identifier, int limit, Duration window) {
        int count = transaction.execute(status -> jdbc.queryForObject("""
                insert into identity.auth_rate_bucket (bucket_key, window_start, attempts) values (?, ?, 1)
                on conflict (bucket_key) do update set
                    attempts = case when identity.auth_rate_bucket.window_start <= ? then 1 else identity.auth_rate_bucket.attempts + 1 end,
                    window_start = case when identity.auth_rate_bucket.window_start <= ? then excluded.window_start else identity.auth_rate_bucket.window_start end
                returning attempts
                """, Integer.class, TokenSecrets.hash(scope + ":" + identifier), Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant().minus(window)), Timestamp.from(clock.instant().minus(window))));
        if (count > limit) throw new ApiException(ErrorCode.RATE_LIMITED);
    }
}
