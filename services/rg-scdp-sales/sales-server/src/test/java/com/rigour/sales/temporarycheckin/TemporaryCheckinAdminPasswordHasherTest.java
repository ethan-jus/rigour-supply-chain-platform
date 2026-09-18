package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TemporaryCheckinAdminPasswordHasherTest {

    @Test
    void hashesWithRandomSaltAndMatchesWithoutStoringPlaintext() {
        TemporaryCheckinAdminPasswordHasher hasher = hasher();
        char[] password = "Strong-temporary-42!".toCharArray();

        String first = hasher.hash(password);
        String second = hasher.hash(password);

        assertThat(first).startsWith("pbkdf2-sha256$120000$").doesNotContain(new String(password));
        assertThat(second).isNotEqualTo(first);
        assertThat(hasher.matches(password, first)).isTrue();
        assertThat(hasher.matches("wrong-password".toCharArray(), first)).isFalse();
        assertThat(hasher.matches(password, "pbkdf2-sha256$1$bad$bad")).isFalse();
    }

    @Test
    void generatesIndependentOneTimePasswords() {
        TemporaryCheckinAdminPasswordHasher hasher = hasher();
        Set<String> values = new HashSet<>();
        for (int index = 0; index < 32; index++) values.add(hasher.generateTemporaryPassword());

        assertThat(values).hasSize(32).allSatisfy(value -> assertThat(value).hasSize(20));
    }

    private static TemporaryCheckinAdminPasswordHasher hasher() {
        TemporaryCheckinAdminAuthProperties properties = new TemporaryCheckinAdminAuthProperties();
        properties.setPbkdf2Iterations(120_000);
        return new TemporaryCheckinAdminPasswordHasher(properties);
    }
}
