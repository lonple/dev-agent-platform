package com.lonple.dap.codetools.api;

import com.lonple.dap.codetools.config.SandboxProps;
import com.lonple.dap.codetools.fs.WorkspaceGuard;
import com.lonple.dap.codetools.service.CodeWorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping
@RequiredArgsConstructor
public class CodeController {

    private final CodeWorkspaceService service;
    private final WorkspaceGuard guard;
    private final SandboxProps props;

    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean rg = false;
        try {
            Process p = new ProcessBuilder(props.getRipgrepExecutable(), "--version").start();
            rg = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ignored) {
        }
        return Map.of(
                "status", "UP",
                "workspaceRoot", guard.root().toString(),
                "workspaceExists", java.nio.file.Files.isDirectory(guard.root()),
                "ripgrep", rg);
    }

    @PostMapping({"/list", "/code/list"})
    public Dto.ListResult list(@RequestBody(required = false) Dto.ListReq body) {
        String rel = body == null ? "." : body.getRelativePath();
        long t0 = System.currentTimeMillis();
        Dto.ListResult out = service.listDir(rel);
        log.info("code list path={} entries={} costMs={}",
                safePath(rel), out == null || out.getEntries() == null ? 0 : out.getEntries().size(),
                System.currentTimeMillis() - t0);
        return out;
    }

    @PostMapping({"/read", "/code/read"})
    public Dto.ReadResult read(@Valid @RequestBody Dto.PathReq body) {
        long t0 = System.currentTimeMillis();
        Dto.ReadResult out = service.readFile(
                body.getRelativePath(), body.getStartLine(), body.getEndLine(), body.getMaxChars());
        int chars = out == null || out.getContent() == null ? 0 : out.getContent().length();
        log.info("code read path={} start={} end={} chars={} costMs={}",
                safePath(body.getRelativePath()), body.getStartLine(), body.getEndLine(),
                chars, System.currentTimeMillis() - t0);
        return out;
    }

    @PostMapping({"/write", "/code/write"})
    public Dto.GenericResult write(@Valid @RequestBody Dto.WriteReq body) {
        long t0 = System.currentTimeMillis();
        int chars = body.getContent() == null ? 0 : body.getContent().length();
        log.info("code write begin path={} chars={}", safePath(body.getRelativePath()), chars);
        Dto.GenericResult out = service.writeFile(body.getRelativePath(), body.getContent());
        log.info("code write done path={} ok={} costMs={}",
                safePath(body.getRelativePath()), out != null && out.isOk(), System.currentTimeMillis() - t0);
        return out;
    }

    @PostMapping({"/delete", "/code/delete"})
    public Dto.GenericResult delete(@Valid @RequestBody Dto.DeleteReq body) {
        long t0 = System.currentTimeMillis();
        log.info("code delete begin path={}", safePath(body.getRelativePath()));
        Dto.GenericResult out = service.deleteFile(body.getRelativePath());
        log.info("code delete done path={} ok={} costMs={}",
                safePath(body.getRelativePath()), out != null && out.isOk(), System.currentTimeMillis() - t0);
        return out;
    }

    @PostMapping({"/search", "/code/search"})
    public Map<String, Object> search(@Valid @RequestBody Dto.SearchReq body) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> out = service.search(body.getQuery());
        Object hits = out == null ? null : out.get("hits");
        int n = hits instanceof java.util.Collection<?> c ? c.size() : -1;
        log.info("code search queryLen={} hits={} costMs={}",
                body.getQuery() == null ? 0 : body.getQuery().length(), n, System.currentTimeMillis() - t0);
        return out;
    }

    @PostMapping({"/action", "/code/action"})
    public Dto.ActionResult action(@Valid @RequestBody Dto.ActionReq body) {
        long t0 = System.currentTimeMillis();
        String action = body.getAction();
        log.info("code action begin action={} argsKeys={}",
                action, body.getArgs() == null ? java.util.List.of() : body.getArgs().keySet());
        Dto.ActionResult out = service.runAction(action, body.getArgs());
        log.info("code action done action={} exitCode={} timedOut={} costMs={}",
                action,
                out == null ? null : out.getExitCode(),
                out != null && out.isTimedOut(),
                System.currentTimeMillis() - t0);
        return out;
    }

    @PostMapping({"/commit", "/code/commit"})
    public Dto.GenericResult commit(@Valid @RequestBody Dto.CommitReq body) {
        long t0 = System.currentTimeMillis();
        String msg = body.getMessage() == null ? "" : body.getMessage().trim();
        String preview = msg.length() > 80 ? msg.substring(0, 80) + "…" : msg;
        log.info("code commit begin message={}", preview);
        Dto.GenericResult out = service.commit(body.getMessage(), body.getAuthorName(), body.getAuthorEmail());
        log.info("code commit done ok={} costMs={}",
                out != null && out.isOk(), System.currentTimeMillis() - t0);
        return out;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        log.warn("code bad request: {}", e.getMessage());
        return Map.of("code", "SB_PATH_FORBIDDEN", "message", e.getMessage() == null ? "bad request" : e.getMessage());
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> denied(UnsupportedOperationException e) {
        log.warn("code action denied: {}", e.getMessage());
        return Map.of("code", "SB_ACTION_DENIED", "message", e.getMessage() == null ? "denied" : e.getMessage());
    }

    private static String safePath(String path) {
        return StringUtils.hasText(path) ? path.trim() : ".";
    }
}
