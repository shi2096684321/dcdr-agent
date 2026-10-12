package com.dcdr.agent.repository;

import com.dcdr.agent.entity.LlmCallLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LlmCallLogRepository extends JpaRepository<LlmCallLogEntity, String> {

    /** 分页查询调用链日志(按时间倒序) */
    Page<LlmCallLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 按用户分页查询 */
    Page<LlmCallLogEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

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
}