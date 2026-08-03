package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 生成记录分页结果 (创作模式历史记录)
 */
@Data
@Schema(description = "生成记录分页结果")
public class GenerationPageDTO {

    @Schema(description = "当前页记录")
    private List<GenerationDTO> records;

    @Schema(description = "总记录数")
    private long total;

    @Schema(description = "当前页码 (从 1 开始)")
    private int page;

    @Schema(description = "每页大小")
    private int size;

    @Schema(description = "总页数")
    private int totalPages;

    public static GenerationPageDTO of(List<GenerationDTO> records, long total, int page, int size) {
        GenerationPageDTO dto = new GenerationPageDTO();
        dto.setRecords(records);
        dto.setTotal(total);
        dto.setPage(page);
        dto.setSize(size);
        dto.setTotalPages(size <= 0 ? 0 : (int) Math.ceil((double) total / size));
        return dto;
    }
}
