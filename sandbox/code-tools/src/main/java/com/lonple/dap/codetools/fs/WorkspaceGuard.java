package com.lonple.dap.codetools.fs;

import com.lonple.dap.codetools.config.SandboxProps;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class WorkspaceGuard {

    private final Path root;

    public WorkspaceGuard(SandboxProps props) {
        this.root = Path.of(props.getWorkspaceRoot()).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path resolve(String relativePath, boolean mustExist) {
        String rel = relativePath == null || relativePath.isBlank() ? "." : relativePath.trim();
        if (rel.contains("\0")) {
            throw new IllegalArgumentException("illegal path");
        }
        Path target = ".".equals(rel)
                ? root
                : root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace: " + relativePath);
        }
        try {
            if (Files.exists(target)) {
                Path real = target.toRealPath();
                if (!real.startsWith(root) && !real.equals(root)) {
                    // allow if root itself not yet real on some FS; still require normalize prefix
                    if (!real.startsWith(root.toAbsolutePath().normalize())) {
                        throw new IllegalArgumentException("symlink escape: " + relativePath);
                    }
                }
            } else if (mustExist) {
                throw new IllegalArgumentException("not found: " + relativePath);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("path resolve failed: " + e.getMessage());
        }
        return target;
    }
}
