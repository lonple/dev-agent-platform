package com.lonple.dap.server.modules.review.review;

import com.lonple.dap.server.modules.review.review.model.ReviewIssue;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ResultMerger {

    public List<ReviewIssue> merge(List<List<ReviewIssue>> batches) {
        Map<String, ReviewIssue> uniq = new LinkedHashMap<>();
        int seq = 0;
        for (List<ReviewIssue> batch : batches) {
            if (batch == null) {
                continue;
            }
            for (ReviewIssue issue : batch) {
                if (issue == null) {
                    continue;
                }
                String key = dedupeKey(issue);
                if (uniq.containsKey(key)) {
                    continue;
                }
                if (!StringUtils.hasText(issue.getId())) {
                    issue.setId("M" + (++seq));
                }
                uniq.put(key, issue);
            }
        }
        List<ReviewIssue> out = new ArrayList<>(uniq.values());
        out.sort(Comparator.comparingInt(a -> severityOrder(a.getSeverity())));
        return out;
    }

    private static String dedupeKey(ReviewIssue issue) {
        String path = issue.getLocation() != null && StringUtils.hasText(issue.getLocation().getPath())
                ? issue.getLocation().getPath().trim().toLowerCase(Locale.ROOT) : "";
        Integer line = issue.getLocation() != null ? issue.getLocation().getStartLine() : null;
        String title = issue.getTitle() == null ? "" : issue.getTitle().trim().toLowerCase(Locale.ROOT);
        return path + "|" + line + "|" + title;
    }

    private static int severityOrder(String s) {
        if (s == null) {
            return 9;
        }
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }
}
