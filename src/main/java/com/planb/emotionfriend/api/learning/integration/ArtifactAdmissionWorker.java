package com.planb.emotionfriend.api.learning.integration;

import static com.planb.emotionfriend.api.learning.integration.GenerationJobs.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Verifies server-retrieved bytes before a worker result can become RESULT_READY. */
public final class ArtifactAdmissionWorker implements Worker {
    @FunctionalInterface
    public interface ArtifactBytes {
        /** Retrieve by an opaque server-side ID under the claim's authority; never trust a client URL. */
        Optional<byte[]> read(Job claim, UUID artifactId);
    }

    private final Worker delegate;
    private final ArtifactBytes artifacts;
    private final long maximumBytes;

    public ArtifactAdmissionWorker(Worker delegate, ArtifactBytes artifacts, long maximumBytes) {
        this.delegate = Objects.requireNonNull(delegate);
        this.artifacts = Objects.requireNonNull(artifacts);
        if (maximumBytes < 1) throw new IllegalArgumentException("Invalid artifact limit");
        this.maximumBytes = maximumBytes;
    }

    @Override public Result execute(Job claim) {
        Result result = Objects.requireNonNull(delegate.execute(claim));
        if (result.state() != State.RESULT_READY) return result;
        Artifact artifact = result.artifact();
        byte[] bytes = artifacts.read(claim, artifact.id()).orElse(null);
        if (bytes == null || bytes.length == 0 || bytes.length > maximumBytes
                || !hash(bytes).equals(artifact.bytesHash())) {
            return new Result(State.HELD, null);
        }
        // Copy is owned by the trusted ArtifactBytes implementation; no child text is logged here.
        return result;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
