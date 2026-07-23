package com.dcdr.agent.tool;

import java.util.Map;

/**
 * 工具基类 - 提供参数提取等通用方法
 */
public abstract class AbstractTool implements Tool {

    /** 从参数中提取字符串值 */
    protected String getString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val != null ? val.toString() : null;
    }

    /** 从参数中提取字符串值, 带默认值 */
    protected String getString(Map<String, Object> params, String key, String defaultValue) {
        String val = getString(params, key);
        return val != null ? val : defaultValue;
    }

    /** 从参数中提取数值 */
    protected double getDouble(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) return 0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        return Double.parseDouble(val.toString());
    }
}
