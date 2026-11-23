package com.lonple.dap.server.modules.review.log;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class LogFile {
    private List<LogEntry> entries = new ArrayList<>();
    private long nextId = 1;
}
