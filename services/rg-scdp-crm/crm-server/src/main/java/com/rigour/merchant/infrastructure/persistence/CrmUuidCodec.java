package com.rigour.merchant.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** CRM UUIDv7 生成及 MySQL BINARY(16) 编解码。 */
public final class CrmUuidCodec {
    private CrmUuidCodec() {
    }

    public static UUID next() {
        long timestamp = System.currentTimeMillis() & 0x0000FFFFFFFFFFFFL;
        long most = (timestamp << 16) | 0x7000L | ThreadLocalRandom.current().nextLong(0x1000L);
        long least = (ThreadLocalRandom.current().nextLong() & 0x3FFFFFFFFFFFFFFFL)
                | 0x8000000000000000L;
        return new UUID(most, least);
    }

    public static byte[] encode(UUID value) {
        if (value == null) return null;
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    public static UUID decode(byte[] value) {
        if (value == null) return null;
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
