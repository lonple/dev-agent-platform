package com.lonple.dap.server.modules.agenttool.repo;

import com.lonple.dap.server.common.BizException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 用 netstat 查看监听端口，并用 taskkill 结束占用该端口的进程。
 * 只接受已经校验过的端口号和从 netstat 解析出的 PID，不执行任意命令行。
 */
public final class PortProcessControl {

    private static final int TIMEOUT_SEC = 10;

    private static final Set<String> PROTECTED_IMAGES = Set.of(
            "system",
            "idle",
            "registry",
            "smss.exe",
            "csrss.exe",
            "wininit.exe",
            "winlogon.exe",
            "services.exe",
            "lsass.exe",
            "svchost.exe",
            "fontdrvhost.exe",
            "dwm.exe");

    public record Listener(long pid, String protocol, String localAddress, String imageName) {}

    public record StopOutcome(int exitCode, String message) {}

    private PortProcessControl() {}

    public static List<Listener> findListeners(int port) {
        String output = run(listenCommand(port), TIMEOUT_SEC).output();
        List<Listener> parsed = RepoPathSandbox.isWindows()
                ? parseNetstat(output, port)
                : parseLsof(output, port);
        List<Listener> unique = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Listener listener : parsed) {
            if (!seen.add(listener.pid())) {
                continue;
            }
            String image = imageName(listener.pid());
            unique.add(new Listener(listener.pid(), listener.protocol(), listener.localAddress(), image));
        }
        return unique;
    }

    public static String describe(List<Listener> listeners) {
        if (listeners == null || listeners.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Listener listener : listeners) {
            String name = listener.imageName() == null || listener.imageName().isBlank()
                    ? ""
                    : " " + listener.imageName();
            parts.add("pid=" + listener.pid() + name + " " + listener.localAddress());
        }
        return String.join(", ", parts);
    }

    public static StopOutcome stopListeners(int port) {
        List<Listener> listeners = findListeners(port);
        if (listeners.isEmpty()) {
            return new StopOutcome(0, "端口 " + port + " 空闲，没有需要结束的进程");
        }
        List<String> lines = new ArrayList<>();
        for (Listener listener : listeners) {
            if (isProtected(listener.pid(), listener.imageName())) {
                lines.add("已跳过受保护进程 pid=" + listener.pid()
                        + (listener.imageName().isBlank() ? "" : " " + listener.imageName()));
                continue;
            }
            CommandResult killed = taskkill(listener.pid());
            if (killed.exitCode() == 0) {
                lines.add("已结束 pid=" + listener.pid()
                        + (listener.imageName().isBlank() ? "" : " " + listener.imageName()));
            } else {
                lines.add("结束失败 pid=" + listener.pid() + " exit=" + killed.exitCode());
            }
        }
        List<Listener> left = findListeners(port);
        if (left.isEmpty()) {
            lines.add("端口 " + port + " 已释放");
            return new StopOutcome(0, String.join("\n", lines));
        }
        lines.add("端口仍被占用：" + describe(left));
        return new StopOutcome(1, String.join("\n", lines));
    }

    static List<Listener> parseNetstat(String output, int port) {
        List<Listener> found = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return found;
        }
        for (String raw : output.split("\\R")) {
            String[] parts = raw.trim().split("\\s+");
            if (parts.length < 4) {
                continue;
            }
            String proto = parts[0].toUpperCase(Locale.ROOT);
            if (!"TCP".equals(proto) && !"UDP".equals(proto)) {
                continue;
            }
            String state = parts[parts.length - 2];
            if (!"LISTENING".equalsIgnoreCase(state)) {
                continue;
            }
            String local = parts[1];
            if (!localEndsWithPort(local, port)) {
                continue;
            }
            long pid = parsePid(parts[parts.length - 1]);
            if (pid > 0) {
                found.add(new Listener(pid, proto, local, ""));
            }
        }
        return found;
    }

    static List<Listener> parseLsof(String output, int port) {
        List<Listener> found = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return found;
        }
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("COMMAND")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            long pid = parsePid(parts[1]);
            if (pid > 0) {
                found.add(new Listener(pid, "TCP", ":" + port, parts[0]));
            }
        }
        return found;
    }

    static boolean localEndsWithPort(String local, int port) {
        if (local == null || local.isBlank()) {
            return false;
        }
        String portText;
        int bracket = local.lastIndexOf(']');
        if (bracket >= 0 && bracket + 1 < local.length() && local.charAt(bracket + 1) == ':') {
            portText = local.substring(bracket + 2);
        } else {
            int colon = local.lastIndexOf(':');
            if (colon < 0 || colon == local.length() - 1) {
                return false;
            }
            portText = local.substring(colon + 1);
        }
        return Integer.toString(port).equals(portText);
    }

    private static boolean isProtected(long pid, String imageName) {
        if (pid <= 4 || pid == ProcessHandle.current().pid()) {
            return true;
        }
        if (imageName == null || imageName.isBlank()) {
            return false;
        }
        return PROTECTED_IMAGES.contains(imageName.toLowerCase(Locale.ROOT));
    }

    private static List<String> listenCommand(int port) {
        if (RepoPathSandbox.isWindows()) {
            return List.of("netstat", "-ano", "-p", "tcp");
        }
        if (port < 1 || port > 65535) {
            throw new BizException("端口非法");
        }
        return List.of("lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN");
    }

    private static CommandResult taskkill(long pid) {
        if (pid <= 4) {
            throw new BizException("拒绝结束系统进程");
        }
        if (RepoPathSandbox.isWindows()) {
            return run(List.of("taskkill", "/PID", Long.toString(pid), "/T", "/F"), TIMEOUT_SEC);
        }
        return run(List.of("kill", "-9", Long.toString(pid)), TIMEOUT_SEC);
    }

    private static String imageName(long pid) {
        if (!RepoPathSandbox.isWindows() || pid <= 0) {
            return "";
        }
        CommandResult result;
        try {
            result = run(List.of("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH"), TIMEOUT_SEC);
        } catch (RuntimeException e) {
            return "";
        }
        for (String raw : result.output().split("\\R")) {
            List<String> cols = parseCsvLine(raw.trim());
            if (cols.size() >= 2 && Long.toString(pid).equals(cols.get(1))) {
                return cols.get(0);
            }
        }
        return "";
    }

    static List<String> parseCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        if (line == null || line.isBlank()) {
            return cols;
        }
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (c == ',' && !quoted) {
                cols.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        cols.add(cur.toString());
        return cols;
    }

    private static long parsePid(String token) {
        if (token == null || token.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(token.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static CommandResult run(List<String> command, int timeoutSec) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), commandCharset()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (out.length() < 2_000_000) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "port-process-control");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            reader.join(500);
            if (!finished) {
                process.destroyForcibly();
                throw new BizException("命令超时：" + command.get(0));
            }
            return new CommandResult(process.exitValue(), out.toString());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("执行 " + command.get(0) + " 失败：" + e.getMessage());
        }
    }

    private static Charset commandCharset() {
        if (RepoPathSandbox.isWindows()) {
            return Charset.forName("GBK");
        }
        return StandardCharsets.UTF_8;
    }

    private record CommandResult(int exitCode, String output) {}
}
