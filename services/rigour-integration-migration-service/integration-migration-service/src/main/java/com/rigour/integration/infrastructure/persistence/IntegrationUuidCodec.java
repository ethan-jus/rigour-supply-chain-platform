package com.rigour.integration.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;

/** UUID与MySQL BINARY(16)互转；Integration服务不依赖IAM持久层实现。 */
public final class IntegrationUuidCodec {

    private IntegrationUuidCodec() {
    }

    public static byte[] encode(UUID value) {
        if (value == null) return null;
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    public static UUID decode(byte[] bytes) {
        if (bytes == null) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public static UUID decode(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return decode(rs.getBytes(column));
    }
}
