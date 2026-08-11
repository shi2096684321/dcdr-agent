package com.dcdr.agent.entity;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;

/**
 * 轻量 Hibernate 类型: 在 Java float[] 与 PostgreSQL pgvector 的 vector 列之间互转。
 * 使用 PgVector 文本格式 (如 '[0.1,0.2,0.3]')，无需第三方 pgvector jar，离线可编译。
 */
public class VectorType implements UserType<float[]> {

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<float[]> returnedClass() {
        return float[].class;
    }

    @Override
    public boolean equals(float[] x, float[] y) {
        return Arrays.equals(x, y);
    }

    @Override
    public int hashCode(float[] x) {
        return Arrays.hashCode(x);
    }

    @Override
    public float[] nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner) throws SQLException {
        String val = rs.getString(position);
        if (val == null) {
            return null;
        }
        String inner = val.trim();
        if (inner.startsWith("[")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("]")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        if (inner.isEmpty()) {
            return new float[0];
        }
        String[] parts = inner.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }

    @Override
    public void nullSafeSet(PreparedStatement st, float[] value, int index, SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            st.setObject(index, toVectorString(value), Types.OTHER);
        }
    }

    /**
     * 将 float[] 序列化为 PgVector 文本格式 (如 {@code [0.1,0.2,0.3]})。
     * <p>
     * 供 Repository 原生 SQL 检索时构造 {@code CAST(:queryVec AS vector)} 参数使用,
     * 与 {@link #nullSafeGet} 反向互转, 保持格式一致。
     *
     * @param value 向量, null 返回 null
     * @return PgVector 文本格式字符串
     */
    public static String toVectorString(float[] value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length + 2);
        sb.append('[');
        for (int i = 0; i < value.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(value[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    @Override
    public float[] deepCopy(float[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(float[] value) {
        return deepCopy(value);
    }

    @Override
    public float[] assemble(Serializable cached, Object owner) {
        return cached == null ? null : Arrays.copyOf((float[]) cached, ((float[]) cached).length);
    }

    @Override
    public float[] replace(float[] detached, float[] managed, Object owner) {
        return deepCopy(detached);
    }
}
