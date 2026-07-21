package com.example.agent.agent.session;

import com.example.agent.dto.GenerationDTO;
import com.example.agent.dto.ProjectDTO;
import com.example.agent.entity.GenerationEntity;
import com.example.agent.entity.ProjectEntity;
import com.example.agent.repository.GenerationRepository;
import com.example.agent.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
        return dto;
    }

    static GenerationEntity toEntity(GenerationDTO dto) {
        GenerationEntity e = new GenerationEntity();
        e.setId(dto.getId());
        e.setProjectId(dto.getProjectId());
        e.setType(dto.getType());
        e.setPrompt(dto.getPrompt());
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
