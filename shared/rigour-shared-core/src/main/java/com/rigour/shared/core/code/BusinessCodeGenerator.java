package com.rigour.shared.core.code;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * 统一业务编码生成器。
 *
 * <p>默认格式由 {@link BusinessCodeRule} 控制。唯一性不能只依赖随机数，调用方必须保留数据库唯一索引；
 * 高并发场景可通过 {@link #generateUnique(BusinessCodeRule, Predicate)} 接入 Redis set-if-absent 或数据库预占。</p>
 */
public final class BusinessCodeGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final Clock clock;
    private final IntFunction<String> randomDigits;

    public BusinessCodeGenerator() {
        this(Clock.system(BUSINESS_ZONE), BusinessCodeGenerator::secureRandomDigits);
    }

    public BusinessCodeGenerator(Clock clock, IntFunction<String> randomDigits) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.randomDigits = Objects.requireNonNull(randomDigits, "randomDigits");
    }

    /** 生成候选编码；最终唯一性由数据库唯一索引兜底。 */
    public String generate(BusinessCodeRule rule) {
        Objects.requireNonNull(rule, "rule");
        LocalDateTime now = LocalDateTime.now(clock);
        return rule.prefix() + rule.dateFormatter().format(now) + randomDigits.apply(rule.randomDigits());
    }

    /** 按指定业务时间生成候选编码；用于导入外部历史业务单据。 */
    public String generate(BusinessCodeRule rule, Instant businessTime) {
        Objects.requireNonNull(rule, "rule");
        if (businessTime == null) {
            return generate(rule);
        }
        LocalDateTime time = LocalDateTime.ofInstant(businessTime, BUSINESS_ZONE);
        return rule.prefix() + rule.dateFormatter().format(time) + randomDigits.apply(rule.randomDigits());
    }

    /**
     * 生成并预占唯一编码。
     *
     * @param reserve 返回 true 表示编码已被成功预占；返回 false 表示冲突，需要重试
     */
    public String generateUnique(BusinessCodeRule rule, Predicate<String> reserve) {
        Objects.requireNonNull(reserve, "reserve");
        for (int attempt = 0; attempt < rule.maxAttempts(); attempt++) {
            String candidate = generate(rule);
            if (reserve.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("业务编码生成冲突次数超过上限: prefix=" + rule.prefix());
    }

    /**
     * 按指定业务时间生成并预占唯一编码。
     *
     * @param reserve 返回 true 表示编码已被成功预占；返回 false 表示冲突，需要重试
     */
    public String generateUnique(BusinessCodeRule rule, Instant businessTime, Predicate<String> reserve) {
        Objects.requireNonNull(reserve, "reserve");
        for (int attempt = 0; attempt < rule.maxAttempts(); attempt++) {
            String candidate = generate(rule, businessTime);
            if (reserve.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("业务编码生成冲突次数超过上限: prefix=" + rule.prefix());
    }

    private static String secureRandomDigits(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(SECURE_RANDOM.nextInt(10));
        }
        return builder.toString();
    }
}
