package com.planb.emotionfriend.api.learning.integration;

import com.planb.emotionfriend.api.common.security.ActorPrincipal;
import com.planb.emotionfriend.api.learning.LearningContracts.Receipt;
import com.planb.emotionfriend.api.learning.LearningCurrent;
import com.planb.emotionfriend.api.learning.RuntimeContracts.Commit;
import java.util.Objects;

/** Internal completion boundary. A null selection identifies the cardless ORIGINAL path. */
@FunctionalInterface
public interface CompletionBoundary {
    record Command(LearningCurrent.Query query, Commit commit, ExpressionCurrent.OriginalSelection selection) {
        public Command {
            Objects.requireNonNull(commit);
            if (query.actor().kind() != ActorPrincipal.Kind.CHILD) {
                throw new IllegalArgumentException("Runtime completion requires a verified runtime actor");
            }
        }
    }

    Receipt complete(Command command);
}

