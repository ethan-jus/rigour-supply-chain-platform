package com.rigour.shared.core.code;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 业务编码生成规则。
 *
 * <p>该类型只描述通用编码算法，不承载 ERP/CRM/Order 的领域前缀；领域前缀应定义在各自服务内。</p>
 */
public record BusinessCodeRule(
        String prefix,
        DateTimeFormatter dateFormatter,
        int randomDigits,
        Duration reserveWindow,
        int maxAttempts) {

    private static final int DEFAULT_MAX_ATTEMPTS = 1_000;

    public BusinessCodeRule {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("编码前缀不能为空");
        }
        prefix = prefix.strip().toUpperCase(Locale.ROOT);
        if (!prefix.matches("[A-Z0-9]{2,8}")) {
            throw new IllegalArgumentException("编码前缀只能包含2到8位大写字母或数字");
        }
        if (dateFormatter == null) {
            throw new IllegalArgumentException("日期格式不能为空");
        }
        if (randomDigits < 1 || randomDigits > 12) {
            throw new IllegalArgumentException("随机数字长度必须在1到12之间");
        }
        if (reserveWindow == null || reserveWindow.isNegative() || reserveWindow.isZero()) {
            reserveWindow = Duration.ofDays(1);
        }
        if (maxAttempts < 1) {
            maxAttempts = DEFAULT_MAX_ATTEMPTS;
        }
    }

    /** 日维度编码：前缀 + yyyyMMdd + 随机数字。 */
    public static BusinessCodeRule daily(String prefix, int randomDigits) {
        return new BusinessCodeRule(prefix, DateTimeFormatter.BASIC_ISO_DATE,
                randomDigits, Duration.ofDays(1), DEFAULT_MAX_ATTEMPTS);
    }

    /** 毫秒维度编码：前缀 + yyyyMMddHHmmssSSS + 随机数字。 */
    public static BusinessCodeRule millisecond(String prefix, int randomDigits) {
        return new BusinessCodeRule(prefix, DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"),
                randomDigits, Duration.ofMinutes(1), DEFAULT_MAX_ATTEMPTS);
    }
}
