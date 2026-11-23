package com.lonple.dap.server.modules.position;

import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.position.dto.PositionSaveRequest;
import com.lonple.dap.server.modules.position.entity.SysPosition;
import com.lonple.dap.server.modules.position.mapper.PositionMapper;
import com.lonple.dap.server.modules.position.mapper.PositionSkillMapper;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PositionServiceTest {
    private final PositionMapper mapper = mock(PositionMapper.class);
    private final PositionSkillMapper skills = mock(PositionSkillMapper.class);
    private final PositionService service = new PositionService(mapper, skills);
    private SysPosition existing;

    @BeforeEach
    void setup() {
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setCurrentTenantId(10L);
        UserContext.set(user);
        existing = new SysPosition();
        existing.setId(1L);
        existing.setTenantId(10L);
        existing.setCode("DEV");
        existing.setName("研发");
        when(mapper.selectById(1L)).thenReturn(existing);
    }

    @AfterEach
    void cleanup() { UserContext.clear(); }

    private PositionSaveRequest request(String code) {
        PositionSaveRequest request = new PositionSaveRequest();
        request.setCode(code);
        request.setName("后端研发");
        request.setDescription("负责后端任务");
        return request;
    }

    @Test
    void builtinCodeCannotBeChangedOrCleared() {
        assertThrows(BizException.class, () -> service.update(1L, request("OTHER")));
        assertThrows(BizException.class, () -> service.update(1L, request(null)));
        verify(mapper, never()).updateById(any(SysPosition.class));
        assertEquals("DEV", existing.getCode());
    }

    @Test
    void builtinCannotBeDeletedAndSkillsArePreserved() {
        assertThrows(BizException.class, () -> service.delete(1L));
        verifyNoInteractions(skills);
        verify(mapper, never()).deleteById(1L);
    }

    @Test
    void builtinDisplayInformationCanBeEdited() {
        when(mapper.selectCount(any())).thenReturn(0L);
        service.update(1L, request("dev"));
        assertEquals("DEV", existing.getCode());
        assertEquals("后端研发", existing.getName());
        assertEquals("负责后端任务", existing.getDescription());
        verify(mapper).updateById(existing);
    }

    @Test
    void legacyPositionCannotBeReclassifiedAsBuiltin() {
        existing.setCode("CUSTOM");
        assertThrows(BizException.class, () -> service.update(1L, request("QA")));
        verify(mapper, never()).updateById(any(SysPosition.class));
    }

    @Test
    void initializationPreservesExistingPositions() {
        when(mapper.selectOne(any())).thenReturn(existing);
        service.ensureBuiltins();
        assertEquals("研发", existing.getName());
        verify(mapper, never()).insert(any(SysPosition.class));
        verify(mapper, never()).updateById(any(SysPosition.class));
    }

    @Test
    void initializationCreatesFiveBuiltinRolesInCurrentTenant() {
        service.ensureBuiltins();
        ArgumentCaptor<SysPosition> saved = ArgumentCaptor.forClass(SysPosition.class);
        verify(mapper, times(5)).insert(saved.capture());
        assertEquals(Set.of("PM", "ARCH", "DEV", "QA", "UI"), saved.getAllValues().stream().map(SysPosition::getCode).collect(Collectors.toSet()));
        assertTrue(saved.getAllValues().stream().allMatch(p -> Long.valueOf(10L).equals(p.getTenantId())));
    }

    @Test
    void anotherTenantsPositionCannotBeEdited() {
        existing.setTenantId(20L);
        assertThrows(BizException.class, () -> service.update(1L, request("DEV")));
        verify(mapper, never()).updateById(any(SysPosition.class));
    }
}
