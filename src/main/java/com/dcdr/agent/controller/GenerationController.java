package com.dcdr.agent.controller;

import com.dcdr.agent.agent.session.ProjectManager;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.GenerationDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生成记录查询接口 - 供前端轮询异步生成任务的状态与结果。
 */
@RestController
@RequestMapping("/api/generations")
@RequiredArgsConstructor
@Tag(name = "生成记录", description = "查询生成任务状态与结果")
public class GenerationController {

    private final ProjectManager projectManager;

    @Operation(summary = "查询生成记录", description = "根据生成记录 ID 查询状态(pending/completed/failed)与结果 URL")
    @GetMapping("/{id}")
    public Result<GenerationDTO> get(@PathVariable String id) {
        return Result.success(projectManager.getGeneration(id));
    }
}
