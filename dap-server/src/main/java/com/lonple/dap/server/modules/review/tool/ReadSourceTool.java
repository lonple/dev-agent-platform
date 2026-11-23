package com.lonple.dap.server.modules.review.tool;

import com.lonple.dap.server.modules.review.review.ContextReader;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 读源码工具。评审多轮协议和后续 MCP 都走这里，不把路径规则散落在 Worker 里。
 */
@Component
public class ReadSourceTool {
    public static final String NAME = "read_source";

    private final ContextReader contextReader;

    public ReadSourceTool(ContextReader contextReader) {
        this.contextReader = contextReader;
    }

    public String read(Path workTree, List<ContextReader.Target> targets) {
        return contextReader.readTargets(workTree, targets);
    }
}
