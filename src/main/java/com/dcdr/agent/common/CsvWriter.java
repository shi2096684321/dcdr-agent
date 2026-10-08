package com.dcdr.agent.common;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * V3.1 F-44.2 极简 CSV 构造器 (成本报表导出用)。
 * <p>
 * 不引入 Apache Commons CSV: 需求只有三点, 且第三点是本项目特有的踩坑点 ——
 * <ol>
 *   <li><b>UTF-8 BOM</b>: 不加 BOM 时 Windows Excel 打开中文列头会乱码
 *       (项目既有的 {@code ConversationExportService} 只导 md/json, 未暴露过该问题)</li>
 *   <li><b>公式注入防护</b>: 单元格以 {@code = + - @} 开头会被 Excel 当公式执行;
 *       用户名 / 模型名属于用户数据, 必须加 {@code '} 前缀转义 (OWASP 建议)</li>
 *   <li><b>RFC 4180 转义</b>: 含逗号/引号/换行的字段用双引号包裹, 内部 {@code "} 翻倍</li>
 * </ol>
 */
public final class CsvWriter {

    private static final String LINE_SEP = "\r\n";
    private static final String FORMULA_PREFIX = "=+-@";

    private final StringBuilder sb = new StringBuilder(1024);

    /** 写表头 */
    public CsvWriter header(String... columns) {
        return row((Object[]) columns);
    }

    /** 写一行数据 */
    public CsvWriter row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(cells[i]));
        }
        sb.append(LINE_SEP);
        return this;
    }

    /** 写一行 (List 形式, 便于动态列) */
    public CsvWriter row(List<?> cells) {
        return row(cells.toArray());
    }

    /** 输出为带 BOM 的 UTF-8 字节数组 */
    public byte[] toBytes() {
        return ("\uFEFF" + sb).getBytes(StandardCharsets.UTF_8);
    }

    /** 已写入的行数 (不含表头), 便于调用方做导出上限校验 */
    public int lineCount() {
        return sb.length() == 0 ? 0 : sb.toString().split(LINE_SEP, -1).length - 1;
    }

    private static String escape(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (s.isEmpty()) return "";
        // 公式注入防护: 前置单引号, 让 Excel 按文本处理
        if (FORMULA_PREFIX.indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        // RFC 4180: 含分隔符/引号/换行的字段需引号包裹, 内部的 " 翻倍
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
