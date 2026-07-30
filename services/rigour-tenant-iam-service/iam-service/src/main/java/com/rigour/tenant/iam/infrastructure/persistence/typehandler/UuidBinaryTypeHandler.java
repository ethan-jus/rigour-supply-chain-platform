package com.rigour.tenant.iam.infrastructure.persistence.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.nio.ByteBuffer;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** UUID与MySQL自然字节序`BINARY(16)`之间的统一转换器，不执行UUID_TO_BIN的v1交换。 */
public final class UuidBinaryTypeHandler extends BaseTypeHandler<UUID> {

    private static final int UUID_BYTES = 16;

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, UUID value, JdbcType jdbcType)
            throws SQLException {
        statement.setBytes(index, toBytes(value));
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return fromBytes(resultSet.getBytes(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return fromBytes(resultSet.getBytes(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return fromBytes(statement.getBytes(columnIndex));
    }

    private byte[] toBytes(UUID value) {
        return ByteBuffer.allocate(UUID_BYTES)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private UUID fromBytes(byte[] value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value.length != UUID_BYTES) {
            throw new SQLException("Expected BINARY(16) UUID but got " + value.length + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
