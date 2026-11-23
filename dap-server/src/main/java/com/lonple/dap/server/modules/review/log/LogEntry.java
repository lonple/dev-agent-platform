package com.lonple.dap.server.modules.review.log;

import lombok.Data;

import java.util.Map;

@Data
public class LogEntry {
    private long id;
    private String time;
    private String level;
    private String step;
    private String message;
    private Map<String, Object> detail;
}
