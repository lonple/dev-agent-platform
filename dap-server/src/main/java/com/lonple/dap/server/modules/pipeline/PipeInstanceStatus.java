package com.lonple.dap.server.modules.pipeline;

import java.util.Set;

public enum PipeInstanceStatus {
    RUNNING,
    WAITING_HUMAN,
    SUSPENDED,
    CANCELLED,
    FAILED,
    DONE;

    public static final Set<String> ACTIVE = Set.of(
            RUNNING.name(), WAITING_HUMAN.name(), SUSPENDED.name(), FAILED.name());
}
