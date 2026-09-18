package com.rigour.tenant.iam.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;

/** UUID与MySQL自然字节序{@code BINARY(16)}之间的无状态转换器，不执行UUID_TO_BIN的v1交换。 */
public final class UuidBinaryCodec {

    private static final int UUID_BYTES = 16;

    private UuidBinaryCodec() {
    }

    public static byte[] encode(UUID value) {
        if (value == null) {
            return null;
        }
        return ByteBuffer.allocate(UUID_BYTES)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    public static UUID decode(byte[] value) {
        if (value == null) {
            return null;
        }
        if (value.length != UUID_BYTES) {
            throw new IllegalArgumentException("Expected BINARY(16) UUID but got " + value.length + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
