package com.lonple.dap.server.modules.chat;

import com.lonple.dap.server.common.BizException;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务会话可选类型：生成类 / 评审类（手动任务选用）。
 */
public enum SessionTaskType {

    // —— 生成类 ——
    GEN_PRD("GEN", "生成需求分析", "PM"),
    GEN_ARCH("GEN", "架构设计", "ARCH"),
    GEN_UI("GEN", "UI设计", "UI"),
    GEN_DETAIL("GEN", "详细设计", "DEV"),
    GEN_CASES("GEN", "测试用例", "QA"),
    GEN_CODING("GEN", "开发编码", "DEV"),
    /** 编码完成后、集成测试前的代码评审 */
    GEN_CODE_REVIEW("GEN", "代码评审", "DEV"),
    GEN_INT_TEST("GEN", "集成测试", "QA"),
    /** 问题单：定位分析 */
    GEN_ISSUE_ANALYSIS("GEN", "问题分析", "DEV"),
    /** 问题单：按分析结果修复编码 */
    GEN_ISSUE_FIX("GEN", "问题修复", "DEV"),
    /** 问题修复后、存量集成测试前的代码评审 */
    GEN_ISSUE_CODE_REVIEW("GEN", "问题代码评审", "DEV"),
    /** 问题单：存量集成测试 */
    GEN_ISSUE_INT_TEST("GEN", "问题集成测试", "QA"),
    /** 按 PRD 评审结果修订需求分析（先判定是否需改） */
    REVISE_PRD("GEN", "修订需求分析", "PM"),
    /** 按架构评审结果修订架构设计 */
    REVISE_ARCH("GEN", "修订架构设计", "ARCH"),
    /** 按 UI 评审结果修订 UI 设计 */
    REVISE_UI("GEN", "修订UI设计", "UI"),
    /** 按详设评审结果修订详细设计 */
    REVISE_DETAIL("GEN", "修订详细设计", "DEV"),
    /** 按用例评审结果修订测试用例 */
    REVISE_CASES("GEN", "修订测试用例", "QA"),

    // —— 评审类 ——
    /** 原始需求 · 业务初审（产品经理） */
    REVIEW_PRE("REVIEW", "原始需求业务初审", "PM"),
    /** 原始需求 · 技术初审（架构师） */
    REVIEW_PRE_TECH("REVIEW", "原始需求技术初审", "ARCH"),
    REVIEW_PRD_DEV("REVIEW", "需求设计PRD评审(研发)", "DEV"),
    REVIEW_PRD_QA("REVIEW", "需求设计PRD评审(测试)", "QA"),
    REVIEW_ARCH_DEV("REVIEW", "架构设计评审(研发)", "DEV"),
    REVIEW_ARCH_QA("REVIEW", "架构设计评审(测试)", "QA"),
    REVIEW_UI("REVIEW", "UI设计评审(产品)", "PM"),
    REVIEW_UI_DEV("REVIEW", "UI设计评审(研发)", "DEV"),
    REVIEW_UI_QA("REVIEW", "UI设计评审(测试)", "QA"),
    REVIEW_DETAIL_ARCH("REVIEW", "详细设计评审(架构)", "ARCH"),
    REVIEW_DETAIL_PM("REVIEW", "详细设计评审(产品)", "PM"),
    REVIEW_DETAIL_QA("REVIEW", "详细设计评审(测试)", "QA"),
    REVIEW_CASE_ARCH("REVIEW", "测试用例评审(架构)", "ARCH"),
    REVIEW_CASE_PM("REVIEW", "测试用例评审(产品)", "PM"),
    REVIEW_CASE_DEV("REVIEW", "测试用例评审(研发)", "DEV");

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
        return this == GEN_CODING
                || this == GEN_CODE_REVIEW
                || this == GEN_INT_TEST
                || this == GEN_ISSUE_FIX
                || this == GEN_ISSUE_CODE_REVIEW
                || this == GEN_ISSUE_INT_TEST
                || this == GEN_DETAIL
                || this == GEN_CASES;
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
