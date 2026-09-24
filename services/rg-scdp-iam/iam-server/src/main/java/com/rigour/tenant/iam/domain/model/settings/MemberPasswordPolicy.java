package com.rigour.tenant.iam.domain.model.settings;

import com.rigour.shared.core.exception.RequestValidationException;

import java.util.Set;

/** 新建与重置登录凭证共用的密码规则；不限制已有凭证的登录。 */
public final class MemberPasswordPolicy {
    private static final Set<String> COMMON_WORDS =
            Set.of("password", "admin", "qwerty", "welcome", "letmein");

    private MemberPasswordPolicy() {}

    public static String validate(String value) {
        if (value == null || value.length() < 8 || value.length() > 12)
            throw new RequestValidationException("密码长度需为 8 至 12 位");
        if (!value.matches("[!-~]+")
                || !value.matches(".*[A-Z].*") || !value.matches(".*[a-z].*")
                || !value.matches(".*[0-9].*") || !value.matches(".*[^A-Za-z0-9].*"))
            throw new RequestValidationException("密码须包含大写字母、小写字母、数字和英文符号，不能包含空格");
        String letters = value.replaceAll("[^A-Za-z]", "").toLowerCase(java.util.Locale.ROOT);
        if (COMMON_WORDS.contains(letters))
            throw new RequestValidationException("不能使用常见弱密码，请更换字母和数字组合");
        return value;
    }
}
