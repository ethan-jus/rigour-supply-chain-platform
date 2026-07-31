package com.rigour.tenant.iam.infrastructure.persistence.typehandler;

import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** UUID与MySQL自然字节序`BINARY(16)`之间的统一转换器，不执行UUID_TO_BIN的v1交换。 */
public final class UuidBinaryTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, UUID value, JdbcType jdbcType)
            throws SQLException {
        statement.setBytes(index, UuidBinaryCodec.encode(value));
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return decode(resultSet.getBytes(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return decode(resultSet.getBytes(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return decode(statement.getBytes(columnIndex));
    }

    private UUID decode(byte[] value) throws SQLException {
        try {
            return UuidBinaryCodec.decode(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException(exception.getMessage(), exception);
        }
    }
}
