package com.dcdr.agent.skill.market;

/**
 * 技能市场排序方式 (V3.1)。
 * <p>
 * <b>为什么是一个枚举, 而不是把用户传的字符串直接转发给上游</b> —— 这是本类存在的全部理由:
 * 上游的 {@code sortBy} 是<b>严格白名单</b>, 未知值直接返回 400(实测):
 * <pre>
 *   sortBy=downloads / installs / stars / score / updated_at(updatedAt/updated)  → 200
 *   sortBy=created_at / name / version / trending / bogus_field_xyz              → 400
 * </pre>
 * 所以"透传用户输入"等于让前端的一个拼写错误或一个陈旧的页面把整页打成 502。
 * 这里把外部输入<b>映射到有限枚举</b>, 再由枚举产出上游参数 —— 用户输入永远不参与 URL 拼接。
 * <p>
 * <b>未知值回落「推荐」而不是报错</b>: 排序是展示偏好, 不是数据写入。一个过期的前端
 * 不该让用户连市场都打不开; 同时 {@code MarketPage.sort} 会把实际生效的排序回显出去,
 * 于是"我传的值没生效"仍然是可观测的, 不需要靠猜。
 * <p>
 * <b>只暴露降序</b>: 上游的 {@code order} 支持 asc/desc 且非法值也 400, 但"按下载量升序"
 * 对找技能这个场景没有意义 —— 少暴露一个参数就少一处出错的地方。
 * <p>
 * <b>没有"上游默认顺序"这一项</b>: 上游默认顺序是一个不透明的内部排列(实测首页多为
 * 下载量极低的技能), 既说不清也不比"推荐"更好。留着它只会多一个用户无法判断的选项 ——
 * 这也是 WorkBuddy 的做法: 它的排序只有 推荐 / 最新 / 最热 / 优质, 没有"综合"。
 */
public enum SkillMarketSort {

    /**
     * 推荐: <b>本地复合评分</b>, 不是上游排序。
     * <p>
     * 为什么得自己算: 上游<b>没有</b>推荐端点 —— 实测 {@code /api/v1/skills/recommended}、
     * {@code /featured}、{@code /trending} 全部 404; 而 {@code /api/v1/skills/hot} 虽然返回 200,
     * 却是"slug 恰好叫 hot"的那条技能详情, 跟热门榜无关。WorkBuddy 的「推荐」同样是它自己后端
     * 算出来的排序项, 不是一个可调用的接口。
     * <p>
     * 这里的 {@code upstreamField} 对推荐项的含义与其它项<b>不同</b>: 它只用于取<b>候选池</b>
     * (按下载量取热门前 N), 最终顺序由 {@code SkillMarketService#rankRecommended} 决定。
     * 用枚举字段而不是字面量, 是为了让"上游参数只能来自白名单"这条不变量没有例外。
     */
    RECOMMENDED("recommended", "downloads", "desc", "推荐", true),
    DOWNLOADS("downloads", "downloads", "desc", "下载最多", false),
    INSTALLS("installs", "installs", "desc", "安装最多", false),
    STARS("stars", "stars", "desc", "收藏最多", false),
    /**
     * 最近更新: 上游三种写法都认(updated_at / updatedAt / updated), 这里固定用下划线形式 ——
     * 它同时在响应字段里出现, 是上游最"原生"的那个名字。
     */
    RECENT("recent", "updated_at", "desc", "最近更新", false);

    /** 对外暴露的 key(前端传这个) */
    private final String key;
    /** 上游 sortBy 取值(推荐项用作候选池口径, 见类注释) */
    private final String upstreamField;
    /** 上游 order 取值 */
    private final String order;
    /** 展示名(经 /sorts 接口下发给前端, 避免两边各写一份标签而漂移) */
    private final String label;
    /** 最终顺序是否由本地评分决定(而不是上游) */
    private final boolean locallyRanked;

    SkillMarketSort(String key, String upstreamField, String order, String label, boolean locallyRanked) {
        this.key = key;
        this.upstreamField = upstreamField;
        this.order = order;
        this.label = label;
        this.locallyRanked = locallyRanked;
    }

    /**
     * 解析对外 key。
     *
     * @return 匹配的枚举; 未知/空值一律返回 {@link #RECOMMENDED}(见类注释的取舍)
     */
    public static SkillMarketSort fromKey(String key) {
        if (key == null || key.isBlank()) return RECOMMENDED;
        String k = key.trim();
        for (SkillMarketSort s : values()) {
            if (s.key.equalsIgnoreCase(k)) return s;
        }
        return RECOMMENDED;
    }

    public String getKey() {
        return key;
    }

    public String getUpstreamField() {
        return upstreamField;
    }

    public String getOrder() {
        return order;
    }

    public String getLabel() {
        return label;
    }

    public boolean isLocallyRanked() {
        return locallyRanked;
    }
}
