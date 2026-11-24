package com.lonple.dap.server.modules.sandbox;

import com.lonple.dap.server.modules.product.entity.SysProduct;
import com.lonple.dap.server.modules.product.mapper.ProductMapper;
import com.lonple.dap.server.modules.sandbox.entity.*;
import com.lonple.dap.server.modules.sandbox.mapper.*;
import com.lonple.dap.server.security.LoginUser;
import com.lonple.dap.server.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SandboxLeaseRecoveryTest {
    @TempDir Path worktree;
    private final SbLeaseMapper leases = mock(SbLeaseMapper.class);
    private final SbNodeMapper nodes = mock(SbNodeMapper.class);
    private final SbProductResourceMapper resources = mock(SbProductResourceMapper.class);
    private final ProductMapper products = mock(ProductMapper.class);
    private final SandboxManagerClient manager = mock(SandboxManagerClient.class);
    private final SandboxPortAllocator ports = mock(SandboxPortAllocator.class);
    private final SandboxRuntimeInstantator instantator = mock(SandboxRuntimeInstantator.class);
    private final SandboxLeaseService service = new SandboxLeaseService(new SandboxProperties(), nodes,
            resources, leases, products, manager, ports, instantator, new ObjectMapper(), mock(SandboxProfileService.class));

    @AfterEach void clearContext() { UserContext.clear(); }

    private SbLease existing() {
        var user = new LoginUser(); user.setCurrentTenantId(3L); UserContext.set(user);
        when(products.selectById(4L)).thenReturn(new SysProduct());
        var lease = new SbLease();
        lease.setId(26L); lease.setResourceId(2L); lease.setStatus("ATTACHED");
        lease.setSessionId(9173L); lease.setWorktreeHostPath(worktree.toString());
        lease.setExpiresAt(LocalDateTime.of(2026, 11, 24, 10, 0));
        lease.setRuntimeInstanceJson("""
            {"nodeCode":"original-node","image":"original-image",
             "env":{"DAP_TEST_API_URL":"http://127.0.0.1:8200"},
             "ports":{"api":{"host":18501,"container":8200}},
             "cacheMounts":{"gradle":true,"workspace:dap-test/node_modules":true}}
            """);
        when(leases.selectOne(any())).thenReturn(lease);
        var resource = new SbProductResource(); resource.setNodeId(1L);
        when(resources.selectById(2L)).thenReturn(resource);
        var node = new SbNode(); node.setNodeCode("changed-resource-node");
        when(nodes.selectById(1L)).thenReturn(node);
        return lease;
    }

    private SandboxLeaseService.AcquireReq request() {
        var req = new SandboxLeaseService.AcquireReq();
        req.productId = 4L; req.sessionId = 9174L; req.jobId = 318L;
        req.worktreeHostPath = worktree.toString(); return req;
    }

    @Test void reattachesFromSnapshotBeforeRebindingSessionWithoutAllocatingPorts() {
        var lease = existing();
        service.acquire(request());
        var order = inOrder(manager, leases);
        order.verify(manager).attach(26L, "original-node", "original-image", worktree.toString(),
                Map.of("DAP_TEST_API_URL", "http://127.0.0.1:8200"),
                List.of(Map.of("host", 18501, "container", 8200)),
                Map.of("gradle", "true", "workspace:dap-test/node_modules", "true"));
        order.verify(leases).updateById(lease);
        assertEquals(9174L, lease.getSessionId());
        verifyNoInteractions(ports, instantator);
    }

    @Test void failedReattachDoesNotRenewOrRebindTheLease() {
        var lease = existing(); var expiry = lease.getExpiresAt();
        doThrow(new IllegalStateException("manager unavailable")).when(manager)
                .attach(anyLong(), anyString(), anyString(), anyString(), anyMap(), anyList(), anyMap());
        assertThrows(IllegalStateException.class, () -> service.acquire(request()));
        assertEquals(expiry, lease.getExpiresAt());
        assertEquals(9173L, lease.getSessionId());
        verify(leases, never()).updateById(any(SbLease.class));
        verifyNoInteractions(ports, instantator);
    }
}
