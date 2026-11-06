package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 「总上下文大小」设置的 DTO (V3.4)。
 * <p>
 * 读写不对称是刻意的: 读要一次给出<b>全部三个值</b>(生效值 / 用户自己设的 / 部署默认),
 * 界面才能同时渲染"当前是多少"和"点恢复默认会变成多少"; 写只需要一个值。
 * 若只回传生效值, 前端就得自己记住部署默认值才能画那个复位的提示 —— 那是第二份真值源。
 */
public class ContextLimitDtos {

    private ContextLimitDtos() {}

    /** 总上下文设置视图 */
    @Data
    @Schema(description = "总上下文设置")
    public static class ContextLimitView {

        @Schema(description = "当前生效的上限(token)")
        private int limit;

        /** {@code null} = 用户未设置, 当前生效值来自部署默认 */
        @Schema(description = "用户自己设置的值; null 表示未设置(跟随部署默认)")
        private Integer userValue;

        @Schema(description = "部署默认值(界面上「恢复默认」的目标值)")
        private int defaultValue;

        @Schema(description = "允许的最小值")
        private int min;

        @Schema(description = "允许的最大值")
        private int max;

        /**
         * 是否可修改。
         * <p>认证关闭({@code auth.enabled=false})时没有用户行可存设置 —— 界面上应把输入框禁用,
         * 而不是让用户改完发现"保存了但没生效"。
         */
        @Schema(description = "当前部署是否允许修改(无登录用户时为 false)")
        private boolean editable;

        /**
         * 可选档位(含首项「跟随默认」, value=null)。
         * <p>
         * 由后端下发而不是前端写死: 档位要带上部署默认值(它随配置变化), 标签还要标出"哪一档就是当前默认";
         * 若前端自己拼, 部署方改了 {@code context-token-limit} 之后两边就对不上了。
         */
        @Schema(description = "可选档位列表（首项 value=null 表示跟随部署默认）")
        private java.util.List<Option> options;

        /** 档位项: {@code value=null} → 跟随部署默认 */
        @Data
        @Schema(description = "上下文长度档位")
        public static class Option {

            @Schema(description = "token 值; null 表示跟随部署默认")
            private Integer value;

            @Schema(description = "展示文案", example = "300.0K · 加强")
            private String label;
        }
    }

    /** 设置总上下文请求 */
    @Data
    @Schema(description = "设置总上下文")
    public static class ContextLimitRequest {

        /**
         * {@code null} = 恢复默认(清除个人设置, 重新跟随部署配置)。
         * <p>与「设置成和默认一样的值」不同: 后者会钉住, 部署方日后调整默认值时不会跟着变。
         */
        @Schema(description = "总上下文(token); null = 恢复默认(跟随部署配置)")
        private Integer limit;
    }
}
