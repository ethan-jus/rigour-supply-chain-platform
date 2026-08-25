package com.rigour.sales.temporarycheckin;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

/** 使用 JDK PBKDF2-HMAC-SHA256 保存后台密码，不引入额外安全依赖。 */
@Component
final class TemporaryCheckinAdminPasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final String TEMPORARY_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";

    private final SecureRandom random = new SecureRandom();
    private final int iterations;
    private final String dummyHash;

    TemporaryCheckinAdminPasswordHasher(TemporaryCheckinAdminAuthProperties properties) {
        if (properties.getPbkdf2Iterations() < 120_000 || properties.getPbkdf2Iterations() > 2_000_000) {
            throw new IllegalStateException("后台PBKDF2迭代次数必须在120000到2000000之间");
        }
        iterations = properties.getPbkdf2Iterations();
        dummyHash = hash("invalid-account-dummy-password".toCharArray());
    }

    String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, iterations);
        return PREFIX + "$" + iterations + "$" + encode(salt) + "$" + encode(derived);
    }

    boolean matches(char[] password, String encoded) {
        try {
            String[] parts = encoded == null ? new String[0] : encoded.split("\\$", -1);
            if (parts.length != 4 || !PREFIX.equals(parts[0])) return false;
            int encodedIterations = Integer.parseInt(parts[1]);
            if (encodedIterations < 120_000 || encodedIterations > 2_000_000) return false;
            byte[] salt = decode(parts[2]);
            byte[] expected = decode(parts[3]);
            if (salt.length != SALT_BYTES || expected.length != HASH_BITS / 8) return false;
            return MessageDigest.isEqual(expected, derive(password, salt, encodedIterations));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    void consumeDummyHash(char[] password) {
        matches(password, dummyHash);
    }

    String generateTemporaryPassword() {
        StringBuilder value = new StringBuilder(20);
        for (int index = 0; index < 20; index++) {
            value.append(TEMPORARY_ALPHABET.charAt(random.nextInt(TEMPORARY_ALPHABET.length())));
        }
        return value.toString();
    }

    private static byte[] derive(char[] password, byte[] salt, int rounds) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, rounds, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("JDK不支持后台密码摘要算法", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value.getBytes(StandardCharsets.US_ASCII));
    }
}
