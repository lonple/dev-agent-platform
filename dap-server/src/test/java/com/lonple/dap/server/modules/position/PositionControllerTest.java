package com.lonple.dap.server.modules.position;

import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PositionControllerTest {
    private final PositionService service = mock(PositionService.class);
    private final PositionController controller = new PositionController(service);

    private void permissions(String... permissions) {
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setCurrentTenantId(10L);
        user.setPermissions(Set.of(permissions));
        UserContext.set(user);
    }

    @AfterEach
    void cleanup() { UserContext.clear(); }

    @Test
    void promptViewerCanReachRoleEntry() {
        permissions("prompt:view");
        assertDoesNotThrow(() -> controller.list(false));
        verify(service).list(false);
    }

    @Test
    void positionViewerRetainsAccess() {
        permissions("position:view");
        assertDoesNotThrow(() -> controller.list(false));
        verify(service).list(false);
    }

    @Test
    void unrelatedPermissionsDoNotGrantAccess() {
        permissions("product:view");
        assertThrows(BizException.class, () -> controller.list(false));
        verifyNoInteractions(service);
    }
}
