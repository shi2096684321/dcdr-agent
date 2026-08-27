package com.dcdr.agent.agent.router;

import com.dcdr.agent.agent.definition.AgentDefinitionService;
import com.dcdr.agent.agent.workflow.WorkflowEngine;
import com.dcdr.agent.agent.governance.CostTracker;
import com.dcdr.agent.config.V3Properties;
import com.dcdr.agent.dto.AgentDtos;
import com.dcdr.agent.dto.ChatRequest;
import com.dcdr.agent.dto.ChatResponse;
import com.dcdr.agent.dto.RoutingDtos;
import com.dcdr.agent.dto.WorkflowDtos;
import com.dcdr.agent.agent.memory.Message;
import com.dcdr.agent.entity.RoutingLogEntity;
import com.dcdr.agent.model.provider.ChatModelProvider;
import com.dcdr.agent.repository.RoutingLogRepository;
import com.dcdr.agent.model.registry.ModelRegistry;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V3.0 智能路由层 (F-30, 文档 §4)。
 * <p>
 * 在对话入口做一次轻量 LLM 调用, 判断用户消息应走工作流 / 单 Agent / 普通对话,
 * 实现对话即触发、用户无感。
 * <p>
 * 路由策略:
 * <ul>
 *   <li>输入: 用户消息 + 当前用户可用的 Agent 列表 + 工作流列表 (名称+描述)</li>
 *   <li>输出: JSON {type, target, confidence, reason}</li>
 *   <li>置信度低于阈值时回退普通对话, 避免误路由</li>
 *   <li>{@code agent.v3.smart-routing.enabled=false} 时直接回退, 退化为 V2.3 行为</li>
 * </ul>
 * <p>
 * <b>线程模型</b>: {@link #route} 同步阻塞调用 LLM, 调用方须在 boundedElastic 线程执行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouter {

    private final AgentDefinitionService agentDefinitionService;
    private final WorkflowEngine workflowEngine;
    private final ModelRegistry modelRegistry;
    private final V3Properties v3Properties;
    private final RoutingLogRepository routingLogRepository;
    private final CostTracker costTracker;
    /** 容忍未知字段: LLM 输出可能携带非预期字段, 双保险配合 RouteResult 上的 @JsonIgnoreProperties */
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 从 LLM 输出中抽取首个完整 JSON 对象 (兼容 ```json 代码块包裹)。括号配平, 避免贪婪正则误截 */
    private static String extractJson(String content) {
        if (content == null) return null;
        int start = content.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        char strQuote = 0;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == strQuote) inStr = false;
            } else {
                if (c == '"' || c == '\'') { inStr = true; strQuote = c; }
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return content.substring(start, i + 1);
                }
            }
        }
        return null; // 括号未配平, 提取失败
    }

    private static final String SYSTEM_PROMPT =
            "你是一个意图路由器。根据用户消息判断应该路由到哪个工作流、哪个 Agent，还是普通对话。\n" +
            "判断原则：\n" +
            "1. 仅当用户意图与某个工作流/Agent 的描述高度匹配时才路由过去；\n" +
            "2. 闲聊、问答、通用写作等无明确匹配场景时返回 chat；\n" +
            "3. confidence 为你对判断的把握程度(0~1的小数)。\n" +
            "严格只输出如下 JSON，不要任何解释或多余文字：\n" +
            "{\"type\":\"workflow|agent|chat\",\"target\":\"目标ID或null\",\"confidence\":0.0,\"reason\":\"简短理由\"}";

    /**
     * 路由结果 JSON Schema (OpenAI strict 模式)。
     * <p>协议层强约束: 字段名/类型/枚举值/必填全保证, 模型无法输出 schema 之外的字段或枚举值。
     * <ul>
     *   <li>{@code type}: 枚举限定 workflow/agent/chat</li>
     *   <li>{@code target}: string 或 null (未命中目标时)</li>
     *   <li>{@code confidence}: 数值, 范围 [0, 1]</li>
     *   <li>{@code reason}: 字符串</li>
     * </ul>
     * strict 模式要求所有字段 required + additionalProperties=false。
     */
    private static final Map<String, Object> ROUTE_RESULT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "type", Map.of("type", "string", "enum", List.of("workflow", "agent", "chat")),
                    "target", Map.of("type", List.of("string", "null")),
                    "confidence", Map.of("type", "number", "minimum", 0, "maximum", 1),
                    "reason", Map.of("type", "string")
            ),
            "required", List.of("type", "target", "confidence", "reason"),
            "additionalProperties", false
    );

    /** 普通对话兜底结果 */
    public RoutingDtos.RouteResult chatFallback() {
        RoutingDtos.RouteResult r = new RoutingDtos.RouteResult();
        r.setType("chat");
        r.setConfidence(BigDecimal.ONE);
        r.setReason("兜底普通对话");
        return r;
    }

    /**
     * 路由判断。
     *
     * @param userMessage 用户消息
     * @param userId      当前用户 ID
     * @param sessionId   会话 ID (用于路由日志, 可空)
     * @return 路由结果; 任何异常都回退到普通对话, 不影响主流程
     */
    public RoutingDtos.RouteResult route(String userMessage, String userId, String sessionId) {
        RoutingDtos.RouteResult fallback = chatFallback();
        if (!v3Properties.getSmartRouting().isEnabled()) {
            return fallback;
        }
        if (userMessage == null || userMessage.isBlank()) {
            return fallback;
        }
        try {
            List<AgentDtos.AgentView> agents = agentDefinitionService.list(userId);
            List<WorkflowDtos.WorkflowView> workflows = workflowEngine.list(userId).stream()
                    .filter(WorkflowDtos.WorkflowView::isEnabled)
                    .toList();
            // 没有任何可路由目标时直接走普通对话, 省一次 LLM 调用
            if (agents.isEmpty() && workflows.isEmpty()) {
                return fallback;
            }
            String prompt = buildPrompt(userMessage, agents, workflows);
            ChatModelProvider provider = resolveProvider();
            ChatRequest req = new ChatRequest();
            req.setMessage(prompt);
            req.setStream(false);
            req.setTemperature(0.1);
            // 结构化输出约束: 强制模型输出合法 JSON (response_format 协议层保证, 默认 json_object)
            String responseFormat = v3Properties.getSmartRouting().getResponseFormat();
            if (responseFormat != null && !responseFormat.isBlank()) {
                req.setResponseFormat(responseFormat);
                // 字段级强约束: 协议层保证字段名/类型/枚举/必填
                if ("json_schema".equals(responseFormat) || "tool_call".equals(responseFormat)) {
                    req.setJsonSchemaName("route_result");
                    req.setJsonSchema(ROUTE_RESULT_SCHEMA);
                    req.setJsonSchemaDescription("提交意图路由判断结果(工作流/Agent/普通对话 三选一)");
                }
            }
            ChatResponse resp = provider.invoke(req, List.of(Message.system(SYSTEM_PROMPT)));
            // V3.0-beta F-34: 记录路由 LLM 调用 token 用量 (source=routing)
            costTracker.record(userId, sessionId, null, "routing", provider.getName(), resp.getTokenUsage());
            RoutingDtos.RouteResult r = parse(resp.getContent(), agents, workflows);
            // 置信度阈值
            double conf = r.getConfidence() == null ? 0 : r.getConfidence().doubleValue();
            if (conf < v3Properties.getSmartRouting().getConfidenceThreshold()) {
                r.setType("chat");
                r.setTarget(null);
                r.setTargetName(null);
                r.setReason("置信度不足(" + conf + "), 回退普通对话");
            }
            saveLog(r, userId, sessionId, userMessage);
            return r;
        } catch (Exception e) {
            log.warn("智能路由失败, 回退普通对话: {}", e.getMessage());
            return fallback;
        }
    }

    private String buildPrompt(String userMessage, List<AgentDtos.AgentView> agents,
                               List<WorkflowDtos.WorkflowView> workflows) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户消息：").append(userMessage).append("\n\n");
        sb.append("可用 Agent 列表：\n");
        if (agents.isEmpty()) {
            sb.append("(无)\n");
        } else {
            for (AgentDtos.AgentView a : agents) {
                sb.append("- ID: ").append(a.getId())
                        .append(" | 名称: ").append(a.getName())
                        .append(" | 描述: ").append(safe(a.getDescription())).append("\n");
            }
        }
        sb.append("\n可用工作流列表：\n");
        if (workflows.isEmpty()) {
            sb.append("(无)\n");
        } else {
            for (WorkflowDtos.WorkflowView w : workflows) {
                sb.append("- ID: ").append(w.getId())
                        .append(" | 名称: ").append(w.getName())
                        .append(" | 模式: ").append(w.getMode())
                        .append(" | 描述: ").append(safe(w.getDescription())).append("\n");
            }
        }
        sb.append("\n请输出路由 JSON。");
        return sb.toString();
    }

    private ChatModelProvider resolveProvider() {
        String model = v3Properties.getSmartRouting().getModel();
        if (model != null && !model.isBlank()) {
            return modelRegistry.getChatProvider(model);
        }
        return modelRegistry.getDefaultChatProvider();
    }

    private RoutingDtos.RouteResult parse(String content, List<AgentDtos.AgentView> agents,
                                          List<WorkflowDtos.WorkflowView> workflows) {
        if (content == null || content.isBlank()) {
            return chatFallback();
        }
        RoutingDtos.RouteResult r;
        try {
            String json = extractJson(content);
            if (json == null) {
                log.warn("路由输出未找到 JSON 对象, 回退普通对话: {}", truncate(content, 200));
                return chatFallback();
            }
            // 优先类型安全绑定; 失败(如 confidence 误输出为字符串)再回退 JsonNode 宽松提取
            try {
                r = mapper.readValue(json, RoutingDtos.RouteResult.class);
            } catch (Exception bindEx) {
                log.warn("readValue 绑定失败, 回退 JsonNode 宽松提取: {}", bindEx.getMessage());
                r = parseLoose(mapper.readTree(json));
            }
            r = normalize(r);
            r = validateTarget(r, agents, workflows);
        } catch (Exception e) {
            log.warn("路由结果解析失败, 回退普通对话: {}", e.getMessage());
            return chatFallback();
        }
        return r;
    }

    /**
     * JsonNode 宽松提取兜底: 兼容 confidence 误输出为字符串、字段缺失等情况。
     */
    private RoutingDtos.RouteResult parseLoose(JsonNode node) {
        RoutingDtos.RouteResult r = new RoutingDtos.RouteResult();
        if (node == null || node.isNull()) return r;
        String type = text(node, "type", "chat").toLowerCase();
        String target = text(node, "target", null);
        double conf = 0.8;
        if (node.has("confidence")) {
            JsonNode cn = node.get("confidence");
            if (cn.isNumber()) conf = cn.asDouble();
            else if (cn.isTextual()) {
                try { conf = Double.parseDouble(cn.asText()); } catch (NumberFormatException ignore) {}
            }
        }
        String reason = text(node, "reason", null);
        r.setType(type);
        r.setTarget("null".equalsIgnoreCase(target) ? null : target);
        r.setConfidence(BigDecimal.valueOf(conf).setScale(2, RoundingMode.HALF_UP));
        r.setReason(reason);
        return r;
    }

    /**
     * 枚举与范围强约束:
     * <ul>
     *   <li>type 仅允许 workflow / agent / chat, 非法值回退 chat</li>
     *   <li>confidence 规范到 [0, 1], 越界钳制到边界</li>
     * </ul>
     */
    private RoutingDtos.RouteResult normalize(RoutingDtos.RouteResult r) {
        if (r == null) return chatFallback();
        String type = r.getType() == null ? "chat" : r.getType().trim().toLowerCase();
        if (!"workflow".equals(type) && !"agent".equals(type) && !"chat".equals(type)) {
            log.warn("路由 type 非法值 [{}], 回退 chat", r.getType());
            type = "chat";
        }
        r.setType(type);
        if (r.getTarget() != null && "null".equalsIgnoreCase(r.getTarget().trim())) {
            r.setTarget(null);
        }
        if (r.getConfidence() == null) {
            r.setConfidence(BigDecimal.ZERO);
        } else {
            double c = r.getConfidence().doubleValue();
            if (c < 0) c = 0;
            if (c > 1) c = 1;
            r.setConfidence(BigDecimal.valueOf(c).setScale(2, RoundingMode.HALF_UP));
        }
        return r;
    }

    /** 校验命中的目标确实存在, 回填名称; 不存在则回退 chat */
    private RoutingDtos.RouteResult validateTarget(RoutingDtos.RouteResult r,
                                                   List<AgentDtos.AgentView> agents,
                                                   List<WorkflowDtos.WorkflowView> workflows) {
        if (r.isChat() || r.getTarget() == null) {
            return r;
        }
        if (r.isWorkflow()) {
            for (WorkflowDtos.WorkflowView w : workflows) {
                if (w.getId().equals(r.getTarget())) {
                    r.setTargetName(w.getName());
                    return r;
                }
            }
        } else if (r.isAgent()) {
            for (AgentDtos.AgentView a : agents) {
                if (a.getId().equals(r.getTarget())) {
                    r.setTargetName(a.getName());
                    return r;
                }
            }
        }
        // 目标不存在, 回退
        r.setType("chat");
        r.setTarget(null);
        r.setTargetName(null);
        r.setReason("路由目标不存在, 回退普通对话");
        return r;
    }

    private void saveLog(RoutingDtos.RouteResult r, String userId, String sessionId, String userMessage) {
        try {
            RoutingLogEntity e = new RoutingLogEntity();
            e.setId("rt_" + UUID.randomUUID().toString().replace("-", ""));
            e.setUserId(userId);
            e.setSessionId(sessionId);
            e.setUserMessage(truncate(userMessage, 2000));
            e.setRouteType(r.getType() == null ? "chat" : r.getType().toLowerCase());
            e.setTargetId(r.getTarget());
            e.setConfidence(r.getConfidence());
            e.setReason(r.getReason());
            e.setCreatedAt(LocalDateTime.now());
            routingLogRepository.save(e);
        } catch (Exception ex) {
            log.warn("路由日志落库失败: {}", ex.getMessage());
        }
    }

    private static String text(JsonNode node, String field, String def) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return def;
        String s = n.asText();
        return s == null || s.isBlank() ? def : s;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
