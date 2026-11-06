package com.planb.emotionfriend.api.learning;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;

class CoreVerifiedContentCacheTest {
    static class Time extends Clock {Instant value=Instant.parse("2026-11-06T00:00:00Z");public Instant instant(){return value;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}}
    Time time=new Time();CoreVerifiedContentCache cache=new CoreVerifiedContentCache(time,2,Duration.ofSeconds(30));UUID asset=UUID.randomUUID();
    void begin(){TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.initSynchronization();}
    void finish(boolean commit){var syncs=TransactionSynchronizationManager.getSynchronizations();if(commit)syncs.forEach(TransactionSynchronization::afterCommit);syncs.forEach(s->s.afterCompletion(commit?0:1));TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.setActualTransactionActive(false);}
    @AfterEach void clean(){if(TransactionSynchronizationManager.isSynchronizationActive())finish(false);}
    @Test void verifiedBytesAreReusedInOneTransactionAndOnlyPublishedAfterCommit() throws Exception {
        begin();cache.remember("a",List.of(asset));assertThat(cache.get("a")).containsExactly(asset);
        var other=new java.util.concurrent.atomic.AtomicReference<List<UUID>>();Thread thread=new Thread(()->other.set(cache.get("a")));thread.start();thread.join();assertThat(other.get()).isNull();
        finish(true);assertThat(cache.get("a")).containsExactly(asset);
    }
    @Test void rollbackDoesNotPromoteUncommittedValidation(){begin();cache.remember("a",List.of(asset));finish(false);assertThat(cache.get("a")).isNull();}
    @Test void differentContentVersionExpiryAndCapacityCauseACacheMiss(){
        begin();cache.remember("v1",List.of(asset));finish(true);assertThat(cache.get("v2")).isNull();
        time.value=time.value.plusSeconds(31);assertThat(cache.get("v1")).isNull();
        begin();cache.remember("a",List.of(asset));cache.remember("b",List.of(asset));finish(true);assertThat(cache.get("a")).isNotNull();
        begin();cache.remember("c",List.of(asset));finish(true);assertThat(cache.get("b")).isNull();assertThat(cache.get("a")).isNotNull();
    }
}
