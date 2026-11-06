package com.planb.emotionfriend.api.learning;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 변경 불가한 원고/음원의 검사 결과만 재사용합니다. 권한·동의·철회 결과는 보관하지 않습니다. */
final class CoreVerifiedContentCache {
    private record Entry(List<UUID> artifacts, Instant expires) {}
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Clock clock;
    private final int capacity;
    private final Duration ttl;

    CoreVerifiedContentCache(Clock clock) {
        this(clock, 128, Duration.ofMinutes(5));
    }

    CoreVerifiedContentCache(Clock clock, int capacity, Duration ttl) {
        this.clock = clock;
        this.capacity = capacity;
        this.ttl = ttl;
    }

    List<UUID> get(String key) {
        var local = memo(false);
        if (local != null && local.values.containsKey(key)) return local.values.get(key);
        synchronized (entries) {
            var entry = entries.get(key);
            if (entry == null) return null;
            if (!entry.expires().isAfter(clock.instant())) {
                entries.remove(key);
                return null;
            }
            return entry.artifacts();
        }
    }

    void remember(String key, List<UUID> artifacts) {
        // 커밋 전 검사 결과를 다른 요청에 공개하지 않습니다. 롤백이면 메모도 폐기됩니다.
        var local = memo(true);
        if (local != null) local.values.put(key, List.copyOf(artifacts));
    }

    private Memo memo(boolean create) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) return null;
        for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof Memo memo && memo.owner() == this) return memo;
        }
        if (!create) return null;
        var memo = new Memo();
        TransactionSynchronizationManager.registerSynchronization(memo);
        return memo;
    }

    private final class Memo implements TransactionSynchronization {
        private final Map<String, List<UUID>> values = new LinkedHashMap<>();

        CoreVerifiedContentCache owner() {
            return CoreVerifiedContentCache.this;
        }

        @Override public void afterCommit() {
            synchronized (entries) {
                Instant expires = clock.instant().plus(ttl);
                values.forEach((key, ids) -> entries.put(key, new Entry(ids, expires)));
                while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
            }
        }
    }
}
