package com.planb.emotionfriend.api.learning;

import com.planb.emotionfriend.api.common.error.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;

/** Bounded transient confirmation proofs. Raw expressions are never retained or persisted. */
@Component
public class ConfirmedExpressions {
    private record Proof(UUID session, int slot, String actor, long version, Instant expiresAt) {}
    private final Map<UUID,Proof> proofs=new HashMap<>();
    private final Clock clock;
    public ConfirmedExpressions(Clock clock) { this.clock=clock; }
    public synchronized LearningContracts.Expression confirm(UUID session,int slot,String actor,long version) {
        proofs.entrySet().removeIf(e->!e.getValue().expiresAt().isAfter(clock.instant()));
        if(proofs.size()>=10000) throw new ApiException(ErrorCode.RATE_LIMITED);
        // A newer draft invalidates the previous proof. Older/equal drafts cannot replace it.
        for(var p:proofs.values()) if(p.session().equals(session) && p.slot()==slot && p.version()>=version)
            throw new ApiException(ErrorCode.STALE_DRAFT);
        UUID ref=UUID.randomUUID();
        Instant expires=clock.instant().plusSeconds(900);
        proofs.put(ref,new Proof(session,slot,actor,version,expires));
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    synchronized(ConfirmedExpressions.this) {
                        if(status!=STATUS_COMMITTED) proofs.remove(ref);
                        else removeOlder(session,slot,version);
                    }
                }
            });
        } else removeOlder(session,slot,version);
        return new LearningContracts.Expression(ref,expires,version);
    }
    public synchronized void require(UUID ref,UUID session,int slot,String actor,long version) {
        Proof p=proofs.get(ref);
        if(p==null || !p.session().equals(session) || p.slot()!=slot || !p.actor().equals(actor)
                || p.version()!=version || !p.expiresAt().isAfter(clock.instant())
                || proofs.values().stream().anyMatch(other->other.session().equals(session)
                        && other.slot()==slot && other.version()>version))
            throw new ApiException(ErrorCode.STALE_DRAFT);
    }
    private void removeOlder(UUID session,int slot,long version) {
        proofs.entrySet().removeIf(e->e.getValue().session().equals(session)
                && e.getValue().slot()==slot && e.getValue().version()<version);
    }
}
