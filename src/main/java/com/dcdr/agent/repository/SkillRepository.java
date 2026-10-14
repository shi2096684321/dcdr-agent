package com.dcdr.agent.repository;

import com.dcdr.agent.entity.SkillDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SkillRepository extends JpaRepository<SkillDefinitionEntity, String> {

    /** 某用户的全部技能(含停用), 按更新时间倒序 */
    List<SkillDefinitionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    /** 全部技能(admin 视图), 按更新时间倒序 */
    List<SkillDefinitionEntity> findAllByOrderByUpdatedAtDesc();

    /** 内置技能(userId 为 null) */
    List<SkillDefinitionEntity> findByUserIdIsNullOrderByCreatedAtAsc();

    /** 已启用的内置技能(userId 为 null) —— 内置技能对所有登录用户生效, 不依赖 isPublic */
    List<SkillDefinitionEntity> findByEnabledTrueAndUserIdIsNullOrderByCreatedAtAsc();

    /**
     * 某用户可见的<b>已启用</b>技能 = 自己的 + 公开的。
     * <p>
     * 用于组装"技能目录"(注入 system prompt)。停用的不注入 —— 停用的语义就是
     * "别让模型用", 与"删掉"的区别只在于定义还在, 便于重新启用。
     */
    List<SkillDefinitionEntity> findByEnabledTrueAndUserIdOrderByUpdatedAtDesc(String userId);

    /** 已启用的公开技能 */
    List<SkillDefinitionEntity> findByEnabledTrueAndIsPublicTrueOrderByUpdatedAtDesc();

    /** 按标识查(不分归属, 供导入时查重) */
    List<SkillDefinitionEntity> findByName(String name);

    /** 按归属 + 标识查(唯一约束对应的自然键查询) */
    Optional<SkillDefinitionEntity> findByUserIdAndName(String userId, String name);

    /** 统计某用户的技能数(配额校验用) */
    long countByUserId(String userId);
}
