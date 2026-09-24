package com.rigour.tenant.iam.domain.model.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MemberPasswordPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"R7!mK2xp", "R7!mK2xpQ9#v"})
    void acceptsEightAndTwelveCharacterPasswords(String password) {
        assertThat(MemberPasswordPolicy.validate(password)).isEqualTo(password);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"R7!mK2x", "R7!mK2xpQ9#vZ", "abcdefgh", "ABCDEFGH",
            "12345678", "Abcdef12", "abcdef1!", "ABCDEF1!", "Abcdefg!", "Aa12! xy",
            "Aa12!中文xx", "Password1!", "Admin123!", "Qwerty12!"})
    void rejectsLengthComplexityAndCommonWeakPasswords(String password) {
        assertThatThrownBy(() -> MemberPasswordPolicy.validate(password))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
