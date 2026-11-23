package com.lonple.dap.server.modules.sandbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.sandbox.entity.SbPortAllocation;
import com.lonple.dap.server.modules.sandbox.mapper.SbPortAllocationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SandboxPortAllocator {

    private final SbPortAllocationMapper portMapper;
    private final SandboxProperties props;

    @Transactional
    public int allocate(Long leaseId, Long nodeId, String serviceKey, int poolStart, int poolEnd) {
        int start = poolStart > 0 ? poolStart : props.getBusinessPortStart();
        int end = poolEnd > 0 ? poolEnd : props.getBusinessPortEnd();
        if (end < start) {
            throw new BizException("端口池配置非法");
        }
        Set<Integer> used = new HashSet<>();
        List<SbPortAllocation> active = portMapper.selectList(new LambdaQueryWrapper<SbPortAllocation>()
                .eq(SbPortAllocation::getStatus, "ACTIVE"));
        for (SbPortAllocation a : active) {
            if (a.getHostPort() != null) {
                used.add(a.getHostPort());
            }
        }
        for (int p = start; p <= end; p++) {
            if (!used.contains(p)) {
                SbPortAllocation row = new SbPortAllocation();
                row.setLeaseId(leaseId);
                row.setNodeId(nodeId);
                row.setServiceKey(serviceKey);
                row.setHostPort(p);
                row.setStatus("ACTIVE");
                row.setCreatedAt(LocalDateTime.now());
                row.setUpdatedAt(LocalDateTime.now());
                portMapper.insert(row);
                return p;
            }
        }
        throw new BizException(409, "SB_PORT_EXHAUSTED: 业务端口池已满");
    }

    @Transactional
    public void releaseByLease(Long leaseId) {
        if (leaseId == null) {
            return;
        }
        List<SbPortAllocation> rows = portMapper.selectList(new LambdaQueryWrapper<SbPortAllocation>()
                .eq(SbPortAllocation::getLeaseId, leaseId)
                .eq(SbPortAllocation::getStatus, "ACTIVE"));
        LocalDateTime now = LocalDateTime.now();
        for (SbPortAllocation row : rows) {
            row.setStatus("RELEASED");
            row.setUpdatedAt(now);
            portMapper.updateById(row);
        }
    }
}
