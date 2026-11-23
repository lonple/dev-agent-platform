package com.lonple.dap.sandbox.manager.domain;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LeaseRoutes {

    private final Map<Long, Route> routes = new ConcurrentHashMap<>();

    public void put(Long leaseId, String nodeCode, String clientBaseUrl) {
        Route r = new Route();
        r.setLeaseId(leaseId);
        r.setNodeCode(nodeCode);
        r.setClientBaseUrl(clientBaseUrl.replaceAll("/$", ""));
        routes.put(leaseId, r);
    }

    public Route get(Long leaseId) {
        return routes.get(leaseId);
    }

    public Route require(Long leaseId) {
        Route r = routes.get(leaseId);
        if (r == null) {
            throw new IllegalStateException("SB_LEASE_NOT_ATTACHED");
        }
        return r;
    }

    public void remove(Long leaseId) {
        routes.remove(leaseId);
    }

    @Data
    public static class Route {
        private Long leaseId;
        private String nodeCode;
        private String clientBaseUrl;
    }
}
