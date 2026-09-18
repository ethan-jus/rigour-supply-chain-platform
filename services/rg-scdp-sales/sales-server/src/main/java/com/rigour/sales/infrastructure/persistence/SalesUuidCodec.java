package com.rigour.sales.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;

/** Sales Work 自有 BINARY(16) UUID 转换；不依赖其他领域服务实现。 */
public final class SalesUuidCodec {

    private SalesUuidCodec() {
    }

    public static byte[] encode(UUID value) {
        if (value == null) return null;
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    public static UUID decode(byte[] value) {
        if (value == null) return null;
        if (value.length != 16) throw new IllegalArgumentException("UUID BINARY(16)长度无效");
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
