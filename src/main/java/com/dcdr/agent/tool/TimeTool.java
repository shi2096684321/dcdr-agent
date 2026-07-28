package com.dcdr.agent.tool;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 时间日期工具
 */
@Component
public class TimeTool extends AbstractTool {

    @Override
    public String getName() {
        return "time";
    }

    @Override
    public String getDescription() {
        return "时间日期工具, 可以查询当前时间、日期、星期。当用户询问时间相关问题时使用此工具。";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "format": {
                            "type": "string",
                            "description": "时间格式, 可选: 'date'(仅日期), 'time'(仅时间), 'all'(全部, 默认)"
                        }
                    }
                }""";
    }

    @Override
    public String execute(Map<String, Object> params) {
        String format = getString(params, "format", "all");
        LocalDateTime now = LocalDateTime.now();
        return switch (format) {
            case "date" -> "当前日期: " + now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 EEEE"));
            case "time" -> "当前时间: " + now.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            default -> "当前时间: " + now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
        };
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }
}
