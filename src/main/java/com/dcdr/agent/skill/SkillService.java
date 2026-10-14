package com.dcdr.agent.skill;

import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.SkillDtos;
import com.dcdr.agent.entity.SkillDefinitionEntity;
import com.dcdr.agent.repository.SkillRepository;
import com.dcdr.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技能管理 (V3.1 专家体系)。
 * <p>
 * <b>职责边界</b>: 本类只管"技能定义的生命周期"(增删改查/启停/导入导出/按名加载内容),
 * 不负责"把技能注入上下文" —— 那是 {@link SkillCatalogProvider} 与
 * {@code SkillLoadTool} 的事。分开的理由: 注入策略(注入几条、截断多少)是编排层关注点,
 * 与技能本身的存储无关。
 * <p>
 * <b>为什么把校验写得这么严</b>: 技能包是<b>从外部来的文本</b>(市场下载 / 他人传阅 / LLM 生成),
 * 它会直接进入模型上下文。所以这里做三件事:
 * <ol>
 *   <li>大小与数量上限 —— 否则一个技能就能吃掉整个上下文预算;</li>
 *   <li>资源路径白名单 —— 拒绝 {@code ../}、绝对路径、盘符, 以及任何非文本后缀
 *       (从设计上就不接受二进制, 见 {@link #ALLOWED_RESOURCE_EXT});</li>
 *   <li>依赖工具存在性校验 —— 缺失<b>只告警不阻断</b>: 技能的知识部分是独立可用的,
 *       因缺一个工具就拒绝安装, 会让"先装技能、后装插件"这种正常顺序变得不可行。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    /** 技能标识格式: kebab-case, 与 WorkBuddy/Claude Skills 的惯例一致(模型按名字加载) */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");

    /** 指令正文上限(字符)。超限说明这个技能设计得不对 —— 应该拆成技能 + 参考资源 */
    static final int MAX_INSTRUCTIONS_CHARS = 100_000;
    /** 参考资源条目数上限 */
    static final int MAX_RESOURCE_COUNT = 50;
    /** 单个参考资源上限(字符) */
    static final int MAX_RESOURCE_CHARS = 64 * 1024;
    /** 参考资源总量上限(字符) */
    static final int MAX_RESOURCES_TOTAL_CHARS = 512 * 1024;
    /** 单用户技能数上限 */
    static final int MAX_SKILLS_PER_USER = 100;
    /** URL 字段长度上限(与实体的 icon_url / source_url 列宽一致) */
    static final int MAX_URL_LEN = 512;
    /** 来源标识长度上限(与实体的 source_ref 列宽一致) */
    static final int MAX_SOURCE_REF_LEN = 255;

    /**
     * "正文待补全"的判据。
     * <p>
     * <b>"是否待补全"刻意不做成数据库字段, 而由正文内容派生</b> —— 三个理由:
     * <ol>
     *   <li><b>老技能无需迁移</b>: 该状态是随技能市场一起引入的, 加列就得为历史数据写迁移,
     *       而迁移的判断依据同样是这段文字, 绕一圈没有收益;</li>
     *   <li><b>会自愈</b>: 用户一旦按占位说明把正文替换掉, 它自动就不再是"待补全" ——
     *       少一个"标记已补全"的按钮, 也就少一处会忘记点的地方;</li>
     *   <li><b>无法被谎报</b>: 正文本身就是证据, 请求里传什么值都不影响判断。</li>
     * </ol>
     * 代价是判据依赖文案: 它必须与 {@code SkillMarketService} 生成占位正文时用的那句保持一致,
     * 两处要同步改(已有单测钉住)。
     */
    static final String PENDING_SENTINEL = "正文待补全";

    /** 从占位正文回读来源页地址(市场引入时写在正文里的那一行) */
    private static final Pattern SOURCE_LINE =
            Pattern.compile("^\\s*>\\s*来源[:：]\\s*(https?://\\S+)", Pattern.MULTILINE);
    /** 从占位正文回读来源标识(市场引入时写在"## 来源信息 / - 标识: ..."那一行) */
    private static final Pattern ORIGIN_LINE =
            Pattern.compile("^\\s*-\\s*标识[:：]\\s*(\\S+)", Pattern.MULTILINE);
    /** 加载内容返回给模型时的上限(字符), 超出显式截断标注 */
    static final int MAX_LOAD_CHARS = 20_000;

    /**
     * 参考资源允许的文本后缀。
     * <p>
     * 白名单而非黑名单: 技能是纯文本能力包, 本就不该携带任何二进制/脚本。
     * 一旦放开这个口子, "从市场装个技能"就等价于"远程投递任意文件"。
     */
    private static final Set<String> ALLOWED_RESOURCE_EXT = Set.of(
            "md", "txt", "json", "csv", "yaml", "yml", "toml");

    private final SkillRepository repository;
    private final ToolRegistry toolRegistry;
    private final OwnershipGuard ownershipGuard;

    // ===== 查询 =====

    /** 可见技能列表: 自己的 + 公开的(admin 为全部) */
    public List<SkillDtos.SkillView> list(String userId, boolean isAdmin) {
        List<SkillDefinitionEntity> entities = isAdmin
                ? repository.findAllByOrderByUpdatedAtDesc()
                : mergeOwnAndPublic(userId);
        List<SkillDtos.SkillView> views = new ArrayList<>(entities.size());
        for (SkillDefinitionEntity e : entities) {
            views.add(toView(e, userId, isAdmin, false));
        }
        return views;
    }

    /** 详情(归属校验: 本人 / 公开 / admin; 越权与不存在统一 404) */
    public SkillDtos.SkillView get(String id, String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.SKILL, id, userId, isAdmin, false);
        return toView(loadEntity(id), userId, isAdmin, true);
    }

    /** 内部加载(供运行时注入与加载工具使用, 不做可见性校验) */
    public SkillDefinitionEntity loadEntity(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "技能不存在: " + id, "SKILL_NOT_FOUND"));
    }

    // ===== 生命周期 =====

    @Transactional
    public SkillDtos.SkillView create(SkillDtos.SaveSkillRequest req, String userId) {
        String name = requireValidName(req.getName());
        validateInstructions(req.getInstructions());
        Map<String, String> resources = normalizeResources(req.getResources());

        if (repository.findByUserIdAndName(userId, name).isPresent()) {
            throw new BusinessException(409, "技能已存在: " + name, "SKILL_EXISTS");
        }
        if (repository.countByUserId(userId) >= MAX_SKILLS_PER_USER) {
            throw new BusinessException(400, "技能数量已达上限(" + MAX_SKILLS_PER_USER + ")", "SKILL_LIMIT");
        }

        SkillDefinitionEntity e = new SkillDefinitionEntity();
        e.setId("skill_" + UUID.randomUUID().toString().replace("-", ""));
        e.setUserId(userId);
        e.setSource("local");
        e.setCreatedAt(LocalDateTime.now());
        applyRequest(e, req, name, resources);
        repository.save(e);
        log.info("技能创建: {} (user={}, 资源 {} 个, 依赖工具 {})",
                name, userId, resources.size(), SkillJson.readList(e.getRequiredTools()));
        return toView(e, userId, false, true);
    }

    @Transactional
    public SkillDtos.SkillView update(String id, SkillDtos.SaveSkillRequest req, String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.SKILL, id, userId, isAdmin, true);
        SkillDefinitionEntity e = loadEntity(id);
        // 保住血缘的最后一次机会: 正文一被替换, 占位里那行"标识:"就没了(见 derivedSourceRef)。
        // 必须放在 applyRequest 之前 —— 之后再回读, 读到的已经是新正文了
        if (blankToNull(e.getSourceRef()) == null) {
            String derived = derivedSourceRef(e.getInstructions());
            if (derived != null) e.setSourceRef(derived);
        }
        String name = requireValidName(req.getName());
        validateInstructions(req.getInstructions());
        Map<String, String> resources = normalizeResources(req.getResources());

        // 改名要保证新名字在自己名下不冲突(否则 skill_load 会有歧义)
        if (!name.equals(e.getName()) && repository.findByUserIdAndName(e.getUserId(), name).isPresent()) {
            throw new BusinessException(409, "技能已存在: " + name, "SKILL_EXISTS");
        }
        applyRequest(e, req, name, resources);
        repository.save(e);
        return toView(e, userId, isAdmin, true);
    }

    @Transactional
    public void delete(String id, String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.SKILL, id, userId, isAdmin, true);
        SkillDefinitionEntity e = loadEntity(id);
        if ("builtin".equals(e.getSource())) {
            // 内置技能由平台维护: 允许停用(不再进入技能目录), 但不允许删除,
            // 否则一次误操作就会让所有依赖它的对话失去能力, 且重启后被初始化器重建 —— 状态更混乱
            throw new BusinessException(400, "内置技能不可删除, 如需停用请调用启用/停用接口", "BUILTIN_SKILL_IMMUTABLE");
        }
        repository.delete(e);
        log.info("技能删除: {} (user={})", e.getName(), userId);
    }

    /** 启用/停用: 停用后不再进入技能目录(定义保留, 便于排查与恢复) */
    @Transactional
    public SkillDtos.SkillView setEnabled(String id, boolean enabled, String userId, boolean isAdmin) {
        ownershipGuard.check(ResourceType.SKILL, id, userId, isAdmin, true);
        SkillDefinitionEntity e = loadEntity(id);
        e.setEnabled(enabled);
        e.setUpdatedAt(LocalDateTime.now());
        repository.save(e);
        return toView(e, userId, isAdmin, false);
    }

    /**
     * 记录技能来源标识 —— <b>仅供导入 / 市场引入链路调用</b>。
     * <p>
     * <b>为什么单独开一个方法, 而不是塞进 {@code SaveSkillRequest}</b>: 来源是系统记录的<b>事实</b>,
     * 不是用户内容。若走通用保存路径, "手工编辑技能"这个动作会带着一个空的 sourceRef 进来,
     * 把血缘静默清成 null —— 而会去编辑的恰恰就是要补全正文的那个人, 补完血缘就没了。
     * 放在这里, 结构上只有导入链路写得进来。
     * <p>
     * <b>覆盖语义</b>: 重复引入会带上新的来源标识(可能换了命名空间), 以最后一次为准;
     * 传空值表示"不改", 而不是"清空"(清血缘没有任何合理场景)。
     */
    @Transactional
    public void markOrigin(String id, String sourceRef) {
        String ref = blankToNull(sourceRef) == null ? null : sourceRef.trim();
        if (ref == null) return;
        if (ref.length() > MAX_SOURCE_REF_LEN) {
            // 来源是辅助信息: 超长就丢弃并留日志, 不因为这行字拦住一次技能包导入
            // (与 iconUrl / sourceUrl 的"非法值静默丢弃"口径一致)
            log.warn("来源标识过长({} 字符), 已忽略: {}", ref.length(), ref.substring(0, 60));
            return;
        }
        SkillDefinitionEntity e = loadEntity(id);
        e.setSourceRef(ref);
        e.setUpdatedAt(LocalDateTime.now());
        repository.save(e);
        log.info("技能来源登记: id={}, sourceRef={}", id, ref);
    }

    // ===== 运行时(供上下文注入与 skill_load 使用) =====

    /**
     * 某用户当前生效的技能: <b>内置的 + 公开的 + 自己的</b>(都只取已启用), 按名称去重。
     * <p>
     * <b>优先级: 自己的 &gt; 公开的 &gt; 内置的</b>。自己优先的理由: 用户对自己的技能理解最深,
     * 且"我自建了一个同名技能"通常意味着我想覆盖掉平台那个; 反过来会让用户无法修正内置技能的行为。
     * <p>
     * <b>内置技能单独查一遍</b>(而不是靠 {@code isPublic=true} 混进"公开"里):
     * 内置技能的 {@code userId} 是 null, 既不在任何用户的归属里, 也不该被"是否公开"这个
     * <b>用户主动公开</b>的开关控制 —— 用 isPublic 顶替会让"管理员误关公开 → 全员丢失内置技能"
     * 这种事故变得可能, 而且从数据上也看不出它是内置的。
     */
    public List<SkillDefinitionEntity> visibleEnabledSkills(String userId) {
        Map<String, SkillDefinitionEntity> byName = new LinkedHashMap<>();
        for (SkillDefinitionEntity s : repository.findByEnabledTrueAndUserIdIsNullOrderByCreatedAtAsc()) {
            byName.putIfAbsent(s.getName(), s);
        }
        for (SkillDefinitionEntity s : repository.findByEnabledTrueAndIsPublicTrueOrderByUpdatedAtDesc()) {
            byName.putIfAbsent(s.getName(), s);
        }
        if (userId != null) {
            for (SkillDefinitionEntity s : repository.findByEnabledTrueAndUserIdOrderByUpdatedAtDesc(userId)) {
                byName.put(s.getName(), s);
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * 按名加载技能内容 —— {@code skill_load} 工具的落地点。
     *
     * @param name 技能标识
     * @param path 参考资源路径; 为空表示加载技能指令正文
     * @return 供模型阅读的文本(超长已截断并标注); 未找到或不可见时返回可读的提示语
     */
    public String loadContent(String userId, String name, String path) {
        SkillDefinitionEntity skill = visibleEnabledSkills(userId).stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElse(null);
        if (skill == null) {
            return "未找到可用技能: " + name + "。请只使用技能目录中列出的名称; 若确实需要其它技能, 请让用户先在技能管理中启用。";
        }
        if (path == null || path.isBlank()) {
            return clip(skill.getInstructions(), MAX_LOAD_CHARS);
        }
        Map<String, String> resources = SkillJson.readMap(skill.getResourcesJson());
        String content = resources.get(path.trim());
        if (content == null) {
            return "技能 " + name + " 中不存在资源: " + path
                    + "。可用资源: " + (resources.isEmpty() ? "(无)" : String.join(", ", resources.keySet()));
        }
        return clip(content, MAX_LOAD_CHARS);
    }

    /**
     * 取技能指令正文(已按 {@link #MAX_LOAD_CHARS} 截断)。
     * <p>
     * 供"用户手动指定技能"时<b>直接注入正文</b>用 —— 与 {@code skill_load} 走<b>同一个截断口径</b>:
     * 两处若各写一份截断逻辑, 迟早会漂移成"工具加载是 2 万字符、手动注入是 10 万字符",
     * 而后者会静默吃掉上下文预算。
     * <p>
     * 不做可见性校验: 调用方(编排层)必须先用 {@link #visibleEnabledSkills} 解析过归属,
     * 拿到的实体本身就是"可见且已启用"的。
     */
    public String instructionsOf(SkillDefinitionEntity skill) {
        return skill == null ? "" : clip(skill.getInstructions(), MAX_LOAD_CHARS);
    }

    // ===== 内部辅助 =====

    private List<SkillDefinitionEntity> mergeOwnAndPublic(String userId) {
        Map<String, SkillDefinitionEntity> byId = new LinkedHashMap<>();
        // 内置技能也要进列表 —— 否则"管理页看不到、但对话里生效", 用户会以为技能表坏了
        for (SkillDefinitionEntity s : repository.findByUserIdIsNullOrderByCreatedAtAsc()) {
            byId.put(s.getId(), s);
        }
        for (SkillDefinitionEntity s : repository.findByEnabledTrueAndIsPublicTrueOrderByUpdatedAtDesc()) {
            byId.put(s.getId(), s);
        }
        if (userId != null) {
            for (SkillDefinitionEntity s : repository.findByUserIdOrderByUpdatedAtDesc(userId)) {
                byId.put(s.getId(), s);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private void applyRequest(SkillDefinitionEntity e, SkillDtos.SaveSkillRequest req, String name,
                              Map<String, String> resources) {
        e.setName(name);
        e.setDisplayName(blankToNull(req.getDisplayName()) != null ? req.getDisplayName().trim() : name);
        e.setIconUrl(normalizeHttpUrl(req.getIconUrl(), MAX_URL_LEN));
        e.setSourceUrl(normalizeHttpUrl(req.getSourceUrl(), MAX_URL_LEN));
        e.setDescription(blankToNull(req.getDescription()));
        e.setVersion(blankToNull(req.getVersion()));
        e.setAuthor(blankToNull(req.getAuthor()));
        e.setCategoryId(blankToNull(req.getCategoryId()));
        e.setTags(SkillJson.writeList(req.getTags()));
        e.setInstructions(req.getInstructions().strip());
        e.setResourcesJson(SkillJson.writeMap(resources));
        e.setRequiredTools(SkillJson.writeList(req.getRequiredTools()));
        e.setHasExecutable(Boolean.TRUE.equals(req.getHasExecutable()));
        if (req.getIsPublic() != null) e.setPublic(req.getIsPublic());
        e.setUpdatedAt(LocalDateTime.now());
        if (e.getCreatedAt() == null) e.setCreatedAt(e.getUpdatedAt());
    }

    private String requireValidName(String raw) {
        String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            throw new BusinessException(400, "技能标识不能为空");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new BusinessException(400,
                    "技能标识必须是 kebab-case (小写字母/数字/连字符, 3~64 位): " + raw);
        }
        return name;
    }

    private void validateInstructions(String instructions) {
        if (instructions == null || instructions.isBlank()) {
            throw new BusinessException(400, "技能指令正文不能为空 —— 技能的核心就是这段指令");
        }
        if (instructions.length() > MAX_INSTRUCTIONS_CHARS) {
            throw new BusinessException(400, "技能指令过长 (上限 " + MAX_INSTRUCTIONS_CHARS
                    + " 字符); 建议拆分为技能 + 参考资源");
        }
    }

    /** 校验并归一化参考资源: 路径白名单 + 单文件与总量上限 */
    private Map<String, String> normalizeResources(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        if (raw.size() > MAX_RESOURCE_COUNT) {
            throw new BusinessException(400, "参考资源条目过多 (上限 " + MAX_RESOURCE_COUNT + ")");
        }
        Map<String, String> out = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, String> en : raw.entrySet()) {
            String path = normalizeResourcePath(en.getKey());
            String content = en.getValue() == null ? "" : en.getValue();
            if (content.length() > MAX_RESOURCE_CHARS) {
                throw new BusinessException(400, "参考资源过大: " + path + " (上限 " + MAX_RESOURCE_CHARS + " 字符)");
            }
            total += content.length();
            if (total > MAX_RESOURCES_TOTAL_CHARS) {
                throw new BusinessException(400, "参考资源总量过大 (上限 " + MAX_RESOURCES_TOTAL_CHARS + " 字符)");
            }
            out.put(path, content);
        }
        return out;
    }

    /**
     * 资源路径归一化与校验。
     * <p>
     * 拒绝的四类: 绝对路径、盘符、{@code ../} 回溯、非白名单后缀。
     * 前两类防"技能包里的路径指到宿主文件系统"; 后两类防"借技能渠道投递可执行内容"。
     * 注意: 资源只存于 DB 文本字段、从不落磁盘, 这里的校验是纵深防御 ——
     * 将来若新增"资源导出到本地目录"的能力, 这条校验就是唯一防线。
     */
    private String normalizeResourcePath(String rawPath) {
        String p = rawPath == null ? "" : rawPath.trim().replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        if (p.isEmpty()) {
            throw new BusinessException(400, "参考资源路径不能为空");
        }
        if (p.startsWith("/") || p.contains(":") || p.contains("..")) {
            throw new BusinessException(400, "非法的参考资源路径: " + rawPath);
        }
        int dot = p.lastIndexOf('.');
        String ext = dot < 0 ? "" : p.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_RESOURCE_EXT.contains(ext)) {
            throw new BusinessException(400, "参考资源仅支持文本格式 " + ALLOWED_RESOURCE_EXT
                    + ": " + rawPath + " (技能不携带可执行内容, 执行能力请通过 requiredTools 引用工具)");
        }
        return p;
    }

    /** 超长内容截断并显式标注 —— 不静默丢内容 */
    private static String clip(String text, int max) {
        String s = text == null ? "" : text;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n…（内容过长, 已按 " + max + " 字符截断; 如需完整内容请缩小提问范围或拆分技能）";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 归一化"界面用的地址"(图标 / 来源页共用): 只接受 http(s) 或站内相对路径, 其余丢弃为 null。
     * <p>
     * 这<b>不是安全控制</b> —— 真正的安全性来自"界面只把它当 {@code img src} / 链接用",
     * 图标还会在加载失败时回落首字母占位。这里管的是<b>数据卫生</b>:
     * <ul>
     *   <li>拒绝控制字符(只可能来自拼装错误, 且会污染日志与响应);</li>
     *   <li>拒绝 {@code javascript:} 之类非法协议;</li>
     *   <li>拒绝 {@code data:} —— 那是"往文本字段里塞二进制"的开始, 一旦开口,
     *       "技能表是纯文本"这条前提就没了, 而它正是技能包能自由分享的基础。</li>
     * </ul>
     * 非法值<b>静默丢弃而不报错</b>: 这两个都是辅助字段, 为它们拦住整个保存(或拦住一次
     * 技能包导入)不划算; 前端用输入提示来预防, 而不是靠报错来纠正。
     */
    private static String normalizeHttpUrl(String raw, int maxLen) {
        // 注意 blankToNull 不做 trim, 这里必须自己裁: 前后带空格会让 <img src>/<a href> 直接失效
        String s = raw == null ? null : raw.trim();
        if (s == null || s.isEmpty()) return null;
        if (s.length() > maxLen) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return null;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://") && !s.startsWith("/")) {
            return null;
        }
        return s;
    }

    /**
     * 来源页地址: 优先取字段; 字段为空时, 从占位正文里的"来源:"行回读。
     * <p>
     * 为什么要这层兜底: {@code source_url} 列是"补全入口"这个需求才加的, 之前引入的技能
     * 该列全为 null —— 它们的来源地址只写在正文里。兜底让这些老技能<b>无需数据迁移</b>就立刻
     * 拥有"去来源页"入口。纯读取, 不回写数据库。
     */
    private static String sourceUrlOf(SkillDefinitionEntity e) {
        String url = blankToNull(e.getSourceUrl());
        if (url != null) return url;
        String body = e.getInstructions();
        if (!isInstructionsPending(body)) return null;
        Matcher m = SOURCE_LINE.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 从占位正文回读来源标识 —— 只为<b>存量数据</b>兜底。
     * <p>
     * {@code source_ref} 是后加的列, 在此之前引入的技能只有正文里那行"标识:"。
     * 与 {@link #sourceUrlOf} 完全同一套思路(那列当年也是这么过渡的)。
     * <p>
     * <b>但它只是过渡手段, 不能当长期数据来源</b>: 用户补全正文时那行会被整段替换掉,
     * 所以 {@link #update} 里会在覆盖正文<b>之前</b>把回读到的值落库 —— 编辑技能几乎总是
     * 等于"补全正文", 那是唯一的时机窗口。
     */
    private static String derivedSourceRef(String instructions) {
        if (!isInstructionsPending(instructions)) return null;
        Matcher m = ORIGIN_LINE.matcher(instructions);
        if (!m.find()) return null;
        String raw = m.group(1).trim();
        // 占位里写的是上游的 canonicalName(形如 @ns/slug), 补上与字段一致的来源前缀;
        // 已经在正文里带了前缀的就不重复加
        return raw.startsWith("skillhub:") ? raw : "skillhub:" + raw;
    }

    /** 视图用的来源标识: 字段优先, 为空时从占位正文回读(存量数据) */
    private static String sourceRefOf(SkillDefinitionEntity e) {
        String ref = blankToNull(e.getSourceRef());
        return ref != null ? ref : derivedSourceRef(e.getInstructions());
    }

    /** 正文是否仍是占位骨架(取舍见 {@link #PENDING_SENTINEL}) */
    static boolean isInstructionsPending(String instructions) {
        return instructions != null && instructions.contains(PENDING_SENTINEL);
    }

    /**
     * 实体 → 视图。
     *
     * @param withDetail true=详情(返回 instructions 与资源内容); false=列表(仅返回资源路径清单)
     */
    SkillDtos.SkillView toView(SkillDefinitionEntity e, String currentUserId, boolean isAdmin, boolean withDetail) {
        SkillDtos.SkillView v = new SkillDtos.SkillView();
        v.setId(e.getId());
        v.setUserId(e.getUserId());
        v.setName(e.getName());
        v.setDisplayName(e.getDisplayName());
        v.setIconUrl(e.getIconUrl());
        v.setSourceUrl(sourceUrlOf(e));
        v.setSourceRef(sourceRefOf(e));
        v.setInstructionsPending(isInstructionsPending(e.getInstructions()));
        v.setDescription(e.getDescription());
        v.setVersion(e.getVersion());
        v.setAuthor(e.getAuthor());
        v.setCategoryId(e.getCategoryId());
        v.setTags(SkillJson.readList(e.getTags()));
        List<String> required = SkillJson.readList(e.getRequiredTools());
        v.setRequiredTools(required);
        v.setMissingTools(missingTools(required));
        v.setHasExecutable(e.isHasExecutable());
        v.setSource(e.getSource());
        v.setPublic(e.isPublic());
        v.setEnabled(e.isEnabled());
        Map<String, String> resources = SkillJson.readMap(e.getResourcesJson());
        v.setResourcePaths(new ArrayList<>(resources.keySet()));
        if (withDetail) {
            v.setInstructions(e.getInstructions());
            v.setResources(resources);
        }
        v.setCreatedAt(e.getCreatedAt());
        v.setUpdatedAt(e.getUpdatedAt());
        // 可编辑: admin 任意; 本人创建的(内置技能 userId 为 null, 故普通用户不可编辑)
        v.setEditable(isAdmin || (currentUserId != null && currentUserId.equals(e.getUserId())));
        return v;
    }

    /** 依赖工具中当前未注册的部分 —— 用于界面提示"能力缺失", 而不是让用户运行时才发现 */
    List<String> missingTools(List<String> requiredTools) {
        if (requiredTools == null || requiredTools.isEmpty()) return List.of();
        List<String> missing = new ArrayList<>();
        for (String t : requiredTools) {
            if (toolRegistry.getTool(t) == null) missing.add(t);
        }
        return missing;
    }
}
