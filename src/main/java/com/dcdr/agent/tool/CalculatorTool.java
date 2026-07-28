package com.dcdr.agent.tool;

import org.springframework.stereotype.Component;

import javax.script.ScriptEngineManager;
import java.util.Map;

/**
 * 计算器工具 - 支持四则运算
 */
@Component
public class CalculatorTool extends AbstractTool {

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public String getDescription() {
        return "数学计算工具, 可以进行加减乘除等四则运算。当用户需要数学计算时使用此工具。";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "expression": {
                            "type": "string",
                            "description": "数学表达式, 如 '1234 * 5678' 或 '(1+2)*3'"
                        }
                    },
                    "required": ["expression"]
                }""";
    }

    @Override
    public String execute(Map<String, Object> params) {
        String expression = getString(params, "expression");
        if (expression == null || expression.isBlank()) {
            return "请提供计算表达式";
        }
        // 安全校验: 只允许数字和基本运算符
        String sanitized = expression.replaceAll("[^0-9+\\-*/().\\s]", "");
        if (sanitized.isBlank()) {
            return "无效的表达式";
        }
        try {
            var engine = new ScriptEngineManager().getEngineByName("js");
            if (engine == null) {
                // 降级: 简单解析
                return simpleCalculate(sanitized);
            }
            Object result = engine.eval(sanitized);
            return expression + " = " + result;
        } catch (Exception e) {
            return "计算失败: " + e.getMessage();
        }
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    /** 简单降级计算(仅支持两个操作数) */
    private String simpleCalculate(String expr) {
        expr = expr.replaceAll("\\s", "");
        String[] parts;
        if (expr.contains("+")) {
            parts = expr.split("\\+");
            return expr + " = " + (Double.parseDouble(parts[0]) + Double.parseDouble(parts[1]));
        } else if (expr.contains("-")) {
            parts = expr.split("-", 2);
            return expr + " = " + (Double.parseDouble(parts[0]) - Double.parseDouble(parts[1]));
        } else if (expr.contains("*")) {
            parts = expr.split("\\*");
            return expr + " = " + (Double.parseDouble(parts[0]) * Double.parseDouble(parts[1]));
        } else if (expr.contains("/")) {
            parts = expr.split("/");
            return expr + " = " + (Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]));
        }
        return "无法计算: " + expr;
    }
}
