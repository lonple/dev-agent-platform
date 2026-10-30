package com.lonple.dap.server.modules.agenttool.repo;

import com.lonple.dap.server.common.BizException;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 将相对路径解析到仓库根下，并强制禁止越界（含 symlink）。
 */
public final class RepoPathSandbox {

    private RepoPathSandbox() {}

    public static Path requireRepoRoot(String localPath) {
        if (!StringUtils.hasText(localPath)) {
            throw new BizException("仓库路径为空");
        }
        Path root;
        try {
            root = Path.of(localPath.trim()).toAbsolutePath().normalize();
            if (!Files.exists(root) || !Files.isDirectory(root)) {
                throw new BizException("仓库目录不存在：" + root);
            }
            return root.toRealPath();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("仓库路径无效：" + localPath);
        }
    }

    /**
     * @param relativePath 相对仓库根的路径；禁止绝对路径与盘符路径
     * @param mustExist    是否要求目标已存在
     */
    public static Path resolveInRepo(Path repoRoot, String relativePath, boolean mustExist) {
        if (!StringUtils.hasText(relativePath)) {
            throw new BizException("相对路径不能为空");
        }
        String rel = relativePath.trim().replace('\\', '/');
        if (rel.startsWith("/") || rel.matches("^[A-Za-z]:.*")) {
            throw new BizException("仅允许仓库内相对路径，禁止绝对路径：" + relativePath);
        }
        if (rel.contains("\0")) {
            throw new BizException("路径非法");
        }
        Path base;
        try {
            base = repoRoot.toRealPath();
        } catch (IOException e) {
            throw new BizException("无法解析仓库根：" + repoRoot);
        }
        Path candidate = base.resolve(rel).normalize();
        if (!candidate.startsWith(base)) {
            throw new BizException("路径越出仓库范围：" + relativePath);
        }
        try {
            if (Files.exists(candidate)) {
                Path real = candidate.toRealPath();
                if (!real.startsWith(base)) {
                    throw new BizException("路径经符号链接越出仓库：" + relativePath);
                }
                return real;
            }
            if (mustExist) {
                throw new BizException("路径不存在：" + relativePath);
            }
            // 父目录必须在仓内且存在（或可创建时仍校验祖先）
            Path parent = candidate.getParent();
            while (parent != null && !Files.exists(parent)) {
                if (!parent.startsWith(base)) {
                    throw new BizException("路径越出仓库范围：" + relativePath);
                }
                parent = parent.getParent();
            }
            if (parent != null) {
                Path realParent = parent.toRealPath();
                if (!realParent.startsWith(base)) {
                    throw new BizException("父目录越出仓库：" + relativePath);
                }
            }
            return candidate;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            throw new BizException("路径解析失败：" + relativePath);
        }
    }

    public static String toRelative(Path repoRoot, Path absolute) {
        try {
            Path base = repoRoot.toRealPath();
            Path abs = absolute.normalize();
            if (Files.exists(abs)) {
                abs = abs.toRealPath();
            }
            if (!abs.startsWith(base)) {
                throw new BizException("不在仓库内：" + absolute);
            }
            String rel = base.relativize(abs).toString().replace('\\', '/');
            return rel.isEmpty() ? "." : rel;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("相对路径计算失败");
        }
    }

    public static boolean looksLikeWindowsAbs(String p) {
        if (!StringUtils.hasText(p)) {
            return false;
        }
        String s = p.trim();
        return s.matches("^[A-Za-z]:[\\\\/].*") || s.startsWith("\\\\");
    }

    public static boolean looksLikeUnixAbs(String p) {
        return StringUtils.hasText(p) && p.trim().startsWith("/");
    }

    public static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    public static boolean isWindows() {
        return osName().contains("win");
    }
}
