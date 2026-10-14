/**
 * 后端枚举取值的<b>前端镜像</b>。
 *
 * <p><b>为什么需要它</b>: JS 里没有枚举, 于是取值比较会散落成裸字符串
 * ({@code m.sourceKind === 'REQUESTED'})。这类比较的问题是"改一边漏一边不会报错" ——
 * 后端把取值改了, 前端只是<b>静默地</b>不再命中那个分支, 界面上表现为"标签忽然不显示了",
 * 排查时还得反查后端代码。
 *
 * <p><b>两条纪律</b>:
 * <ol>
 *   <li><b>只放"需要做判断"的取值</b>。凡是<b>纯展示</b>的中文名, 一律由后端随值下发
 *       (如 {@code sourceKindLabel} / {@code ragSourceLabel} / {@code autoExtractChoiceLabel}),
 *       前端不维护"取值 → 人话"的映射表 —— 那种表就是文案漂移的源头。</li>
 *   <li><b>取值必须与后端枚举逐字一致</b>。改动其中一边时, 注释里指出的那个 Java 枚举
 *       是唯一的对照物。</li>
 * </ol>
 */

/** 镜像 {@code com.dcdr.agent.agent.memory.longterm.AutoExtractChoice} —— 用户对"AI 自动记忆"的表态 */
export const AUTO_EXTRACT_CHOICE = Object.freeze({
  ON: 'on',
  OFF: 'off',
  /** 未表态: 跟随部署配置 */
  DEFAULT: 'default'
})

/** 镜像 {@code com.dcdr.agent.agent.memory.longterm.MemoryOrigin} —— 一条记忆是谁写进来的 */
export const MEMORY_ORIGIN = Object.freeze({
  /** 用户在记忆页手动新增: 界面上不额外标注(默认情形, 每条都标等于没标) */
  MANUAL: 'MANUAL',
  /** 用户在对话中明确要求记录 */
  REQUESTED: 'REQUESTED',
  /** 平台自动抽取 */
  AUTO: 'AUTO'
})
