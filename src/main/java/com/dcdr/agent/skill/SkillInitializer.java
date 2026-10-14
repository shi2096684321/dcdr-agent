package com.dcdr.agent.skill;

import com.dcdr.agent.entity.SkillDefinitionEntity;
import com.dcdr.agent.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内置技能初始化器 (V3.1 专家体系)。
 * <p>
 * <b>为什么需要它, 而不是依赖技能市场</b>: 实测过市场那条链路后结论很清楚 ——
 * 上游公开接口<b>不发放技能正文</b>（约 70% 的技能连来源线索都没有）, 所以"从市场引入一个能用的技能"
 * 这条路本身就不成立。而技能体系要产生价值, 必须有<b>开箱就能用的内容</b>。
 * <p>
 * 因此内置技能的选择标准只有一条: <b>纯知识型</b> —— 只有流程/判据/输出格式, 不依赖任何工具、
 * 不需要执行能力。这让它们<b>不受两个硬约束影响</b>: ①不依赖上游给正文; ②不依赖本项目是否装脚本。
 * <p>
 * <b>初始化语义</b>: 逐条按名称判断, <b>只补缺失的, 不做覆盖</b>。与
 * {@code AgentTemplateInitializer} 的"已存在就整体跳过"不同, 原因有二:
 * <ol>
 *   <li>后续新增内置技能时, 老库也能拿到 —— "整体跳过"会让新技能永远进不来;</li>
 *   <li>内置技能允许管理员编辑(普通用户不可编辑), 一旦覆盖就会把管理员的修改冲掉。</li>
 * </ol>
 * 与模板初始化器一致的地方: {@code saveAndFlush} + 吞掉唯一约束冲突, 使多实例同时首启时
 * "一个成功、另一个静默跳过", 而不是让启动失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillInitializer implements ApplicationRunner {

    private final SkillRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        Set<String> existing = new HashSet<>();
        for (SkillDefinitionEntity s : repository.findByUserIdIsNullOrderByCreatedAtAsc()) {
            existing.add(s.getName());
        }

        LocalDateTime now = LocalDateTime.now();
        List<SkillDefinitionEntity> presets = buildPresets(now);
        int inserted = 0;
        for (SkillDefinitionEntity s : presets) {
            if (existing.contains(s.getName())) continue;
            try {
                // saveAndFlush: 让唯一约束冲突在本行抛出并被吞掉(而不是拖到事务提交时,
                // 那时异常已脱离 try 块, 兜不住)
                repository.saveAndFlush(s);
                inserted++;
            } catch (DataIntegrityViolationException e) {
                log.debug("内置技能 {} 已由其他实例写入, 跳过", s.getName());
            }
        }
        if (inserted > 0) {
            log.info("内置技能初始化完成: 本次插入 {}/{} 个", inserted, presets.size());
        } else {
            log.debug("内置技能已就绪({} 个), 本次无需插入", presets.size());
        }
    }

    private List<SkillDefinitionEntity> buildPresets(LocalDateTime now) {
        return List.of(weeklyReport(now), meetingNotes(now), codeReview(now), requirementSpec(now));
    }

    // ===== 1. 周报撰写 =====

    private static SkillDefinitionEntity weeklyReport(LocalDateTime now) {
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("references/template.md", WEEKLY_TEMPLATE);
        return build("skill_builtin_weekly-report", "weekly-report", "周报撰写",
                "当用户要写周报、工作汇报、项目进展汇报, 或需要把零散的工作记录整理成结构化周报时使用。",
                WEEKLY_INSTRUCTIONS, refs, "office-efficiency", List.of("周报", "汇报", "总结"), now);
    }

    private static final String WEEKLY_INSTRUCTIONS = """
            # 周报撰写

            ## 目标
            把用户零散的工作记录整理成一份**能被上级快速扫读**的周报。
            读者是没有参与你日常工作的人 —— 所以它要回答的是"干了什么、有没有卡住、下一步做什么",
            而不是把工作日志按时间铺一遍。

            ## 输出结构
            严格按以下四段输出, 段名固定:

            ### 一、本周完成
            - 每条格式: **做了什么 —— 产生了什么可验证的结果**(数字/交付物/上线/通过评审)
            - 只写已完成的事; 进行中的归到「下周计划」
            - 按重要性降序, **最多 6 条** —— 超过说明没分清主次

            ### 二、下周计划
            - 每条格式: **要做什么 —— 预期产出**
            - **最多 4 条**, 且必须是下周确实能推进的, 不写长期愿景

            ### 三、风险与阻塞
            - 每条格式: **问题 —— 影响 —— 需要谁/什么支持**
            - 没有就明确写「无」。不要为了凑满结构编造风险

            ### 四、需要协作或决策的事项
            - 只列需要别人拍板或配合的: 需要谁 + 需要什么决定 + 期望时间
            - 没有就写「无」

            ## 写作要求
            1. **用结果说话, 不用过程说话**。反例「参与了 X 项目讨论」; 正例「完成 X 方案评审, 确定走 A 方案」
            2. **避免模糊词**: 大幅、显著、基本、差不多 —— 换成具体数字或事实
            3. **用户没给的数字不要编**。缺数据时保留占位, 如「(待补: 上线时间)」
            4. **材料明显不足时先追问** 2~3 个关键问题再写(例如"这周有哪些事有明确产出?"),
               不要用空话把结构填满 —— 一份全是"推进中"的周报等于没写

            ## 参考
            需要空白模板时, 加载本技能的 references/template.md。
            """;

    private static final String WEEKLY_TEMPLATE = """
            # 周报模板(填空用)

            ## 一、本周完成
            1. ____ —— ____(可验证结果)
            2. ____ —— ____
            3. ____ —— ____

            ## 二、下周计划
            1. ____ —— 预期产出: ____
            2. ____ —— 预期产出: ____

            ## 三、风险与阻塞
            - ____ —— 影响: ____ —— 需要: ____
            (无则写「无」)

            ## 四、需要协作或决策的事项
            - 需要 ____ 决定: ____ (期望时间: ____)
            (无则写「无」)
            """;

    // ===== 2. 会议纪要整理 =====

    private static SkillDefinitionEntity meetingNotes(LocalDateTime now) {
        return build("skill_builtin_meeting-notes", "meeting-notes", "会议纪要整理",
                "当用户要把会议记录、录音转写或讨论摘录整理成会议纪要, 或需要从讨论中提炼结论、待办与未决问题时使用。",
                MEETING_INSTRUCTIONS, Map.of(), "office-efficiency", List.of("会议", "纪要", "待办"), now);
    }

    private static final String MEETING_INSTRUCTIONS = """
            # 会议纪要整理

            ## 目标
            产出一份**能直接转发给没参会的人**的纪要。判断标准: 读完它就应该知道
            "定了什么、谁要做什么、什么时候交、还有什么没定"。

            ## 输出结构

            ### 一句话结论
            用一句话说清这次会议的目的、以及是否达成 / 产出了什么。

            ### 已定事项
            - 每条: **结论 —— 依据或理由(一句话)**
            - 只写真正定了的。会上"讨论过但没结论"的不要放这里

            ### 待办
            用表格三列: 事项 | 负责人 | 时限
            - 负责人与时限**必须来自原文**; 原文没写就填「未指定」
            - **不要替人安排**: 原文说"我们"就写「待确认」, 不要默认派给某个人

            ### 未决问题
            - 每条: **问题 —— 卡在哪 —— 下一步由谁推动**
            - 这一节最容易被省略, 但它恰恰是"复述讨论"和"纪要"的分界线 —— 必须保留

            ## 写作要求
            1. **区分"讨论"与"结论"**。会上说得最多的不一定是最重要的
            2. **保留不同意见**。同一件事出现两种立场时写成"待决", 不要抹平成一句话
            3. 转写文本常有识别错误, 按上下文修正**明显的**错别字; 但不要改写原意、不要补充原文没有的结论
            4. 不要评价决策好坏(那是另一件事) —— 只如实整理

            ## 边界
            用户只给了片段时, 明确标注「以下依据仅来自提供的片段, 可能不完整」, 不要补全成完整会议的样子。
            """;

    // ===== 3. 代码评审 =====

    private static SkillDefinitionEntity codeReview(LocalDateTime now) {
        return build("skill_builtin_code-review", "code-review", "代码评审",
                "当用户要评审一段代码、审查 PR / 合并请求, 或想知道自己的代码有什么风险与问题时使用。",
                CODE_REVIEW_INSTRUCTIONS, Map.of(), "dev-programming", List.of("代码评审", "review", "质量"), now);
    }

    private static final String CODE_REVIEW_INSTRUCTIONS = """
            # 代码评审

            ## 目标
            给**可执行**的评审意见: 每条都要说清"哪里、为什么是问题、怎么改"。
            不写"建议多写注释"这类无法落地的评语。

            ## 评审维度(逐项过, 不跳)
            1. **正确性**: 逻辑真的实现了意图吗? 有没有反了的条件、差一位的边界?
            2. **边界与异常**: 空值 / 空集合 / 零 / 负数 / 超长 / 并发 / 首次调用 —— 每个都要问"这时候会怎样"
            3. **错误处理**: 失败路径是被静默吞掉, 还是如实上报? 有没有把异常当控制流用?
            4. **安全**: 外部输入有没有被直接拼进 SQL / 命令 / 文件路径? 敏感信息是否会被写进日志?
            5. **可读性**: 命名是否表达意图? 嵌套是否超过 3 层? 有没有"要读三遍才懂"的表达式?
            6. **资源与性能**: 循环里查库(N+1)? 无界集合? 大对象被整份读进内存?
            7. **一致性**: 与项目既有写法是否一致? (不一致本身不是错, 但要指出并说明理由)

            ## 输出格式
            先给**总评**(三句以内: 整体能不能合、最该先改什么), 再按严重度分组:

            ### 阻断(必须改)
            - `文件:行号` —— 问题 —— 为什么 —— 建议改法

            ### 建议(改了更好)
            ### 疑问(需要作者确认)

            某一组为空就直接写「无」, 不要留空标题。

            ## 边界
            - **不确定就说不确定**: 缺少上下文时归入「疑问」, 并写清"需要什么信息才能判断", 不要猜
            - **不要重写整份代码**, 除非用户明确要求 —— 优选用最小改动指出问题
            - 不评价格式偏好(空格/换行), 除非项目有明确规范
            - 用户没给完整文件时, 不要臆测未展示的部分, 直接说明"以下仅基于提供的片段"
            """;

    // ===== 4. 需求整理 =====

    private static SkillDefinitionEntity requirementSpec(LocalDateTime now) {
        return build("skill_builtin_requirement-spec", "requirement-spec", "需求整理",
                "当用户要把口语化、零散的需求整理成开发可直接开工的规格, 或需要澄清需求边界与验收标准时使用。",
                REQUIREMENT_INSTRUCTIONS, Map.of(), "business-ops", List.of("需求", "规格", "验收标准"), now);
    }

    private static final String REQUIREMENT_INSTRUCTIONS = """
            # 需求整理

            ## 目标
            把口语化的需求整理成**开发可以直接开工**的规格。核心动作是
            **把模糊的地方变成必须回答的问题**, 而不是用常识把空白填上。

            ## 处理顺序
            1. **先复述理解**: 用 2~3 句说清"你要做什么、解决什么问题"。复述错了后面全白做, 所以必须放到最前
            2. **拆成需求项**: 每条写成「谁 + 做什么 + 期望结果」(能写成"作为…我希望…以便…"更好)
            3. **每条配验收标准**: 必须可验证
            4. **列缺口**: 只列"不回答就没法开工"的问题

            ## 输出结构
            ### 一、我的理解(请确认)
            ### 二、需求项
            每条: 需求描述 + 验收标准
            ### 三、边界与不做的事
            ### 四、待确认(阻塞开工的问题)

            ## 写作要求
            1. **不要自己补需求**。缺的地方放进「待确认」, 不要用"通常还会需要…"填上 ——
               编出来的需求一旦进入开发, 返工成本远高于多问一句
            2. 验收标准必须**可验证**: 「体验流畅」不是标准, 「首屏加载 < 2s」才是
            3. **主动写「不做的事」**: 明确排除项比补充说明更能防止返工
            4. 问题按阻塞程度排序, **一次最多问 5 个** —— 一次抛出十几个问题, 用户会放弃回答

            ## 边界
            - 用户已给的信息要严格采用, 不要"优化"成你熟悉的样子
            - 涉及技术选型时, 只列出选项与权衡, 不替用户拍板
            """;

    // ===== 构造 =====

    /**
     * 构造一个内置技能。
     * <p>
     * 刻意固定的几点: {@code userId} 留空(内置标识, 见 {@code SkillService.delete} 的不可删除保护)、
     * {@code source=builtin}、{@code isPublic=false}(内置技能的可见性由
     * {@code SkillService.visibleEnabledSkills} 单独保证, 不走"用户主动公开"这条语义)、
     * 以及 {@code requiredTools} 为空、{@code hasExecutable=false} ——
     * 后者正是"开箱即用"的前提: 不依赖任何工具, 也就不存在"能力没启用"的问题。
     */
    private static SkillDefinitionEntity build(String id, String name, String displayName, String description,
                                              String instructions, Map<String, String> resources,
                                              String categoryId, List<String> tags, LocalDateTime now) {
        SkillDefinitionEntity e = new SkillDefinitionEntity();
        e.setId(id);
        e.setName(name);
        e.setDisplayName(displayName);
        e.setDescription(description);
        e.setVersion("1.0.0");
        e.setAuthor("平台内置");
        e.setCategoryId(categoryId);
        e.setTags(SkillJson.writeList(tags));
        e.setInstructions(instructions);
        e.setResourcesJson(SkillJson.writeMap(resources));
        e.setRequiredTools(null);
        e.setHasExecutable(false);
        e.setSource("builtin");
        e.setPublic(false);
        e.setEnabled(true);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return e;
    }
}
