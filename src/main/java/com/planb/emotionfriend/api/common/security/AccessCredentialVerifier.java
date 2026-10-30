package com.planb.emotionfriend.api.common.security;

/** Implement in the authentication module: verify signature/secret, expiry, revocation and current generation. */
@FunctionalInterface
public interface AccessCredentialVerifier {
    ActorPrincipal verify(String credential);
}
