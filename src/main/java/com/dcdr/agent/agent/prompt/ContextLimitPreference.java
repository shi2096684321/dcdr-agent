package com.dcdr.agent.agent.prompt;

import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.entity.UserEntity;
import com.dcdr.agent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 「总上下文大小」的用户级设置 (V3.4) —— 本类<b>独占</b>这套取值语义。
 *
 * <p><b>为什么要可设置</b>: {@code agent.memory.context-token-limit} 是部署级默认值(300000),
 * 它决定"占用到多少算接近上限"。但"我这个模型窗口多大"是用户手里的事实 ——
 * 用 8k 窗口的小模型时按 30 万算会一直显示 3% 永不告警, 用 1M 窗口的大模型时又会天天告警。
 * 让用户按自己的模型改一次, 比让部署方为每个人改配置靠谱。
 *
 * <p><b>三态与优先级</b>: 用户没设置(列可空) → 跟随部署默认。刻意不把用户值写死在配置里:
 * 部署方日后调整默认值时, 只有"真没设过"的人跟着变, 设过的人不受影响(与 {@code AutoExtractPreference} 同口径)。
 *
 * <p><b>范围校验放在这里而不是各处调用点</b>: 上限是分母, 取 0 会让百分比变成 Infinity/NaN,
 * 取 1 则任何会话都是 100%。这类值一旦进库, 后面每个读它的地方都得各自防一遍。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextLimitPreference {

    /**
     * 下限 8000。
     * <p>原来是 2000, 但那个量级是<b>有害</b>而非仅仅无用: 摘要策略的触发点会随窗口收敛
     * (×0.6 → 1200), 而"摘要 + 最近 keep-recent 条原文"本身就能超过这个数, 于是每一轮都会
     * 判超预算并调一次模型压缩 —— 用户设了个小窗口, 换来的是每轮多一次付费调用。
     * 8000 仍小于主流小窗口(32K), 但已经容得下系统提示词 + 工具定义 + 几轮对话。
     */
    public static final int MIN_LIMIT = 8_000;
    /** 上限 200 万: 覆盖当前最大窗口(1M)并留余量; 再大只可能是误输入 */
    public static final int MAX_LIMIT = 2_000_000;

    /**
     * 可选档位(token)。
     * <p>
     * <b>为什么是档位而不是自由输入</b>(V3.4 修正): 这个值是<b>分母</b>, 填错不会报错、只会让整个
     * 面板持续显示错的比例 —— 填 1 就是永远 100%, 填 30000000 就是永远 0%。而它对用户来说本来就是
     * "我这个模型的窗口有多大", 答案只可能是有限几个值(主流窗口 32K / 128K / 200K / 300K / 1M)。
     * 给档位等于把"填错"这条路径直接删掉, 也免得界面上出现 299999 这种没人想要的数字。
     * <p>
     * 部署默认值不在这里 —— 它随 {@code agent.memory.context-token-limit} 变化, 由
     * {@link #options()} 动态并入。
     */
    public static final int[] PRESET_LADDER = {32_000, 128_000, 200_000, 300_000, 1_000_000};

    private final UserRepository userRepository;
    private final AgentProperties properties;

    /** 部署默认值(用户未设置时生效, 同时是界面上"恢复默认"的目标值) */
    public int deploymentDefault() {
        int configured = properties.getMemory().getContextTokenLimit();
        // 配置本身也可能被写坏(0/负数), 这里兜住: 分母不能是 0
        return configured >= MIN_LIMIT ? configured : MIN_LIMIT;
    }

    /**
     * 可选的档位清单(供界面下拉使用) —— 标签由后端给, 前端不再自己拼"多少 K / 什么档"。
     * <p>
     * 第一项固定是「跟随默认」（value=null）, 其后是升序档位。部署默认值一定会出现在列表里 ——
     * 否则用户点了「恢复默认」之后, 下拉框里没有与当前生效值对应的项, 会显示成空白。
     *
     * @return 不可变列表, 首项 value 为 {@code null}
     */
    public List<Option> options() {
        int def = deploymentDefault();
        TreeSet<Integer> ladder = new TreeSet<>();
        for (int v : PRESET_LADDER) {
            if (v >= MIN_LIMIT && v <= MAX_LIMIT) ladder.add(v);
        }
        ladder.add(def);

        List<Option> out = new ArrayList<>(ladder.size() + 1);
        out.add(new Option(null, "跟随默认（" + formatTokens(def) + "）"));
        for (int v : ladder) {
            String label = formatTokens(v) + " · " + tierOf(v) + (v == def ? "（当前默认）" : "");
            out.add(new Option(v, label));
        }
        return List.copyOf(out);
    }

    /** 档位项: {@code value=null} 表示跟随部署默认 */
    public record Option(Integer value, String label) {}

    /** token 数的展示缩写(与前端图例同口径): 1.0M / 300.0K / 800 */
    public static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000) return String.format(Locale.ROOT, "%.1fK", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    /**
     * 档位人话。分界刻意与常见模型的窗口对齐(64K 以下算小窗口、200K 是主流大窗口、
     * 400K 以上只有长上下文机型), 让用户在下拉里能按"自己的模型属于哪一档"来选, 而不是背数字。
     */
    private static String tierOf(int tokens) {
        if (tokens <= 64_000) return "紧凑";
        if (tokens <= 200_000) return "标准";
        if (tokens <= 400_000) return "加强";
        return "超长";
    }

    /**
     * 用户自己的设置。
     *
     * @return 已设置的值; {@code null} = 未设置(跟随部署默认)或找不到用户
     */
    public Integer userChoice(String userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(UserEntity::getContextTokenLimit)
                .filter(v -> v >= MIN_LIMIT)
                .orElse(null);
    }

    /**
     * 本轮实际生效的上限 —— 占用百分比与"接近上限"告警都以它为准。
     * <p>认证关闭({@code userId == null})时没有用户行可读, 直接用部署默认值:
     * 这与记忆开关不同, 上下文占用是纯展示口径, 没有"同意"含义, 没必要因此失效。
     */
    public int effectiveLimit(String userId) {
        Integer choice = userChoice(userId);
        return choice != null ? choice : deploymentDefault();
    }

    /**
     * 记录用户设置。
     *
     * @param limit {@code null} = 恢复默认(清除个人设置, 重新跟随部署配置)
     * @throws IllegalArgumentException 取值越界(由 Controller 转 400)
     */
    @Transactional
    public void update(String userId, Integer limit) {
        if (userId == null) {
            // 认证关闭模式: 没有用户行可写。静默忽略而不是报错 —— 界面上这个输入框本就该禁用的
            log.warn("总上下文设置: 无登录用户, 忽略设置请求 limit={}", limit);
            return;
        }
        if (limit != null && (limit < MIN_LIMIT || limit > MAX_LIMIT)) {
            throw new IllegalArgumentException(
                    String.format("总上下文需在 %d ~ %d 之间", MIN_LIMIT, MAX_LIMIT));
        }
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            // token 指向已删除用户: 静默跳过, 不因此让请求失败
            log.warn("总上下文设置: 用户不存在, 忽略设置请求 userId={}", userId);
            return;
        }
        user.setContextTokenLimit(limit);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("总上下文设置: user={}, limit={}", userId, limit == null ? "跟随默认" : limit);
    }
}
