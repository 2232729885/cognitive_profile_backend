package com.idata.profile.infra.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

@MappedTypes(UUID[].class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class UuidArrayTypeHandler extends BaseTypeHandler<UUID[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID[] parameter, JdbcType jdbcType)
            throws SQLException {
        Array array = ps.getConnection().createArrayOf("uuid", parameter);
        ps.setArray(i, array);
    }

    @Override
    public UUID[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toUuidArray(rs.getArray(columnName));
    }

    @Override
    public UUID[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toUuidArray(rs.getArray(columnIndex));
    }

    @Override
    public UUID[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toUuidArray(cs.getArray(columnIndex));
    }

    private UUID[] toUuidArray(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Object value = array.getArray();
        if (value instanceof UUID[] uuids) {
            return uuids;
        }
        Object[] objects = (Object[]) value;
        UUID[] uuids = new UUID[objects.length];
        for (int i = 0; i < objects.length; i++) {
            uuids[i] = objects[i] == null ? null : UUID.fromString(objects[i].toString());
        }
        return uuids;
    }
}
