package com.lonple.dap.sandbox.client.docker;

import com.lonple.dap.sandbox.client.config.ClientProps;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class DockerCli {

    private final ClientProps props;

    public boolean dockerOk() {
        try {
            ExecResult r = run(List.of(props.getDockerCommand(), "info"), 15);
            return r.exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public String dockerVersion() {
        try {
            ExecResult r = run(List.of(props.getDockerCommand(), "version", "--format", "{{.Server.Version}}"), 10);
            return r.exitCode == 0 ? r.stdout.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public ExecResult run(List<String> cmd, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        boolean ok = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!ok) {
            p.destroyForcibly();
            return new ExecResult(-1, out + "\n(timeout)");
        }
        return new ExecResult(p.exitValue(), out.toString());
    }

    public ExecResult runOrThrow(List<String> cmd, int timeoutSec) throws Exception {
        ExecResult r = run(cmd, timeoutSec);
        if (r.exitCode != 0) {
            throw new IllegalStateException("docker failed: " + String.join(" ", cmd) + " => " + r.stdout);
        }
        return r;
    }

    public List<String> base(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getDockerCommand());
        for (String a : args) {
            cmd.add(a);
        }
        return cmd;
    }

    public record ExecResult(int exitCode, String stdout) {}
}
