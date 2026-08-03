package com.dcdr.agent.agent.session;

import com.dcdr.agent.dto.GenerationDTO;
import com.dcdr.agent.dto.GenerationPageDTO;
import com.dcdr.agent.dto.ProjectDTO;
import com.dcdr.agent.entity.GenerationEntity;
import com.dcdr.agent.entity.ProjectEntity;
import com.dcdr.agent.repository.GenerationRepository;
import com.dcdr.agent.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 项目管理器 (创作模式) - 管理创作项目和生成记录
 * <p>
 * 基于 JPA 持久化，项目存 projects 表，生成记录存 generations 表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectManager {

    private final ProjectRepository projectRepository;
    private final GenerationRepository generationRepository;

    /** 创建项目 */
    @Transactional
    public ProjectDTO createProject(String name, String mode, String defaultModel) {
        String id = "proj_" + System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        ProjectEntity entity = new ProjectEntity();
        entity.setId(id);
        entity.setName(name != null ? name : "新项目");
        entity.setMode(mode != null ? mode : "image");
        entity.setDefaultModel(defaultModel);
        entity.setGenerationCount(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        projectRepository.save(entity);
        log.info("创建项目: {} ({})", id, entity.getName());
        return toDTO(entity);
    }

    /** 获取项目 */
    public ProjectDTO getProject(String id) {
        return projectRepository.findById(id).map(ProjectManager::toDTO).orElse(null);
    }

    /** 列出所有项目 */
    public List<ProjectDTO> listProjects() {
        return projectRepository.findAllByOrderByUpdatedAtDesc().stream()
                .map(ProjectManager::toDTO)
                .toList();
    }

    /** 删除项目 */
    @Transactional
    public void deleteProject(String id) {
        projectRepository.deleteById(id);
        generationRepository.deleteByProjectId(id);
        log.info("删除项目: {}", id);
    }

    /** 重命名项目 */
    @Transactional
    public ProjectDTO renameProject(String id, String name) {
        ProjectEntity entity = projectRepository.findById(id).orElse(null);
        if (entity == null) return null;
        if (name != null && !name.isBlank()) {
            entity.setName(name.trim());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        projectRepository.save(entity);
        log.info("重命名项目: {} -> {}", id, entity.getName());
        return toDTO(entity);
    }

    /** 添加生成记录 */
    @Transactional
    public void addGeneration(String projectId, GenerationDTO record) {
        GenerationEntity entity = toEntity(record);
        if (entity.getId() == null) {
            entity.setId("gen_" + System.currentTimeMillis());
        }
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(LocalDateTime.now());
        }
        generationRepository.save(entity);

        projectRepository.findById(projectId).ifPresent(p -> {
            p.setGenerationCount(p.getGenerationCount() + 1);
            p.setUpdatedAt(LocalDateTime.now());
            projectRepository.save(p);
        });
    }

    /** 获取项目的生成历史 */
    public List<GenerationDTO> getGenerations(String projectId) {
        return generationRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(ProjectManager::toDTO)
                .toList();
    }

    /**
     * 分页获取项目的生成历史, 可按 type (image/video) 过滤。
     *
     * @param projectId 项目 ID
     * @param type      生成类型, 为空表示不过滤
     * @param page      页码 (从 1 开始)
     * @param size      每页大小
     */
    public GenerationPageDTO getGenerationsPage(String projectId, String type, int page, int size) {
        if (!projectRepository.existsById(projectId)) {
            throw new RuntimeException("项目不存在: " + projectId);
        }
        int zeroBased = Math.max(page - 1, 0);
        Pageable pageable = PageRequest.of(zeroBased, size);
        Page<GenerationEntity> result = (type == null || type.isBlank())
                ? generationRepository.findByProjectIdOrderByCreatedAtDesc(projectId, pageable)
                : generationRepository.findByProjectIdAndType(projectId, type, pageable);
        List<GenerationDTO> records = result.getContent().stream()
                .map(ProjectManager::toDTO)
                .toList();
        return GenerationPageDTO.of(records, result.getTotalElements(), page, size);
    }

    /** 获取单条生成记录 */
    public GenerationDTO getGeneration(String id) {
        return generationRepository.findById(id).map(ProjectManager::toDTO).orElse(null);
    }

    /**
     * 更新生成记录状态与结果 (异步任务完成/失败时回写)。
     * 仅更新状态相关字段, 不覆盖提交阶段已写入的提示词、积分等。
     */
    @Transactional
    public void updateGeneration(GenerationDTO record) {
        generationRepository.findById(record.getId()).ifPresent(e -> {
            e.setStatus(record.getStatus());
            e.setResultUrl(record.getResultUrl());
            e.setThumbnailUrl(record.getThumbnailUrl());
            e.setErrorMessage(record.getErrorMessage());
            e.setDurationMs(record.getDurationMs());
            generationRepository.save(e);
        });
    }

    // ===== 转换 =====

    static ProjectDTO toDTO(ProjectEntity e) {
        ProjectDTO dto = new ProjectDTO();
        dto.setId(e.getId());
        dto.setName(e.getName());
        dto.setMode(e.getMode());
        dto.setDefaultModel(e.getDefaultModel());
        dto.setGenerationCount(e.getGenerationCount());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setUpdatedAt(e.getUpdatedAt());
        return dto;
    }

    static GenerationDTO toDTO(GenerationEntity e) {
        GenerationDTO dto = new GenerationDTO();
        dto.setId(e.getId());
        dto.setProjectId(e.getProjectId());
        dto.setType(e.getType());
        dto.setPrompt(e.getPrompt());
        dto.setReferImageUrl(e.getReferImageUrl());
        dto.setResultUrl(e.getResultUrl());
        dto.setThumbnailUrl(e.getThumbnailUrl());
        dto.setModel(e.getModel());
        dto.setParamsJson(e.getParamsJson());
        dto.setCostCredits(e.getCostCredits());
        dto.setStatus(e.getStatus());
        dto.setErrorMessage(e.getErrorMessage());
        dto.setDurationMs(e.getDurationMs());
        dto.setCreatedAt(e.getCreatedAt());
        dto.setSize(e.getSize());
        return dto;
    }

    static GenerationEntity toEntity(GenerationDTO dto) {
        GenerationEntity e = new GenerationEntity();
        e.setId(dto.getId());
        e.setProjectId(dto.getProjectId());
        e.setType(dto.getType());
        e.setPrompt(dto.getPrompt());
        e.setSize(dto.getSize());
        e.setReferImageUrl(dto.getReferImageUrl());
        e.setResultUrl(dto.getResultUrl());
        e.setThumbnailUrl(dto.getThumbnailUrl());
        e.setModel(dto.getModel());
        e.setParamsJson(dto.getParamsJson());
        e.setCostCredits(dto.getCostCredits());
        e.setStatus(dto.getStatus());
        e.setErrorMessage(dto.getErrorMessage());
        e.setDurationMs(dto.getDurationMs());
        e.setCreatedAt(dto.getCreatedAt());
        return e;
    }
}
