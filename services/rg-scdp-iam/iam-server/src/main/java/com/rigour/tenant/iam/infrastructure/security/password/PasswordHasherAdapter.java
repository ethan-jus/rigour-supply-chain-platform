package com.rigour.tenant.iam.infrastructure.security.password;

import com.rigour.tenant.iam.application.port.out.PasswordHasher;
import java.util.Map;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 使用冻结的Argon2id-v1参数实现密码强哈希，并为旧参数保留受控升级入口。 */
public final class PasswordHasherAdapter implements PasswordHasher {

    public static final String ALGORITHM_ID = "argon2id-v1";
    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19_456;
    private static final int ITERATIONS = 2;

    private final PasswordEncoder encoder;
    private final String dummyHash;

    public PasswordHasherAdapter() {
        Argon2PasswordEncoder argon2 = new Argon2PasswordEncoder(
                SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);
        this.encoder = new DelegatingPasswordEncoder(ALGORITHM_ID, Map.of(ALGORITHM_ID, argon2));
        this.dummyHash = encoder.encode("iam-nonexistent-account-timing-value");
    }

    @Override
    public String hash(CharSequence rawPassword) {
        requirePassword(rawPassword);
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        requirePassword(rawPassword);
        return encodedPassword != null && encoder.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean needsUpgrade(String encodedPassword) {
        return encodedPassword == null || encoder.upgradeEncoding(encodedPassword);
    }

    @Override
    public void consumeDummyVerification(CharSequence rawPassword) {
        requirePassword(rawPassword);
        encoder.matches(rawPassword, dummyHash);
    }

    private static void requirePassword(CharSequence password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("password cannot be blank");
        }
    }
}
