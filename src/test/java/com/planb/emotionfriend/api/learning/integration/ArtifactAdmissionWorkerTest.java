package com.planb.emotionfriend.api.learning.integration;

import static com.planb.emotionfriend.api.learning.integration.GenerationJobs.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactAdmissionWorkerTest {
    private final byte[] bytes = "synthetic artifact".getBytes(StandardCharsets.UTF_8);
    private final UUID id = UUID.randomUUID();
    private final Job claim = new Job(UUID.randomUUID(),
            new Request(new Scope(UUID.randomUUID(), UUID.randomUUID(), Kind.AUTHORING),
                    new Input(UUID.randomUUID(), 1, 1, UUID.randomUUID(), null)),
            State.RUNNING, 1, Instant.parse("2026-11-04T00:00:00Z"), null);

    @Test void admitsOnlyMatchingServerRetrievedBytes() throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Worker claimed = job -> new Result(State.RESULT_READY, new Artifact(id, hash));
        assertThat(new ArtifactAdmissionWorker(claimed, (job, ref) -> {
            assertThat(job).isSameAs(claim);
            assertThat(ref).isEqualTo(id);
            return Optional.of(bytes);
        }, bytes.length).execute(claim).state()).isEqualTo(State.RESULT_READY);
        assertThat(new ArtifactAdmissionWorker(claimed, (claim, ref) -> Optional.empty(), bytes.length)
                .execute(claim).state()).isEqualTo(State.HELD);
        assertThat(new ArtifactAdmissionWorker(claimed, (claim, ref) -> Optional.of("altered".getBytes(StandardCharsets.UTF_8)), bytes.length)
                .execute(claim).state()).isEqualTo(State.HELD);
        assertThat(new ArtifactAdmissionWorker(claimed, (claim, ref) -> Optional.of(bytes), bytes.length - 1)
                .execute(claim).state()).isEqualTo(State.HELD);
    }

    @Test void preservesNonReadyStatesWithoutReadingArtifact() {
        Worker held = job -> new Result(State.OUTCOME_UNKNOWN, null);
        assertThat(new ArtifactAdmissionWorker(held, (claim, ref) -> {
            throw new AssertionError("Artifact must not be read");
        }, 1).execute(claim).state()).isEqualTo(State.OUTCOME_UNKNOWN);
    }
}
