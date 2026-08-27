package com.dcdr.agent.rag.tool;

import com.dcdr.agent.rag.config.RagProperties;
import com.dcdr.agent.rag.dto.RetrievalResult;
import com.dcdr.agent.rag.service.RetrievalService;
import com.dcdr.agent.tool.AbstractTool;
import com.dcdr.agent.tool.ToolInfo;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具 (Agentic RAG)。
 * <p>
 * 混合模式中的 Agentic 分支: LLM 自主决定是否调用此工具检索知识库。
 * 工具只暴露 {@code query} 参数给 LLM(模型只决定"查什么"),
 * 知识库 ID 列表由 {@link KnowledgeSearchContextHolder} 从当前对话上下文注入
 * (用户选择的用户级知识库 + 平台级配置的知识库)。
 * <p>
 * <b>工作流程</b>:
 * <ol>
 *   <li>用户提问 → LLM 判断是否需要查知识库</li>
 *   <li>需要 → 调用此工具, 传入检索关键词(query)</li>
 *   <li>工具从 ThreadLocal 获取可用知识库 ID, 调 RetrievalService 检索</li>
 *   <li>返回 ToolInfo(精简内容给模型 + sources 给前端渲染引用卡片)</li>
 *   <li>不需要 → LLM 直接回答(省一次检索)</li>
 * </ol>
 */
@Slf4j
@Component
public class KnowledgeSearchTool extends AbstractTool {

    private final RetrievalService retrievalService;
    private final RagProperties properties;

    public KnowledgeSearchTool(RetrievalService retrievalService, RagProperties properties) {
        this.retrievalService = retrievalService;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "knowledge_base_search";
    }

    @Override
    public String getDescription() {
        return "知识库检索工具：从已授权的知识库（用户选择的知识库 + 平台级知识库）中，\n" +
                "按语义相似度检索文档片段。\n" +
                "\n" +
                "【何时调用】\n" +
                "- 用户询问已上传文档、内部资料、特定文件中的内容时\n" +
                "- 用户问题包含文档专有名词、人名、产品名、项目代号等\n" +
                "- 用户明确要求\"查一下文档\"、\"从我上传的资料里找时\n" +
                "\n" +
                "【何时不要调用】\n" +
                "- 通用知识问答（如\"什么是光合作用\"）\n" +
                "- 写代码、翻译、润色、闲聊、数学计算\n" +
                "- 用户问题与任何已上传文档都明显无关\n" +
                "\n" +
                "【query 构造建议】\n" +
                "- 传完整自然语言问题，而非关键词堆叠（向量检索基于语义匹配）\n" +
                "- 好例子：\"作者的工作经历有哪些\" → query=\"作者的工作经历\"\n" +
                "- 坏例子：\"作者 简历 工作\" （关键词堆叠，召回率低）\n" +
                "- 复杂问题可拆分多次调用，每次一个聚焦子问题\n" +
                "\n" +
                "【返回内容】\n" +
                "- 命中时：返回若干文档片段（含文件名、片段序号）\n" +
                "- 未命中：返回\"未找到相关内容\"提示，此时应直接基于自身知识回答，\n" +
                "  不要反复重试相同 query";
    }

    @Override
    public JsonObjectSchema getParameters() {
        return JsonObjectSchema.builder()
                .addProperty("query", JsonStringSchema.builder()
                        .description("检索关键词或问题, 用于从知识库中查找相关文档片段").build())
                .required(List.of("query"))
                .build();
    }

    @Override
    public String execute(Map<String, Object> params) {
        // executeWithInfo 已覆盖此方法, 这里返回精简结果作为兜底
        return executeWithInfo(params).getContent();
    }

    @Override
    public ToolInfo executeWithInfo(Map<String, Object> params) {
        String query = getString(params, "query");
        if (query == null || query.isBlank()) {
            log.warn("检索关键词不能为空");
            return ToolInfo.text("检索关键词不能为空");
        }

        // 从 ThreadLocal 获取当前对话可用的知识库 ID 列表
        List<String> kbIds = KnowledgeSearchContextHolder.get();
        if (kbIds.isEmpty()) {
            log.warn("当前没有可用的知识库, 无法检索。");
            return ToolInfo.text("当前没有可用的知识库, 无法检索。");
        }

        try {
            List<RetrievalResult> results = retrievalService.retrieve(kbIds, query, null);
            if (results.isEmpty()) {
                log.warn("未在知识库中找到与「{}」相关的内容。", query);
                return ToolInfo.text("未在知识库中找到与「" + query + "」相关的内容。");
            }

            // 构造给模型的精简文本: 按序号列出检索片段
            StringBuilder content = new StringBuilder();
            content.append("从知识库检索到 ").append(results.size()).append(" 条相关内容:\n\n");
            List<Map<String, Object>> sources = new ArrayList<>(results.size());
            for (int i = 0; i < results.size(); i++) {
                RetrievalResult r = results.get(i);
                String filename = r.getFilename() != null ? r.getFilename() : "未知文件";
                content.append("[[").append(i + 1).append("]] 文件: ").append(filename)
                        .append("（片段 #").append(r.getChunkIndex()).append("）\n")
                        .append(r.getContent() != null ? r.getContent() : "")
                        .append("\n\n");

                // 构造 sources 供前端渲染引用卡片
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("index", i + 1);
                s.put("title", filename);
                s.put("url", "");
                s.put("snippet", clip(r.getContent(), 300));
                s.put("content", r.getContent());
                s.put("score", r.getScore());
                s.put("chunkId", r.getChunkId());
                s.put("source", "knowledge_base");
                sources.add(s);
            }

            log.info("Agentic RAG 检索: kbIds={}, query='{}', 命中 {} 条", kbIds, query, results.size());
            return new ToolInfo(content.toString().strip(), sources);
        } catch (Exception e) {
            log.warn("知识库检索失败: {}", e.getMessage());
            return ToolInfo.text("知识库检索失败: " + e.getMessage());
        }
    }

    @Override
    public boolean supportsStreaming() {
        // 暴露给流式对话(chat 路径), 使 LLM 在对话中可自主调用
        return properties.isEnabled();
    }

    /** 截断文本到指定长度, 超长追加省略号 */
    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
