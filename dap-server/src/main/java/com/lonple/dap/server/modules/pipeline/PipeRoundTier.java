package com.lonple.dap.server.modules.pipeline;

/** 确认/循环轮次档位：24 / 48 / 96 */
public enum PipeRoundTier {
    T24(24),
    T48(48),
    T96(96);

    private final int maxRound;

    PipeRoundTier(int maxRound) {
        this.maxRound = maxRound;
    }

    public int getMaxRound() {
        return maxRound;
    }

    public static PipeRoundTier fromCode(String code) {
        if (code == null || code.isBlank()) {
            return T48;
        }
        try {
            return PipeRoundTier.valueOf(code.trim().toUpperCase());
        } catch (Exception e) {
            return T48;
        }
    }
}
