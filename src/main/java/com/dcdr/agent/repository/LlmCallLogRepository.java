package com.dcdr.agent.repository;

import com.dcdr.agent.entity.LlmCallLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LlmCallLogRepository extends JpaRepository<LlmCallLogEntity, String> {

    /**
     * 分页查询调用链日志(按时间倒序), 支持按来源筛选。
     *
     * <p><b>放在一个 {@code @Query} 里而不是派生四个方法名</b>: 组合是
     * (全部用户 / 指定用户) × (全部来源 / 指定来源), 派生写法要四个几乎相同的方法;
     * 更麻烦的是"来源=user"必须把 {@code source IS NULL} 也算进去 ——
     * V3.3 之前没有该列, 存量行全是 null, 而那时只有用户对话会写日志,
     * 按 {@code source='user'} 精确匹配会让历史记录凭空消失在筛选结果里。
     *
     * @param userId null 表示不限用户(管理员全局视图)
     * @param source null 表示不限来源
     */
    @Query("SELECT e FROM LlmCallLogEntity e "
            + "WHERE (:userId IS NULL OR e.userId = :userId) "
            + "AND (:source IS NULL OR e.source = :source "
            + "     OR (:source = 'user' AND e.source IS NULL)) "
            + "ORDER BY e.createdAt DESC")
    Page<LlmCallLogEntity> findForList(@Param("userId") String userId,
                                       @Param("source") String source,
                                       Pageable pageable);

    /**
     * 查询时间窗口内的调用链日志, 供检索健康度聚合使用。
     *
     * <p><b>为什么不用 nativeQuery 直接展开 JSON</b>: {@code steps_json} 虽是 JSON 文本,
     * 但 PG 的 {@code jsonb_array_elements} 属 PG 专属方言 —— 用了会绑死数据库,
     * 并在 H2 测试下直接报错(项目已有同类踩坑记录, 见 {@code LlmUsageLogRepository} 注释)。
     * 故此处只做"按窗口取行", JSON 的展开与聚合放在 Java 侧完成。
     *
     * <p>约定与 {@code LlmUsageLogRepository} 一致: 窗口<b>左闭右开</b>;
     * {@code userId} 传 null 表示不限用户(管理员全局视图)。
     * 调用方须通过 {@link Pageable} 传扫描上限, 避免大数据量下一次拉全表。
     */
    @Query("SELECT e FROM LlmCallLogEntity e "
            + "WHERE (:userId IS NULL OR e.userId = :userId) AND e.createdAt >= :from "
            + "ORDER BY e.createdAt ASC")
    List<LlmCallLogEntity> findForHealthAggregation(@Param("userId") String userId,
                                                    @Param("from") LocalDateTime from,
                                                    Pageable pageable);

    /**
     * 会话被删除时解除引用 (V3.3)。
     *
     * <p><b>为什么置空而不是删行</b>: 调用链日志的用途是观测与成本核算 ——
     * token 数、耗时、失败率、来源分布都靠它, 而会话只是"能跳回去看"的那条线索。
     * 定时任务按分钟级跑、会话只保留最近 20 条, 若跟着删日志, 几天前的失败记录就一起没了,
     * 等于把"排查用的证据"交给"控制存储量的清理策略"决定。故只断开跳转链接, 保留统计。
     *
     * <p>批量 UPDATE 必须显式 {@code @Modifying}: 派生删除/更新方法由 Spring Data 自动处理,
     * 而 {@code @Query} 的写操作不加注解会直接抛 {@code InvalidDataAccessApiUsageException}。
     *
     * <p><b>为什么是 {@code flushAutomatically} 而不是更常见的 {@code clearAutomatically}</b>:
     * 本方法在"删会话"的事务里执行, 此时 sessionRepository / messageRepository 的删除还排在
     * Hibernate 的动作队列里没落库; {@code clear()} 会把队列一起清掉, 表现为<b>删除静默失效</b>
     * (日志显示删过, 库里一行没少)。{@code flush()} 则先把待执行的删除刷下去, 再执行本更新。
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE LlmCallLogEntity e SET e.sessionId = NULL WHERE e.sessionId = :sessionId")
    int detachSession(@Param("sessionId") String sessionId);
}