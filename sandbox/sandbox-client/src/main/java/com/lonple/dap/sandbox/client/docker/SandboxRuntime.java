package com.lonple.dap.sandbox.client.docker;

import com.lonple.dap.sandbox.client.config.ClientProps;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxRuntime {

    private final ClientProps props;
    private final DockerCli dockerCli;
    private final Map<Long, Instance> instances = new ConcurrentHashMap<>();
    private final AtomicInteger portCursor = new AtomicInteger();

    public Map<String, Object> health() {
        boolean ok = dockerCli.dockerOk();
        return Map.of(
                "status", "UP",
                "nodeCode", props.getNodeCode(),
                "dockerOk", ok,
                "dockerVersion", dockerCli.dockerVersion(),
                "activeSandboxes", instances.size());
    }

    public Instance require(Long leaseId) {
        Instance i = instances.get(leaseId);
        if (i == null) {
            throw new IllegalStateException("SB_LEASE_NOT_ATTACHED");
        }
        return i;
    }

    public synchronized Instance attach(
            Long leaseId,
            String image,
            String worktreeHostPath,
            Map<String, String> env,
            List<Map<String, Object>> portPublishes,
            Map<String, String> cacheMounts) throws Exception {
        if (leaseId == null) {
            throw new IllegalArgumentException("leaseId required");
        }
        if (instances.containsKey(leaseId)) {
            return instances.get(leaseId);
        }
        if (!StringUtils.hasText(worktreeHostPath) || !Files.isDirectory(Path.of(worktreeHostPath))) {
            throw new IllegalArgumentException("worktreeHostPath not a directory: " + worktreeHostPath);
        }

        String volumePath = toDockerVolumePath(worktreeHostPath);
        String worktreeKey = worktreeKey(volumePath);

        // 内存里已有同 worktree 实例：改绑到新 leaseId，不新建容器
        Instance memHit = findInMemoryByWorktreeKey(worktreeKey);
        if (memHit != null) {
            return rebindInstance(memHit, leaseId, worktreeKey);
        }

        // Docker 已有同 worktree 运行中容器：收养，不 docker run
        Instance dockerHit = findRunningByWorktreeKey(worktreeKey);
        if (dockerHit != null) {
            dockerHit.setLeaseId(leaseId);
            dockerHit.setWorktreeHostPath(worktreeHostPath);
            dockerHit.setWorktreeKey(worktreeKey);
            instances.put(leaseId, dockerHit);
            log.info("reused existing container lease={} container={} port={} worktreeKey={}",
                    leaseId, dockerHit.getContainerName(), dockerHit.getHostPort(), worktreeKey);
            return dockerHit;
        }

        String img = StringUtils.hasText(image) ? image : props.getImage();
        int hostPort = allocatePort();
        String name = "dap-sb-" + leaseId;
        dockerCli.run(dockerCli.base("rm", "-f", name), 30);

        List<String> cmd = new ArrayList<>();
        cmd.add(props.getDockerCommand());
        cmd.add("run");
        cmd.add("-d");
        cmd.add("--name");
        cmd.add(name);
        cmd.add("-e");
        cmd.add("WORKSPACE_ROOT=/workspace");
        cmd.add("-e");
        cmd.add("CODE_TOOLS_PORT=" + props.getCodeToolsContainerPort());
        cmd.add("-e");
        cmd.add("SANDBOX_TOKEN=" + props.getSharedToken());
        if (env != null) {
            for (Map.Entry<String, String> e : env.entrySet()) {
                if (e.getKey() == null || "CODE_TOOLS_PORT".equals(e.getKey())) {
                    continue;
                }
                cmd.add("-e");
                cmd.add(e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue()));
            }
        }
        cmd.add("-v");
        cmd.add(volumePath + ":/workspace");
        if (cacheMounts != null) {
            for (Map.Entry<String, String> entry : cacheMounts.entrySet()) {
                if (!truthy(entry.getValue())) continue;
                String key = entry.getKey();
                if ("gradle".equals(key) || "uv".equals(key)) {
                    cmd.add("-v");
                    cmd.add("dap-cache-" + key + ("gradle".equals(key) ? ":/root/.gradle" : ":/root/.cache/uv"));
                } else if (key.startsWith("workspace:")) {
                    String path = key.substring("workspace:".length());
                    if (!path.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")
                            || java.util.Arrays.asList(path.split("/")).contains("..")) {
                        throw new IllegalArgumentException("Invalid workspace cache path");
                    }
                    cmd.add("-v");
                    cmd.add("dap-ws-" + worktreeKey + "-" + worktreeKey(path) + ":/workspace/" + path);
                }
            }
        }
        if (cacheMounts == null || truthy(cacheMounts.get("m2"))) {
            cmd.add("-v");
            cmd.add(props.getM2CacheVolume() + ":/root/.m2");
        }
        if (cacheMounts == null || truthy(cacheMounts.get("npm"))) {
            cmd.add("-v");
            cmd.add(props.getNpmCacheVolume() + ":/root/.npm");
        }
        cmd.add("-p");
        cmd.add("127.0.0.1:" + hostPort + ":" + props.getCodeToolsContainerPort());
        if (portPublishes != null) {
            for (Map<String, Object> p : portPublishes) {
                Object host = p.get("host");
                Object container = p.get("container");
                if (host != null && container != null) {
                    cmd.add("-p");
                    cmd.add(host + ":" + container);
                }
            }
        }
        cmd.add("--label");
        cmd.add("dap.leaseId=" + leaseId);
        cmd.add("--label");
        cmd.add("dap.worktreeKey=" + worktreeKey);
        // Profile 会注入 SERVER_PORT=8080（给业务进程）；Spring Boot 会抢去占管理口。
        // 强制 code-tools 听 CODE_TOOLS_PORT，业务启动时再自行指定端口。
        cmd.add("--entrypoint");
        cmd.add("java");
        cmd.add(img);
        cmd.add("-jar");
        cmd.add("/opt/code-tools/app.jar");
        cmd.add("--server.port=" + props.getCodeToolsContainerPort());

        try {
            dockerCli.runOrThrow(cmd, 120);
        } catch (Exception e) {
            // 端口冲突等：再尝试收养同 worktree 已运行容器
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("port is already allocated") || msg.contains("Bind for")) {
                dockerCli.run(dockerCli.base("rm", "-f", name), 30);
                Instance retry = findRunningByWorktreeKey(worktreeKey);
                if (retry != null) {
                    retry.setLeaseId(leaseId);
                    retry.setWorktreeHostPath(worktreeHostPath);
                    retry.setWorktreeKey(worktreeKey);
                    instances.put(leaseId, retry);
                    log.warn("docker run port conflict, reused existing container lease={} container={}",
                            leaseId, retry.getContainerName());
                    return retry;
                }
            }
            throw e;
        }
        String base = "http://127.0.0.1:" + hostPort;
        waitReady(base);
        Instance inst = new Instance();
        inst.setLeaseId(leaseId);
        inst.setContainerName(name);
        inst.setHostPort(hostPort);
        inst.setCodeToolsBaseUrl(base);
        inst.setWorktreeHostPath(worktreeHostPath);
        inst.setWorktreeKey(worktreeKey);
        instances.put(leaseId, inst);
        log.info("attached lease={} container={} port={} worktreeKey={}", leaseId, name, hostPort, worktreeKey);
        return inst;
    }

    public synchronized void detach(Long leaseId) throws Exception {
        Instance inst = instances.remove(leaseId);
        String name = inst != null ? inst.getContainerName() : ("dap-sb-" + leaseId);
        // 若其他 lease 仍引用同一容器名，只解绑映射，不删容器
        boolean shared = instances.values().stream().anyMatch(i -> name.equals(i.getContainerName()));
        if (shared) {
            log.info("detach lease={} skipped rm, container {} still referenced", leaseId, name);
            return;
        }
        dockerCli.run(dockerCli.base("rm", "-f", name), 60);
    }

    private Instance rebindInstance(Instance existing, Long newLeaseId, String worktreeKey) {
        Long oldId = existing.getLeaseId();
        if (oldId != null && !oldId.equals(newLeaseId)) {
            instances.remove(oldId);
        }
        existing.setLeaseId(newLeaseId);
        existing.setWorktreeKey(worktreeKey);
        instances.put(newLeaseId, existing);
        log.info("rebound in-memory sandbox oldLease={} newLease={} container={}",
                oldId, newLeaseId, existing.getContainerName());
        return existing;
    }

    private Instance findInMemoryByWorktreeKey(String worktreeKey) {
        if (!StringUtils.hasText(worktreeKey)) {
            return null;
        }
        for (Instance i : instances.values()) {
            if (worktreeKey.equals(i.getWorktreeKey())) {
                return i;
            }
        }
        return null;
    }

    private Instance findRunningByWorktreeKey(String worktreeKey) throws Exception {
        DockerCli.ExecResult listed = dockerCli.run(dockerCli.base(
                "ps", "-q", "--filter", "label=dap.worktreeKey=" + worktreeKey), 15);
        if (listed.exitCode() != 0 || !StringUtils.hasText(listed.stdout())) {
            return null;
        }
        String containerId = listed.stdout().trim().lines().findFirst().orElse("").trim();
        if (!StringUtils.hasText(containerId)) {
            return null;
        }
        DockerCli.ExecResult nameR = dockerCli.run(dockerCli.base(
                "inspect", "-f", "{{.Name}}", containerId), 10);
        if (nameR.exitCode() != 0 || !StringUtils.hasText(nameR.stdout())) {
            log.warn("found worktree container but name inspect failed id={}", containerId);
            return null;
        }
        String name = nameR.stdout().trim().replaceFirst("^/", "");
        // Windows 上复杂 Go template 易炸；用 docker port 解析宿主映射口
        Integer hostPort = resolvePublishedHostPort(containerId, props.getCodeToolsContainerPort());
        if (hostPort == null) {
            log.warn("found worktree container but code-tools port missing id={} name={}", containerId, name);
            return null;
        }
        String base = "http://127.0.0.1:" + hostPort;
        try {
            waitReady(base);
        } catch (Exception e) {
            log.warn("existing container not ready, will recreate: {} {}", name, e.getMessage());
            dockerCli.run(dockerCli.base("rm", "-f", name), 60);
            return null;
        }
        Instance inst = new Instance();
        inst.setContainerName(name);
        inst.setHostPort(hostPort);
        inst.setCodeToolsBaseUrl(base);
        inst.setWorktreeKey(worktreeKey);
        return inst;
    }

    /**
     * 解析 {@code docker port <id> <containerPort>/tcp} 输出，例如 {@code 127.0.0.1:9401} / {@code 0.0.0.0:18000}。
     */
    private Integer resolvePublishedHostPort(String containerId, int containerPort) throws Exception {
        DockerCli.ExecResult portR = dockerCli.run(dockerCli.base(
                "port", containerId, containerPort + "/tcp"), 10);
        if (portR.exitCode() != 0 || !StringUtils.hasText(portR.stdout())) {
            return null;
        }
        for (String line : portR.stdout().trim().lines().toList()) {
            String s = line.trim();
            int colon = s.lastIndexOf(':');
            if (colon < 0 || colon >= s.length() - 1) {
                continue;
            }
            try {
                return Integer.parseInt(s.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                // next line
            }
        }
        return null;
    }

    private void waitReady(String base) throws Exception {
        long deadline = System.currentTimeMillis() + props.getReadyTimeoutSec() * 1000L;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(base + "/health").toURL().openConnection();
                c.setConnectTimeout(2000);
                c.setReadTimeout(2000);
                int code = c.getResponseCode();
                if (code >= 200 && code < 300) {
                    return;
                }
                last = new IllegalStateException("health http " + code);
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("code-tools not ready: " + (last == null ? "" : last.getMessage()));
    }

    private int allocatePort() {
        int start = props.getPortRangeStart();
        int end = props.getPortRangeEnd();
        int size = Math.max(1, end - start + 1);
        for (int i = 0; i < size; i++) {
            int port = start + Math.floorMod(portCursor.getAndIncrement(), size);
            boolean used = instances.values().stream().anyMatch(x -> x.getHostPort() == port);
            if (!used) {
                return port;
            }
        }
        throw new IllegalStateException("SB_PORT_EXHAUSTED");
    }

    static String toDockerVolumePath(String hostPath) {
        String p = Path.of(hostPath).toAbsolutePath().normalize().toString();
        return p.replace('\\', '/');
    }

    static String worktreeKey(String volumePath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(volumePath.toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(volumePath.toLowerCase().hashCode());
        }
    }

    private static boolean truthy(String v) {
        if (v == null) {
            return true;
        }
        String s = v.trim().toLowerCase();
        return !(s.equals("false") || s.equals("0") || s.equals("no"));
    }

    @Data
    public static class Instance {
        private Long leaseId;
        private String containerName;
        private int hostPort;
        private String codeToolsBaseUrl;
        private String worktreeHostPath;
        private String worktreeKey;
    }
}
