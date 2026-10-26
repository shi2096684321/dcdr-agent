package com.dcdr.agent.dto;

import com.dcdr.agent.workspace.WorkspaceAccess;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区 DTO(V3.3 F-49)。
 * <p>
 * {@code rootPath} 原样返回真实绝对路径 —— 用户必须能随时确认"模型到底在读哪个目录",
 * 这是安全感的来源, 不做任何脱敏或简化。
 */
@Data
@Schema(description = "工作区信息")
public class WorkspaceDTO {

    @Schema(description = "工作区 ID")
    private String id;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "真实绝对路径")
    private String rootPath;

    @Schema(description = "授权档位", allowableValues = {"READ", "WRITE", "FULL"})
    private WorkspaceAccess accessLevel;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
