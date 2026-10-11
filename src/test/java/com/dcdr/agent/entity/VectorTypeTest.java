package com.dcdr.agent.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link VectorType} 单元测试(V3.2)。
 *
 * <p>纯单元测试: 解析路径用 Mockito 桩 {@link ResultSet}, 不需要真实数据库
 * (真实读写链路已由集成测试 {@code RagVectorStoreIT} 覆盖, 二者互补:
 * 这里覆盖格式解析的各种边界, 集成测试覆盖"与真实 pgvector 列对接")。
 *
 * <p><b>为什么值得测</b>: 它是 {@code document_chunks.embedding(1536)} 与 Java {@code float[]}
 * 之间唯一的桥梁, 且<b>写入与读取用的是两段独立代码</b>({@code toVectorString} 拼接 vs
 * {@code nullSafeGet} 解析)。两段代码一旦格式不一致, 表现是"向量能存进去但搜不出来"——
 * 静默、且极难排查。因此本测试的重点是<b>往返一致性</b>。
 */
class VectorTypeTest {

    private final VectorType type = new VectorType();

    /** 用桩 ResultSet 走一遍 nullSafeGet 的解析路径 */
    private static float[] parse(String pgVectorText) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(pgVectorText);
        return new VectorType().nullSafeGet(rs, 1, null, null);
    }

    // ==================================================================
    //  序列化: float[] → PgVector 文本
    // ==================================================================

    @Nested
    @DisplayName("toVectorString 序列化")
    class ToVectorString {

        @Test
        @DisplayName("输出 PgVector 文本格式 [a,b,c]")
        void producesPgVectorText() {
            assertEquals("[1.0,2.5]", VectorType.toVectorString(new float[]{1.0f, 2.5f}));
        }

        @Test
        @DisplayName("null 返回 null(与 SQL NULL 对应)")
        void nullReturnsNull() {
            assertNull(VectorType.toVectorString(null));
        }

        @Test
        @DisplayName("空数组输出 []")
        void emptyArray() {
            assertEquals("[]", VectorType.toVectorString(new float[0]));
        }
    }

    // ==================================================================
    //  反序列化: PgVector 文本 → float[]
    // ==================================================================

    @Nested
    @DisplayName("nullSafeGet 反序列化")
    class NullSafeGet {

        @Test
        @DisplayName("解析标准格式")
        void parsesStandardFormat() throws Exception {
            assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, parse("[0.1,0.2,0.3]"), 1e-6f);
        }

        @Test
        @DisplayName("容忍元素间的空白(pgvector 输出带空格时也能解析)")
        void toleratesWhitespace() throws Exception {
            assertArrayEquals(new float[]{0.1f, 0.2f}, parse("[0.1, 0.2]"), 1e-6f);
        }

        @Test
        @DisplayName("无方括号的裸串同样可解析(方括号剥离是条件判断)")
        void parsesWithoutBrackets() throws Exception {
            assertArrayEquals(new float[]{0.1f, 0.2f}, parse("0.1,0.2"), 1e-6f);
        }

        @Test
        @DisplayName("[] 解析为空数组")
        void parsesEmptyArray() throws Exception {
            assertArrayEquals(new float[0], parse("[]"));
        }

        @Test
        @DisplayName("SQL NULL 返回 null(而非空数组)")
        void nullColumnReturnsNull() throws Exception {
            assertNull(parse(null));
        }

        @Test
        @DisplayName("科学计数法可解析")
        void parsesScientificNotation() throws Exception {
            assertArrayEquals(new float[]{1e-6f, 2.5E3f}, parse("[1.0E-6,2.5E3]"), 1e-6f);
        }
    }

    // ==================================================================
    //  往返一致性(核心)
    // ==================================================================

    @Nested
    @DisplayName("往返一致性")
    class RoundTrip {

        @Test
        @DisplayName("toVectorString → nullSafeGet 往返不丢精度(写入与读取必须格式对称)")
        void roundTripPreservesValues() throws Exception {
            float[] original = {1.5f, -2.25f, 0f, 1e-6f, 12345.678f};

            float[] restored = parse(VectorType.toVectorString(original));

            assertArrayEquals(original, restored, 1e-6f,
                    "序列化与反序列化必须对称 —— 一旦失配, 向量会'能存但搜不出'");
        }

        @Test
        @DisplayName("1536 维真实规模往返一致")
        void roundTripAtProductionDimension() throws Exception {
            float[] original = new float[1536];
            for (int i = 0; i < original.length; i++) {
                original[i] = (i % 10) / 10.0f;
            }

            assertArrayEquals(original, parse(VectorType.toVectorString(original)), 1e-6f);
        }
    }

    // ==================================================================
    //  Hibernate 契约
    // ==================================================================

    @Nested
    @DisplayName("Hibernate UserType 契约")
    class UserTypeContract {

        @Test
        @DisplayName("equals / hashCode 按内容比较")
        void equalityByContent() {
            assertTrue(type.equals(new float[]{1f, 2f}, new float[]{1f, 2f}));
            assertFalse(type.equals(new float[]{1f, 2f}, new float[]{1f, 3f}));
            assertTrue(type.equals(null, null), "两个 null 应相等");
            assertFalse(type.equals(new float[]{1f}, null));

            assertEquals(type.hashCode(new float[]{1f, 2f}), type.hashCode(new float[]{1f, 2f}));
        }

        @Test
        @DisplayName("deepCopy 返回独立副本(修改副本不影响原数组)")
        void deepCopyIsIndependent() {
            float[] source = {1f, 2f};

            float[] copy = type.deepCopy(source);
            copy[0] = 99f;

            assertArrayEquals(new float[]{1f, 2f}, source, 0f, "应返回新数组而非同一引用");
            assertNull(type.deepCopy(null));
        }

        @Test
        @DisplayName("声明为可变类型且 SQL 类型为 OTHER(vector 非标准 JDBC 类型)")
        void declaresTypeMetadata() {
            assertTrue(type.isMutable());
            assertEquals(Types.OTHER, type.getSqlType());
            assertEquals(float[].class, type.returnedClass());
        }
    }
}
