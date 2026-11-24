package com.lonple.dap.server.modules.pipeline;

import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.agenttool.dto.CodeRepoDtos;
import com.lonple.dap.server.modules.sandbox.SandboxCodeBridge;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockIntTestRunnerTest {
    private final SandboxCodeBridge bridge = mock(SandboxCodeBridge.class);
    private final StockIntTestRunner runner = new StockIntTestRunner(bridge);

    @Test
    void missingSandboxStopsWithoutRunningLocalTests() {
        assertThrows(BizException.class, () -> runner.run(9166L, List.of()));
        verify(bridge, never()).runAction(anyLong(), anyString(), anyMap());
    }

    @Test
    void preparationFailureDoesNotBecomeTestFailureForAgentAnalysis() {
        when(bridge.shouldRouteMutating(9166L)).thenReturn(true);
        when(bridge.hasConfiguredBuild(9166L)).thenReturn(true);
        when(bridge.runAction(9166L, "build", Map.of())).thenReturn(result(1, false));
        assertThrows(BizException.class, () -> runner.run(9166L, List.of()));
        verify(bridge, never()).runAction(9166L, "test", Map.of());
    }

    @Test
    void buildsAndStartsSameSandboxBeforeTesting() {
        when(bridge.shouldRouteMutating(9166L)).thenReturn(true);
        when(bridge.hasConfiguredBuild(9166L)).thenReturn(true);
        when(bridge.runAction(9166L, "build", Map.of())).thenReturn(result(0, false));
        when(bridge.runAction(9166L, "start_dev", Map.of())).thenReturn(result(-1, true));
        when(bridge.runAction(9166L, "test", Map.of())).thenReturn(result(0, false));
        assertTrue(runner.run(9166L, List.of()).passed());
        var order = inOrder(bridge);
        order.verify(bridge).runAction(9166L, "build", Map.of());
        order.verify(bridge).runAction(9166L, "start_dev", Map.of());
        order.verify(bridge).runAction(9166L, "test", Map.of());
    }

    @Test
    void startOnlyProfileDoesNotTriggerAutoDetectedBuild() {
        when(bridge.shouldRouteMutating(9166L)).thenReturn(true);
        when(bridge.runAction(9166L, "start_dev", Map.of())).thenReturn(result(-1, true));
        when(bridge.runAction(9166L, "test", Map.of())).thenReturn(result(0, false));
        assertTrue(runner.run(9166L, List.of()).passed());
        verify(bridge, never()).runAction(9166L, "build", Map.of());
    }

    private static CodeRepoDtos.ActionResult result(int exitCode, boolean background) {
        var result = new CodeRepoDtos.ActionResult();
        result.setExitCode(exitCode);
        result.setBackground(background);
        return result;
    }
}
