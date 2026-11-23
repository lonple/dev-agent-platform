package com.lonple.dap.server.modules.review.review.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ReviewIssue {
    private String id;
    private String severity;
    private String title;
    private String description;
    private String suggestion;
    private Location location;
    private String evidence;
    private String evidenceType;
    private List<String> relatedKnowledgeIds = new ArrayList<>();
    /** 分片 id，如 backend-order#batch2 */
    private String shardId;
    private String shardKind;
    private String moduleKey;
    /** 行号是否已对齐到源码；false 表示未定位 */
    private Boolean located;
    private String locateNote;

    @Data
    public static class Location {
        private String path;
        private Integer startLine;
        private Integer endLine;
        private String symbol;
    }
}
