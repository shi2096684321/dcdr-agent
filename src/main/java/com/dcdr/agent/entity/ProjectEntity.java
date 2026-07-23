package com.dcdr.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创作项目实体 (创作模式)
 */
@Data
@Entity
@Table(name = "projects")
public class ProjectEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private String name;

    /** 子模式: image / video */
    @Column(nullable = false, length = 16)
    private String mode;

    @Column(length = 64)
    private String defaultModel;

    @Column(nullable = false)
    private int generationCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
