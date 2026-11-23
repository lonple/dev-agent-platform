package com.lonple.dap.server.modules.review.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lonple.dap.server.modules.review.review.model.ReviewIssue;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ReviewSchemaValidator {
    private static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");
    private final ObjectMapper mapper;

    public ReviewSchemaValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<ReviewIssue> parseAndFilterIssues(JsonNode finalReviewNode) {
        List<ReviewIssue> out = new ArrayList<>();
        if (finalReviewNode == null || !finalReviewNode.has("issues") || !finalReviewNode.get("issues").isArray()) {
            return out;
        }
        int i = 0;
        for (JsonNode n : finalReviewNode.get("issues")) {
            i++;
            ReviewIssue issue = mapper.convertValue(n, ReviewIssue.class);
            if (issue == null) {
                continue;
            }
            if (!StringUtils.hasText(issue.getEvidence())) {
                continue;
            }
            if (!StringUtils.hasText(issue.getSeverity())) {
                continue;
            }
            String sev = issue.getSeverity().trim().toUpperCase(Locale.ROOT);
            if (!SEVERITIES.contains(sev)) {
                continue;
            }
            issue.setSeverity(sev);
            if (!StringUtils.hasText(issue.getId())) {
                issue.setId("I" + i);
            }
            if (!StringUtils.hasText(issue.getTitle())) {
                issue.setTitle("未命名问题");
            }
            out.add(issue);
        }
        out.sort(Comparator.comparingInt(a -> severityOrder(a.getSeverity())));
        return out;
    }

    private static int severityOrder(String s) {
        return switch (s) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }
}
