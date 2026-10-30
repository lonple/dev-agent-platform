package com.lonple.dap.server.modules.chat;

import com.lonple.dap.server.common.BizException;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务会话可选类型：生成类 / 评审类（与流水线 AI 步对齐，供手动与自动共用）。
 */
public enum SessionTaskType {

    // —— 生成类 ——
    GEN_PRD("GEN", "需求分析", "PM"),
    GEN_ARCH("GEN", "架构设计", "ARCH"),
    GEN_UI("GEN", "UI设计", "PM"),
    GEN_DETAIL("GEN", "详细设计", "DEV"),
    GEN_CASES("GEN", "测试用例", "QA"),
    GEN_CODING("GEN", "开发编码", "DEV"),
    GEN_INT_TEST("GEN", "集成测试", "DEV"),

    // —— 评审类 ——
    REVIEW_PRE("REVIEW", "原始需求初审", "PM"),
    REVIEW_PRD_ARCH("REVIEW", "PRD架构师初审", "ARCH"),
    REVIEW_PRD_DEV("REVIEW", "需求分析评审(研发)", "DEV"),
    REVIEW_PRD_QA("REVIEW", "需求分析评审(测试)", "QA"),
    REVIEW_ARCH_DEV("REVIEW", "架构设计评审(研发)", "DEV"),
    REVIEW_ARCH_QA("REVIEW", "架构设计评审(测试)", "QA"),
    REVIEW_UI("REVIEW", "UI评审", "PM"),
    REVIEW_DETAIL_QA("REVIEW", "详细设计评审(测试)", "QA"),
    REVIEW_DETAIL_ARCH("REVIEW", "详细设计评审(架构)", "ARCH"),
    REVIEW_CASE_DEV("REVIEW", "测试用例评审(研发)", "DEV"),
    REVIEW_CASE_ARCH("REVIEW", "测试用例评审(架构)", "ARCH");

    private final String kind;
    private final String label;
    /** 默认岗位 code：PM / ARCH / DEV / QA */
    private final String defaultPositionCode;

    SessionTaskType(String kind, String label, String defaultPositionCode) {
        this.kind = kind;
        this.label = label;
        this.defaultPositionCode = defaultPositionCode;
    }

    public String getKind() {
        return kind;
    }

    public String getLabel() {
        return label;
    }

    public String getDefaultPositionCode() {
        return defaultPositionCode;
    }

    public boolean needsLocalCodeRepo() {
        return this == GEN_CODING || this == GEN_INT_TEST;
    }

    public static SessionTaskType fromCode(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BizException("请选择任务类型");
        }
        String c = code.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(t -> t.name().equals(c))
                .findFirst()
                .orElseThrow(() -> new BizException("任务类型无效：" + code));
    }

    /** 流水线步骤 code → 会话任务类型 */
    public static SessionTaskType fromPipeStepCode(String stepCode) {
        if (!StringUtils.hasText(stepCode)) {
            return null;
        }
        return switch (stepCode.trim()) {
            case "AI_PRE_REVIEW" -> REVIEW_PRE;
            case "GEN_PRD_V1" -> GEN_PRD;
            case "REVIEW_PRD_ARCH" -> REVIEW_PRD_ARCH;
            case "GEN_ARCH_V1" -> GEN_ARCH;
            case "GEN_UI_V1" -> GEN_UI;
            case "REVIEW_PRD_DEV" -> REVIEW_PRD_DEV;
            case "REVIEW_PRD_QA" -> REVIEW_PRD_QA;
            case "REVIEW_ARCH_DEV" -> REVIEW_ARCH_DEV;
            case "REVIEW_ARCH_QA" -> REVIEW_ARCH_QA;
            case "REVIEW_UI_PM" -> REVIEW_UI;
            case "GEN_DETAIL_V1" -> GEN_DETAIL;
            case "REVIEW_DETAIL_QA" -> REVIEW_DETAIL_QA;
            case "REVIEW_DETAIL_ARCH" -> REVIEW_DETAIL_ARCH;
            case "GEN_CASES_V1" -> GEN_CASES;
            case "REVIEW_CASE_DEV" -> REVIEW_CASE_DEV;
            case "REVIEW_CASE_ARCH" -> REVIEW_CASE_ARCH;
            case "DEV_CODING" -> GEN_CODING;
            case "INT_TEST" -> GEN_INT_TEST;
            default -> null;
        };
    }

    public static List<Map<String, String>> options() {
        return Arrays.stream(values()).map(t -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("code", t.name());
            m.put("name", t.getLabel());
            m.put("kind", t.getKind());
            m.put("defaultPositionCode", t.getDefaultPositionCode());
            return m;
        }).toList();
    }
}
