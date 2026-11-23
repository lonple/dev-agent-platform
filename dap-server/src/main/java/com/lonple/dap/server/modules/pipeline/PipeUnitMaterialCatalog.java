package com.lonple.dap.server.modules.pipeline;

import java.util.List;
import java.util.Map;

import static com.lonple.dap.server.modules.pipeline.PipeUnitMaterialSpec.PublishRoot.ISSUE;
import static com.lonple.dap.server.modules.pipeline.PipeUnitMaterialSpec.PublishRoot.RAW;
import static com.lonple.dap.server.modules.pipeline.PipeUnitMaterialSpec.PublishRoot.VER;
import static com.lonple.dap.server.modules.pipeline.PipeUnitMaterialSpec.RepoRequirement.BIZ_OPTIONAL;
import static com.lonple.dap.server.modules.pipeline.PipeUnitMaterialSpec.RepoRequirement.BIZ_REQUIRED;
import static com.lonple.dap.server.modules.pipeline.PipeUnitMaterialSpec.RepoRequirement.NONE;
import static com.lonple.dap.server.modules.pipeline.PipeUnitMaterialSpec.RepoRequirement.TEST_REQUIRED;

/**
 * 按 taskTypeCode 索引的材料配置（权威来源：流水线节点与 Agent 配置第 5 节）。
 */
public final class PipeUnitMaterialCatalog {

    private PipeUnitMaterialCatalog() {}

    private static final List<String> EMPTY = List.of();

    /**
     * 改源码任务：只绑 Wiki「规范」目录（见 {@code @规范}），
     * 由 Agent 按实际改动的文件类型自行读取对应规范文件。
     */
    private static final List<String> CODING_STANDARDS_DIR = List.of("@规范");

    private static PipeUnitMaterialSpec s(
            String template,
            List<String> standards,
            List<String> requiredInputs,
            List<String> optionalInputs,
            List<String> contexts,
            String formal,
            String confirm,
            PipeUnitMaterialSpec.PublishRoot root,
            PipeUnitMaterialSpec.RepoRequirement repo) {
        return new PipeUnitMaterialSpec(
                template,
                standards == null ? EMPTY : standards,
                requiredInputs == null ? EMPTY : requiredInputs,
                optionalInputs == null ? EMPTY : optionalInputs,
                contexts == null ? EMPTY : contexts,
                formal,
                confirm,
                root,
                repo);
    }

    private static final Map<String, PipeUnitMaterialSpec> BY_TASK = Map.ofEntries(
            Map.entry("REVIEW_PRE", s(
                    "原始需求评估模板-业务.md", EMPTY,
                    List.of("原始需求.md"), EMPTY,
                    List.of("归档/需求"),
                    "原始需求-业务评估.md", "原始需求-业务确认.md",
                    RAW, NONE)),
            Map.entry("REVIEW_PRE_TECH", s(
                    "原始需求评估模板-技术.md", EMPTY,
                    List.of("原始需求.md"), EMPTY,
                    List.of("归档/需求"),
                    "原始需求-技术评估.md", "原始需求-技术确认.md",
                    RAW, NONE)),
            Map.entry("GEN_PRD", s(
                    "需求设计PRD模板.md", EMPTY,
                    List.of("原始需求.md"), EMPTY,
                    // $VER_REQ = wiki/{产品}/{计划版本}/{需求}（发布目录，含初审产出等）
                    List.of("$VER_REQ", "归档/需求"),
                    "产品需求说明书.md", "产品需求说明书-确认.md",
                    VER, NONE)),
            Map.entry("GEN_ARCH", s(
                    "架构设计文档模板.md", EMPTY,
                    List.of("产品需求说明书.md"), EMPTY,
                    List.of("归档/架构设计"),
                    "架构设计文档.md", "架构设计文档-确认.md",
                    VER, NONE)),
            Map.entry("GEN_UI", s(
                    null, List.of("UI设计规范.md"),
                    List.of("产品需求说明书.md"), EMPTY,
                    List.of("归档/UI设计"),
                    "UI设计.html", "UI设计-确认.md",
                    VER, NONE)),
            Map.entry("REVIEW_PRD_DEV", s(
                    "评审模板.md", EMPTY,
                    List.of("产品需求说明书.md"), EMPTY,
                    List.of("归档/需求"),
                    "PRD评审-研发.md", "PRD评审-研发-确认.md",
                    VER, BIZ_REQUIRED)),
            Map.entry("REVIEW_PRD_QA", s(
                    "评审模板.md", EMPTY,
                    List.of("产品需求说明书.md"), EMPTY,
                    List.of("归档/需求"),
                    "PRD评审-测试.md", "PRD评审-测试-确认.md",
                    VER, NONE)),
            Map.entry("REVIEW_ARCH_DEV", s(
                    "评审模板.md", EMPTY,
                    List.of("产品需求说明书.md", "架构设计文档.md"), EMPTY,
                    List.of("归档/架构设计"),
                    "架构设计评审-研发.md", "架构设计评审-研发-确认.md",
                    VER, BIZ_REQUIRED)),
            Map.entry("REVIEW_ARCH_QA", s(
                    "评审模板.md", EMPTY,
                    List.of("产品需求说明书.md", "架构设计文档.md"), EMPTY,
                    List.of("归档/架构设计"),
                    "架构设计评审-测试.md", "架构设计评审-测试-确认.md",
                    VER, NONE)),
            Map.entry("REVIEW_UI", s(
                    "评审模板.md", List.of("UI设计规范.md"),
                    List.of("产品需求说明书.md", "UI设计.html"), EMPTY,
                    List.of("归档/UI设计"),
                    "UI设计评审-产品.md", "UI设计评审-产品-确认.md",
                    VER, NONE)),
            Map.entry("REVIEW_UI_DEV", s(
                    "评审模板.md", EMPTY,
                    List.of("产品需求说明书.md", "UI设计.html"), EMPTY,
                    List.of("归档/UI设计"),
                    "UI设计评审-研发.md", "UI设计评审-研发-确认.md",
                    VER, BIZ_REQUIRED)),
            Map.entry("REVIEW_UI_QA", s(
                    "评审模板.md", EMPTY,
                    List.of("产品需求说明书.md", "UI设计.html"), EMPTY,
                    List.of("归档/UI设计"),
                    "UI设计评审-测试.md", "UI设计评审-测试-确认.md",
                    VER, NONE)),
            Map.entry("REVISE_PRD", s(
                    null, EMPTY,
                    List.of("产品需求说明书.md", "PRD合并评审.md"), EMPTY,
                    List.of("归档/需求"),
                    "产品需求说明书.md", "修订PRD-确认.md",
                    VER, NONE)),
            Map.entry("REVISE_ARCH", s(
                    null, EMPTY,
                    List.of("架构设计文档.md", "架构合并评审.md"), EMPTY,
                    List.of("归档/架构设计"),
                    "架构设计文档.md", "修订架构-确认.md",
                    VER, NONE)),
            Map.entry("REVISE_UI", s(
                    null, List.of("UI设计规范.md"),
                    List.of("UI设计.html", "UI合并评审.md"), EMPTY,
                    List.of("归档/UI设计"),
                    "UI设计.html", "修订UI-确认.md",
                    VER, NONE)),
            Map.entry("GEN_DETAIL", s(
                    "详细设计文档模板.md", EMPTY,
                    List.of("产品需求说明书.md"),
                    List.of("架构设计文档.md", "UI设计.html"),
                    List.of("归档"),
                    "详细设计文档.md", "详细设计-确认.md",
                    VER, BIZ_REQUIRED)),
            Map.entry("GEN_CASES", s(
                    "需求测试用例.md", EMPTY,
                    List.of("产品需求说明书.md"), EMPTY,
                    List.of("归档"),
                    "需求测试用例.md", "测试用例-确认.md",
                    VER, BIZ_REQUIRED)),
            Map.entry("REVIEW_DETAIL_ARCH", s(
                    "评审模板.md", EMPTY,
                    List.of("详细设计文档.md"), List.of("架构设计文档.md"),
                    List.of("归档"),
                    "详细设计评审-架构.md", "详细设计评审-架构-确认.md",
                    VER, NONE)),
            Map.entry("REVIEW_DETAIL_PM", s(
                    "评审模板.md", EMPTY,
                    List.of("详细设计文档.md", "产品需求说明书.md"), EMPTY,
                    List.of("归档"),
                    "详细设计评审-产品.md", "详细设计评审-产品-确认.md",
                    VER, NONE)),
            Map.entry("REVIEW_DETAIL_QA", s(
                    "评审模板.md", EMPTY,
                    List.of("详细设计文档.md", "产品需求说明书.md"), List.of("架构设计文档.md"),
                    List.of("归档"),
                    "详细设计评审-测试.md", "详细设计评审-测试-确认.md",
                    VER, NONE)),
            Map.entry("REVIEW_CASE_ARCH", s(
                    "评审模板.md", EMPTY,
                    List.of("需求测试用例.md", "产品需求说明书.md"), List.of("架构设计文档.md"),
                    List.of("归档"),
                    "测试用例评审-架构.md", "测试用例评审-架构-确认.md",
                    VER, NONE)),
            Map.entry("REVIEW_CASE_PM", s(
                    "评审模板.md", EMPTY,
                    List.of("需求测试用例.md", "产品需求说明书.md"), EMPTY,
                    List.of("归档"),
                    "测试用例评审-产品.md", "测试用例评审-产品-确认.md",
                    VER, NONE)),
            Map.entry("REVIEW_CASE_DEV", s(
                    "评审模板.md", EMPTY,
                    List.of("需求测试用例.md", "产品需求说明书.md", "详细设计文档.md"),
                    List.of("架构设计文档.md"),
                    List.of("归档"),
                    "测试用例评审-研发.md", "测试用例评审-研发-确认.md",
                    VER, NONE)),
            Map.entry("REVISE_DETAIL", s(
                    null, EMPTY,
                    List.of("详细设计文档.md", "详设合并评审.md", "产品需求说明书.md"),
                    List.of("架构设计文档.md"),
                    List.of("归档"),
                    "详细设计文档.md", "修订详设-确认.md",
                    VER, BIZ_REQUIRED)),
            Map.entry("REVISE_CASES", s(
                    null, EMPTY,
                    List.of("需求测试用例.md", "用例合并评审.md", "产品需求说明书.md"),
                    List.of("架构设计文档.md"),
                    List.of("归档"),
                    "需求测试用例.md", "修订用例-确认.md",
                    VER, BIZ_OPTIONAL)),
            Map.entry("GEN_CODING", s(
                    "编码完成报告模板.md",
                    CODING_STANDARDS_DIR,
                    List.of("详细设计文档.md", "产品需求说明书.md"),
                    List.of("架构设计文档.md"),
                    List.of("归档"),
                    "编码完成报告.md", "编码-确认.md",
                    VER, BIZ_REQUIRED)),
            Map.entry("GEN_CODE_REVIEW", s(
                    null, CODING_STANDARDS_DIR, EMPTY, EMPTY, List.of("归档"),
                    "代码评审报告.md", "代码评审报告-确认.md",
                    VER, BIZ_REQUIRED)),
            Map.entry("GEN_INT_TEST", s(
                    "集成测试报告模板.md",
                    CODING_STANDARDS_DIR,
                    List.of("需求测试用例.md", "产品需求说明书.md", "编码完成报告.md"),
                    List.of("架构设计文档.md"),
                    List.of("归档"),
                    "集成测试报告.md", "集成测试-确认.md",
                    VER, TEST_REQUIRED)),
            Map.entry("GEN_ISSUE_ANALYSIS", s(
                    "问题原因分析模板.md", EMPTY,
                    List.of("问题单.md"), EMPTY,
                    List.of("归档"),
                    "问题原因分析.md", "问题分析-确认.md",
                    ISSUE, NONE)),
            Map.entry("GEN_ISSUE_FIX", s(
                    null,
                    CODING_STANDARDS_DIR,
                    List.of("问题原因分析.md"), EMPTY,
                    List.of("归档"),
                    "问题修复报告.md", "问题修复-确认.md",
                    ISSUE, BIZ_REQUIRED)),
            Map.entry("GEN_ISSUE_CODE_REVIEW", s(
                    null, CODING_STANDARDS_DIR, EMPTY, EMPTY, List.of("归档"),
                    "代码评审报告.md", "代码评审报告-确认.md",
                    ISSUE, BIZ_REQUIRED)),
            Map.entry("GEN_ISSUE_INT_TEST", s(
                    "集成测试报告模板.md",
                    CODING_STANDARDS_DIR,
                    EMPTY, EMPTY,
                    List.of("归档"),
                    "集成测试报告.md", "集成测试-确认.md",
                    ISSUE, TEST_REQUIRED))
    );

    public static PipeUnitMaterialSpec require(String taskTypeCode) {
        PipeUnitMaterialSpec spec = BY_TASK.get(taskTypeCode == null ? "" : taskTypeCode.trim().toUpperCase());
        if (spec == null) {
            // 未知任务类型：最小默认可跑 VER + 无仓，正式文件用任务码
            String code = taskTypeCode == null ? "UNIT" : taskTypeCode.trim();
            return s(null, EMPTY, EMPTY, EMPTY, List.of("归档"),
                    code + ".md", code + "-确认.md", VER, NONE);
        }
        return spec;
    }
}
