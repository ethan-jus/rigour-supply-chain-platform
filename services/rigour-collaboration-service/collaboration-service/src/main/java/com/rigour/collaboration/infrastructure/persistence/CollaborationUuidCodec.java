package com.rigour.collaboration.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;

/** 协作服务自有 BINARY(16) UUID 转换，避免跨领域复用持久化实现。 */
final class CollaborationUuidCodec {
    private CollaborationUuidCodec() {
    }

    static byte[] encode(UUID value) {
        if (value == null) return null;
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    static UUID decode(byte[] value) {
        if (value == null) return null;
        if (value.length != 16) throw new IllegalArgumentException("UUID BINARY(16)长度无效");
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
