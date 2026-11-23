package com.lonple.dap.server.modules.pipeline;

import java.util.Locale;
import java.util.Map;

/**
 * 各评审轨 → 合并评审文件名 / 修订单元 / 修订任务类型。
 * 文件名与 {@link PipeUnitMaterialCatalog} 中 REVISE_* 必填输入保持一致。
 */
public final class PipeTrackMergeCatalog {

    public record TrackSpec(
            String trackCode,
            String label,
            String reviewNodeCode,
            String reviseUnitCode,
            String reviseTaskType,
            String mergeFileName,
            String targetDocName) {
    }

    private static final Map<String, TrackSpec> BY_TRACK = Map.of(
            "PRD", new TrackSpec("PRD", "需求设计PRD", "prd_review", "rev_prd", "REVISE_PRD",
                    "PRD合并评审.md", "产品需求说明书.md"),
            "ARCH", new TrackSpec("ARCH", "架构设计", "arch_review", "rev_arch", "REVISE_ARCH",
                    "架构合并评审.md", "架构设计文档.md"),
            "UI", new TrackSpec("UI", "UI设计", "ui_review", "rev_ui", "REVISE_UI",
                    "UI合并评审.md", "UI设计.html"),
            "DETAIL", new TrackSpec("DETAIL", "详细设计", "detail_review", "rev_detail", "REVISE_DETAIL",
                    "详设合并评审.md", "详细设计文档.md"),
            "CASES", new TrackSpec("CASES", "测试用例", "cases_review", "rev_cases", "REVISE_CASES",
                    "用例合并评审.md", "需求测试用例.md")
    );

    private static final Map<String, String> NODE_TO_TRACK = Map.of(
            "prd_review", "PRD",
            "arch_review", "ARCH",
            "ui_review", "UI",
            "detail_review", "DETAIL",
            "cases_review", "CASES"
    );

    private PipeTrackMergeCatalog() {
    }

    public static TrackSpec require(String trackCode) {
        TrackSpec s = of(trackCode);
        if (s == null) {
            throw new IllegalArgumentException("未知轨：" + trackCode);
        }
        return s;
    }

    public static TrackSpec of(String trackCode) {
        if (trackCode == null) {
            return null;
        }
        return BY_TRACK.get(trackCode.trim().toUpperCase(Locale.ROOT));
    }

    public static String trackOfReviewNode(String nodeCode) {
        if (nodeCode == null) {
            return null;
        }
        return NODE_TO_TRACK.get(nodeCode.trim());
    }
}
