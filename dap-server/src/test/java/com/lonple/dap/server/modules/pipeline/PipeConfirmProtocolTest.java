package com.lonple.dap.server.modules.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PipeConfirmProtocolTest {
    @Test
    void preservesRecommendationAndAcceptsBothSuggestionFormats() throws Exception {
        var service = mock(PipeRuntimeService.class, CALLS_REAL_METHODS);
        var findings = new ObjectMapper().readTree("""
                [{"id":"F1","title":"Contract decision","suggestions":["Keep contract","Change contract"],"recommend":"Keep contract"},
                 {"id":"F2","title":"Legacy finding","suggestion":"Check environment"}]
                """);
        List<Map<String, Object>> result = ReflectionTestUtils.invokeMethod(service, "normalizeConfirmFindings", findings);
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("F1", result.getFirst().get("id"));
        assertEquals("Keep contract", result.getFirst().get("recommend"));
        assertTrue(result.getFirst().get("suggestion").toString().contains("Change contract"));
        assertEquals("Check environment", result.get(1).get("suggestion"));
    }
}
