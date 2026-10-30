package com.lonple.dap.server.modules.agenttool.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentSearchResultVO {
    /** ripgrep | line-scan */
    private String engine;
    private int scannedFiles;
    private int totalAllowedFiles;
    private boolean truncated;
    private List<AgentFileHitVO> hits = new ArrayList<>();
}
