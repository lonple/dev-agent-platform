package com.lonple.dap.server.modules.pipeline;

import java.util.List;

/**
 * 流水线 Agent 单元材料约定（对齐《流水线节点与Agent配置》第 5 节）。
 */
public record PipeUnitMaterialSpec(
        /** 输出参考模板文件名（wiki/模板/ 下）；null=无 */
        String templateFileName,
        /**
         * 规范：相对「规范」目录的文件名列表；若以 {@code 模板:} 前缀则取 wiki/模板/。
         */
        List<String> standardsRefs,
        /** 必填输入文件名（位于发布目录下） */
        List<String> requiredInputFiles,
        /** 可选输入文件名（缺失则跳过） */
        List<String> optionalInputFiles,
        /**
         * 上下文：相对产品 Wiki 根的目录路径，如 {@code 归档/需求}；
         * 特殊值 {@code $VER_REQ} 表示绑定发布目录 {@code {计划版本}/{需求编号}}。
         */
        List<String> contextRelDirs,
        /** 正式产出文件名 */
        String formalOutputName,
        /** 确认汇总文件名 */
        String confirmOutputName,
        /** 发布根：RAW=原始需求/{需求}；VER={计划版本}/{需求}；ISSUE=issues/{问题单号} */
        PublishRoot publishRoot,
        /** 仓库要求 */
        RepoRequirement repoRequirement
) {
    public enum PublishRoot {
        RAW,
        VER,
        /** wiki/{产品}/issues/{问题单号} */
        ISSUE
    }

    public enum RepoRequirement {
        NONE,
        BIZ_REQUIRED,
        BIZ_OPTIONAL,
        TEST_REQUIRED
    }
}
