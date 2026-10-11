package com.dcdr.agent.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CsvWriter} 单元测试(V3.2)。
 *
 * <p>纯单元测试: 无可变外部状态、零依赖, 不启 Spring 上下文。
 *
 * <p><b>为什么值得测</b>: 这个类只有 60 行, 但它承载了三个"错了不会报错、只会静默出错"的点:
 * <ul>
 *   <li><b>UTF-8 BOM</b> —— 少了它, Windows Excel 打开中文列头是乱码(用户看得见, 但排查起来绕);</li>
 *   <li><b>公式注入防护</b> —— 用户可控数据(用户名/模型名)以 {@code = + - @} 开头会被 Excel 当公式执行,
 *       属安全范畴(OWASP 明确建议转义);</li>
 *   <li><b>RFC 4180 转义</b> —— 漏了引号/逗号处理, 导出的 CSV 结构会错位, 且同样不报错。</li>
 * </ul>
 */
class CsvWriterTest {

    /** 去掉 BOM 后的纯文本, 便于断言正文 */
    private static String text(CsvWriter writer) {
        String s = new String(writer.toBytes(), StandardCharsets.UTF_8);
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    /** 构造只含一行的 CSV, 去掉结尾换行, 便于直接比对单元格内容 */
    private static String singleRow(Object... cells) {
        return text(new CsvWriter().row(cells)).replace("\r\n", "");
    }

    // ==================================================================
    //  BOM 与行结构
    // ==================================================================

    @Nested
    @DisplayName("BOM 与行结构")
    class BomAndLines {

        @Test
        @DisplayName("输出以 UTF-8 BOM 开头(否则 Excel 打开中文列头乱码)")
        void startsWithUtf8Bom() {
            byte[] bytes = new CsvWriter().header("列1").toBytes();

            assertEquals((byte) 0xEF, bytes[0]);
            assertEquals((byte) 0xBB, bytes[1]);
            assertEquals((byte) 0xBF, bytes[2]);
        }

        @Test
        @DisplayName("以 CRLF 作为行分隔符(RFC 4180)")
        void usesCrlfLineSeparator() {
            assertEquals("a,b\r\n1,2\r\n", text(new CsvWriter().header("a", "b").row(1, 2)));
        }

        @Test
        @DisplayName("null 单元格输出为空串, 保持列位置不偏移")
        void nullCellBecomesEmpty() {
            assertEquals(",", singleRow(null, null));
        }

        @Test
        @DisplayName("非字符串值按 String.valueOf 输出")
        void nonStringValues() {
            assertEquals("42,true", singleRow(42, true));
        }

        @Test
        @DisplayName("row(List) 与 row(Object...) 结果一致")
        void rowFromListEqualsRowFromVarargs() {
            String fromVarargs = text(new CsvWriter().row("a", "b"));
            String fromList = text(new CsvWriter().row(List.of("a", "b")));

            assertEquals(fromVarargs, fromList);
        }
    }

    // ==================================================================
    //  公式注入防护(安全)
    // ==================================================================

    @Nested
    @DisplayName("公式注入防护")
    class FormulaInjection {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"=1+1", "+1", "-1", "@SUM(A1)"})
        @DisplayName("以 = + - @ 开头的内容加单引号前缀, 避免被 Excel 当公式执行")
        void escapesFormulaPrefix(String payload) {
            assertEquals("'" + payload, singleRow(payload));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {"hello", "1+1", "a=b", "中文内容", "  =x"})
        @DisplayName("不以危险字符开头的内容保持原样(不误加前缀)")
        void keepsNormalText(String cell) {
            assertEquals(cell, singleRow(cell));
        }
    }

    // ==================================================================
    //  RFC 4180 转义
    // ==================================================================

    @Nested
    @DisplayName("RFC 4180 转义")
    class Rfc4180 {

        @Test
        @DisplayName("含逗号的字段用双引号包裹")
        void quotesFieldWithComma() {
            assertEquals("\"a,b\"", singleRow("a,b"));
        }

        @Test
        @DisplayName("含双引号的字段: 包裹并把内部引号翻倍")
        void doublesInnerQuote() {
            assertEquals("\"a\"\"b\"", singleRow("a\"b"));
        }

        @Test
        @DisplayName("含换行的字段用双引号包裹")
        void quotesFieldWithNewline() {
            assertEquals("\"a\nb\"", singleRow("a\nb"));
        }

        @Test
        @DisplayName("公式前缀与引号包裹叠加时顺序正确(先加前缀, 再包裹)")
        void formulaPrefixCombinedWithQuoting() {
            assertEquals("\"'=a,b\"", singleRow("=a,b"));
        }
    }

    // ==================================================================
    //  lineCount
    // ==================================================================

    @Nested
    @DisplayName("lineCount")
    class LineCount {

        @Test
        @DisplayName("未写入任何内容时为 0")
        void zeroWhenNothingWritten() {
            assertEquals(0, new CsvWriter().lineCount());
        }

        @Test
        @DisplayName("header 不计入行数(行数仅指数据行)")
        void headerIsNotCounted() {
            // 修正前: header(...) 复用 row(...), 表头被一并计入 → 此处返回 1,
            // 与字段注释「已写入的行数(不含表头)」相反(多算 1 行)。
            // 现已改为显式计数, 只有 row(...) 累加。
            CsvWriter writer = new CsvWriter().header("a", "b");
            assertEquals(0, writer.lineCount());
        }

        @Test
        @DisplayName("只累计数据行(表头不参与)")
        void countsDataRowsOnly() {
            CsvWriter writer = new CsvWriter().header("a").row(1).row(2);
            assertEquals(2, writer.lineCount());
        }
    }
}
