package com.rigour.tenant.iam.infrastructure.persistence;

import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** 按RFC 9562位布局生成UUIDv7，数据库继续使用自然字节序。 */
public final class UuidV7IdentifierGenerator implements IdentifierGenerator {

    private static final long TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL;
    private static final long VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL;
    private static final long RFC_4122_VARIANT = 0x8000_0000_0000_0000L;

    private final Clock clock;
    private final SecureRandom secureRandom;

    public UuidV7IdentifierGenerator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    UuidV7IdentifierGenerator(Clock clock, SecureRandom secureRandom) {
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom cannot be null");
    }

    @Override
    public UUID nextId() {
        long unixMilliseconds = clock.millis() & TIMESTAMP_MASK;
        long randomA = secureRandom.nextInt(1 << 12);
        long mostSignificantBits = (unixMilliseconds << 16) | 0x7000L | randomA;
        long leastSignificantBits = (secureRandom.nextLong() & VARIANT_MASK) | RFC_4122_VARIANT;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
