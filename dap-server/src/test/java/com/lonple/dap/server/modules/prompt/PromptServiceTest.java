package com.lonple.dap.server.modules.prompt;

import com.lonple.dap.server.modules.position.PositionService;
import com.lonple.dap.server.modules.position.entity.SysPosition;
import com.lonple.dap.server.modules.prompt.entity.SysPrompt;
import com.lonple.dap.server.modules.prompt.mapper.PromptMapper;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PromptServiceTest {
    private final PromptMapper mapper = mock(PromptMapper.class);
    private final PositionService positions = mock(PositionService.class);
    private final PromptService service = new PromptService(mapper, positions);

    @BeforeEach
    void setup() {
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setCurrentTenantId(10L);
        UserContext.set(user);
        when(positions.findByCode(anyString())).thenAnswer(call -> {
            SysPosition position = new SysPosition();
            position.setId(1L);
            position.setCode(call.getArgument(0));
            return position;
        });
    }

    @AfterEach
    void cleanup() { UserContext.clear(); }

    @Test
    void initializationPreservesEveryExistingPrompt() {
        SysPrompt existing = new SysPrompt();
        existing.setContent("tenant customized prompt");
        existing.setName("custom name");
        when(mapper.selectOne(any())).thenReturn(existing);

        var result = service.ensurePreReviewPrompts();

        assertTrue(((List<?>) result.get("created")).isEmpty());
        assertTrue(((List<?>) result.get("skipped")).contains("DEV+GEN_ISSUE_FIX"));
        assertEquals("tenant customized prompt", existing.getContent());
        assertEquals("custom name", existing.getName());
        verify(mapper, never()).updateById(any(SysPrompt.class));
        verify(mapper, never()).insert(any(SysPrompt.class));
    }

    @Test
    void initializationCreatesNonEmptyDefaultsForBothWorkflows() {
        when(mapper.selectCount(any())).thenReturn(0L);

        var result = service.ensurePreReviewPrompts();

        ArgumentCaptor<SysPrompt> saved = ArgumentCaptor.forClass(SysPrompt.class);
        verify(mapper, atLeastOnce()).insert(saved.capture());
        assertEquals(((List<?>) result.get("created")).size(), saved.getAllValues().size());
        assertTrue(saved.getAllValues().stream().anyMatch(p -> "GEN_CODING".equals(p.getTaskTypeCode())));
        assertTrue(saved.getAllValues().stream().anyMatch(p -> "GEN_ISSUE_FIX".equals(p.getTaskTypeCode())));
        for (SysPrompt prompt : saved.getAllValues()) {
            assertNotNull(prompt.getContent(), prompt.getCode());
            assertFalse(prompt.getContent().isBlank(), prompt.getCode());
            assertTrue(prompt.getContent().contains("协作协议 V2"), prompt.getCode());
            assertTrue(prompt.getContent().contains("\"suggestions\""), prompt.getCode());
            assertTrue(prompt.getContent().contains("# 岗位与阶段"), prompt.getCode());
            assertEquals(10L, prompt.getTenantId());
        }
        verify(mapper, never()).updateById(any(SysPrompt.class));
    }
}
