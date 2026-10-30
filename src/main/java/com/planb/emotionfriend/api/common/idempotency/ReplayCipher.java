package com.planb.emotionfriend.api.common.idempotency;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class ReplayCipher {
    private static final int NONCE_BYTES = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ReplayCipher(IdempotencyProperties properties) {
        this.key = new SecretKeySpec(IdempotencyProperties.decode(properties.encryptionKey()), "AES");
    }

    public byte[] encrypt(byte[] plaintext, String binding) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, plaintext, nonce, binding);
        return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
    }

    public byte[] decrypt(byte[] stored, String binding) {
        if (stored.length < NONCE_BYTES + 16) throw new IllegalStateException("Invalid replay ciphertext");
        ByteBuffer buffer = ByteBuffer.wrap(stored);
        byte[] nonce = new byte[NONCE_BYTES];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        return crypt(Cipher.DECRYPT_MODE, ciphertext, nonce, binding);
    }

    private byte[] crypt(int mode, byte[] bytes, byte[] nonce, String binding) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(bytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Replay response encryption/authentication failed");
        }
    }
}
