package com.example.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成记录实体 (创作模式)
 */
@Data
@Entity
@Table(name = "generations", indexes = {
        @Index(name = "idx_generations_project", columnList = "projectId")
})
public class GenerationEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String projectId;

    /** 类型: image / video */
    @Column(nullable = false, length = 16)
    private String type;

    @Column(length = 4000)
    private String prompt;

    @Column(length = 1024)
    private String referImageUrl;

    @Column(length = 1024)
    private String resultUrl;

    @Column(length = 1024)
    private String thumbnailUrl;

    @Column(length = 64)
    private String model;

    /** 生成参数 JSON */
    @Column(length = 4000)
    private String paramsJson;

    @Column(nullable = false)
    private int costCredits;

    /** 状态: pending / completed / failed */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 1000)
    private String errorMessage;

    private long durationMs;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
