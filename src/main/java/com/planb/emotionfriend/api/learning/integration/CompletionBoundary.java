package com.planb.emotionfriend.api.learning.integration;

import com.planb.emotionfriend.api.learning.LearningContracts.Receipt;

/**
 * Port for a future adapter to the single LearningLedger transaction. The implementation must
 * recheck authority, progress and candidate revision inside that transaction, and bind any
 * reviewed reaction before commit. This port has no registered implementation.
 */
@FunctionalInterface
public interface CompletionBoundary {
    Receipt complete(ExpressionCurrent.CandidateProof proof);
}

