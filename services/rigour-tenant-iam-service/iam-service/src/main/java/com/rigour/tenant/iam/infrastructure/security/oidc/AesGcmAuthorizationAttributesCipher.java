package com.rigour.tenant.iam.infrastructure.security.oidc;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** AES-256-GCM授权上下文加密器；密文携带格式版本和随机IV，不携带密钥。 */
public final class AesGcmAuthorizationAttributesCipher implements AuthorizationAttributesCipher {

    private static final byte FORMAT_VERSION = 1;
    private static final int AES_256_KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final String activeKeyVersion;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom secureRandom;

    public AesGcmAuthorizationAttributesCipher(String activeKeyVersion, Map<String, byte[]> keys) {
        this(activeKeyVersion, keys, new SecureRandom());
    }

    AesGcmAuthorizationAttributesCipher(String activeKeyVersion, Map<String, byte[]> keys, SecureRandom secureRandom) {
        if (activeKeyVersion == null || activeKeyVersion.isBlank()) {
            throw new IllegalArgumentException("activeKeyVersion cannot be blank");
        }
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("At least one authorization attributes key is required");
        }
        Map<String, SecretKeySpec> validatedKeys = new HashMap<>();
        keys.forEach((version, keyBytes) -> {
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("Authorization attributes key version cannot be blank");
            }
            if (keyBytes == null || keyBytes.length != AES_256_KEY_BYTES) {
                throw new IllegalArgumentException("Authorization attributes keys must be 32-byte AES-256 keys");
            }
            validatedKeys.put(version, new SecretKeySpec(keyBytes.clone(), "AES"));
        });
        if (!validatedKeys.containsKey(activeKeyVersion)) {
            throw new IllegalArgumentException("Active authorization attributes key version is not configured");
        }
        this.activeKeyVersion = activeKeyVersion;
        this.keys = Map.copyOf(validatedKeys);
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom cannot be null");
    }

    @Override
    public EncryptedAttributes encrypt(byte[] plaintext, byte[] additionalAuthenticatedData) {
        Objects.requireNonNull(plaintext, "plaintext cannot be null");
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, keys.get(activeKeyVersion), iv, plaintext,
                additionalAuthenticatedData);
        byte[] payload = ByteBuffer.allocate(1 + iv.length + encrypted.length)
                .put(FORMAT_VERSION)
                .put(iv)
                .put(encrypted)
                .array();
        return new EncryptedAttributes(activeKeyVersion, payload);
    }

    @Override
    public byte[] decrypt(String keyVersion, byte[] ciphertext, byte[] additionalAuthenticatedData) {
        SecretKeySpec key = keys.get(keyVersion);
        if (key == null) {
            throw new IllegalArgumentException("Unknown authorization attributes key version: " + keyVersion);
        }
        if (ciphertext == null || ciphertext.length <= 1 + GCM_IV_BYTES) {
            throw new IllegalArgumentException("Authorization attributes ciphertext is invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
        if (buffer.get() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported authorization attributes ciphertext format");
        }
        byte[] iv = new byte[GCM_IV_BYTES];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        return crypt(Cipher.DECRYPT_MODE, key, iv, encrypted, additionalAuthenticatedData);
    }

    private byte[] crypt(int mode, SecretKeySpec key, byte[] iv, byte[] input, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Authorization attributes encryption operation failed", exception);
        }
    }
}
