package com.planb.emotionfriend.api.membership;

import com.planb.emotionfriend.api.common.error.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;

@Component
@ConditionalOnProperty(name = "emotion-friend.bootstrap-admin.enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TransactionTemplate tx;
    private final MemberGuard guard;
    private final AuditTrail audit;
    private final Clock clock;
    private final String email;
    private final String password;

    public AdminBootstrap(
            JdbcTemplate jdbc,
            PasswordEncoder passwords,
            org.springframework.transaction.PlatformTransactionManager manager,
            MemberGuard guard,
            AuditTrail audit,
            Clock clock,
            @Value("${EF_BOOTSTRAP_ADMIN_EMAIL:}") String email,
            @Value("${EF_BOOTSTRAP_ADMIN_PASSWORD:}") String password) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tx = new TransactionTemplate(manager);
        this.guard = guard;
        this.audit = audit;
        this.clock = clock;
        this.email = email.strip().toLowerCase(Locale.ROOT);
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || email.length() > 254
                || password.length() < 15
                || password.length() > 128)
            throw new IllegalArgumentException(
                    "Supply valid bootstrap admin email and 15-128 character password through"
                            + " environment variables");
        String hash = passwords.encode(password);
        tx.executeWithoutResult(
                status -> {
                    guard.lockDomain();
                    var existing =
                            jdbc.query(
                                    "select email from identity.member where role='ADMIN'",
                                    (rs, row) -> rs.getString(1));
                    if (!existing.isEmpty()) {
                        if (existing.contains(email)) return;
                        throw new IllegalStateException(
                                "An administrator already exists; bootstrap cannot add another");
                    }
                    if (Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    "select exists(select 1 from identity.member where email=?)",
                                    Boolean.class,
                                    email)))
                        throw new IllegalStateException(
                                "Bootstrap cannot elevate an existing public account");
                    UUID id = UUID.randomUUID();
                    var now = Timestamp.from(clock.instant());
                    jdbc.update(
                            "insert into identity.member"
                                + " (id,email,display_name,password_hash,role,status,email_verified_at,created_at,updated_at)"
                                + " values (?,?,'운영자',?,'ADMIN','ACTIVE',?,?,?)",
                            id,
                            email,
                            hash,
                            now,
                            now,
                            now);
                    audit.record(
                            id,
                            "ADMIN_BOOTSTRAPPED",
                            id,
                            "Explicit local operator bootstrap",
                            Map.of(),
                            Map.of("role", "ADMIN"));
                });
    }
}
