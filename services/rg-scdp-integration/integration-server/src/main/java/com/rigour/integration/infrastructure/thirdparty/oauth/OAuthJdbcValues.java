package com.rigour.integration.infrastructure.thirdparty.oauth;

import java.nio.ByteBuffer;
import java.sql.*;
import java.time.*;
import java.util.UUID;

final class OAuthJdbcValues {
    private OAuthJdbcValues() {}

    static byte[] bytes(UUID value) {
        return value == null
                ? null
                : ByteBuffer.allocate(16)
                        .putLong(value.getMostSignificantBits())
                        .putLong(value.getLeastSignificantBits())
                        .array();
    }

    static UUID uuid(ResultSet row, String column) throws SQLException {
        byte[] b = row.getBytes(column);
        if (b == null) return null;
        var buffer = ByteBuffer.wrap(b);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    static UUID lease(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    static String text(UUID value) {
        return value == null ? null : value.toString();
    }

    static IllegalStateException conflict() {
        return new IllegalStateException("授权状态已变化，请刷新后重试");
    }
}
