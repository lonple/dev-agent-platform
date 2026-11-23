package com.lonple.dap.server.modules.review.task;

import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.review.credential.CredentialStore;
import com.lonple.dap.server.modules.review.git.GitService;
import com.lonple.dap.server.modules.review.git.RepoType;
import com.lonple.dap.server.modules.review.log.ReviewLogService;
import com.lonple.dap.server.modules.review.store.JsonTaskStore;
import com.lonple.dap.server.modules.review.task.dto.CreateTaskRequest;
import com.lonple.dap.server.modules.review.task.dto.CredentialRequest;
import com.lonple.dap.server.modules.review.task.dto.TaskDetailVo;
import com.lonple.dap.server.modules.review.task.dto.UpdateConfigRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class TaskService {
    private final JsonTaskStore store;
    private final GitService gitService;
    private final CredentialStore credentialStore;
    private final ReviewLogService logService;

    public TaskService(JsonTaskStore store,
                       GitService gitService,
                       CredentialStore credentialStore,
                       ReviewLogService logService) {
        this.store = store;
        this.gitService = gitService;
        this.credentialStore = credentialStore;
        this.logService = logService;
    }

    public List<JsonTaskStore.IndexItem> list() {
        return store.listIndex();
    }

    public TaskConfig create(CreateTaskRequest req) {
        TaskConfig config = new TaskConfig();
        config.setId(newId());
        config.setName(req.getName().trim());
        config.setStatus(TaskStatus.DRAFT);
        config.setRepoType(RepoType.LOCAL);
        config.setCreatedAt(Instant.now().toString());
        config.setUpdatedAt(config.getCreatedAt());
        store.saveConfig(config);
        logService.info(config.getId(), "SYSTEM", "任务已创建");
        return config;
    }

    public TaskDetailVo detail(String id) {
        TaskDetailVo vo = new TaskDetailVo();
        TaskConfig config = store.loadConfig(id);
        config.setHasCredential(credentialStore.has(id));
        vo.setConfig(config);
        vo.setResult(store.loadResult(id));
        return vo;
    }

    public TaskConfig updateConfig(String id, UpdateConfigRequest req) {
        TaskConfig config = store.loadConfig(id);
        if (config.getStatus() == TaskStatus.RUNNING) {
            throw new BizException(409, "评审进行中，无法修改配置");
        }
        boolean repoChanged = false;
        if (req.getName() != null && StringUtils.hasText(req.getName())) {
            config.setName(req.getName().trim());
        }
        if (req.getProductName() != null) {
            config.setProductName(req.getProductName().trim());
        }
        if (req.getRepoType() != null && req.getRepoType() != config.getRepoType()) {
            config.setRepoType(req.getRepoType());
            repoChanged = true;
        }
        if (req.getLocalPath() != null) {
            String v = req.getLocalPath().trim();
            if (!v.equals(nullToEmpty(config.getLocalPath()))) {
                repoChanged = true;
            }
            config.setLocalPath(v.isEmpty() ? null : v);
        }
        if (req.getRemoteUrl() != null) {
            String v = req.getRemoteUrl().trim();
            if (!v.equals(nullToEmpty(config.getRemoteUrl()))) {
                repoChanged = true;
            }
            config.setRemoteUrl(v.isEmpty() ? null : v);
        }
        if (req.getBaseBranch() != null) {
            config.setBaseBranch(blankToNull(req.getBaseBranch()));
        }
        if (req.getReviewBranch() != null) {
            config.setReviewBranch(blankToNull(req.getReviewBranch()));
        }
        if (repoChanged) {
            config.setStatus(TaskStatus.DRAFT);
            config.setAvailableBranches(List.of());
            config.setCodeKey(null);
            config.setWorkTreePath(null);
            config.setBaseBranch(null);
            config.setReviewBranch(null);
        }
        store.saveConfig(config);
        return config;
    }

    public TaskConfig verify(String id, CredentialRequest credReq) {
        TaskConfig config = store.loadConfig(id);
        if (config.getStatus() == TaskStatus.RUNNING) {
            throw new BizException(409, "评审进行中，无法验证仓库");
        }
        acceptCredential(id, credReq);
        logService.info(id, "VERIFY", "开始仓库验证", Map.of("repoType", config.getRepoType().name()));
        try {
            GitService.VerifyOutcome outcome;
            if (config.getRepoType() == RepoType.LOCAL) {
                outcome = gitService.verifyLocal(config.getLocalPath());
                config.setCodeKey(null);
            } else {
                if (!StringUtils.hasText(config.getRemoteUrl())) {
                    throw new BizException("远程仓库 URL 不能为空");
                }
                CredentialStore.Cred cred = credentialStore.get(id).orElse(null);
                outcome = gitService.verifyRemote(config.getRemoteUrl(), cred);
                config.setCodeKey(outcome.codeKey());
            }
            config.setWorkTreePath(outcome.workTreePath());
            config.setAvailableBranches(outcome.branches());
            config.setStatus(TaskStatus.VERIFIED);
            config.setHasCredential(credentialStore.has(id));
            store.saveConfig(config);
            logService.info(id, "VERIFY", "仓库验证成功", Map.of(
                    "note", outcome.note() == null ? "" : outcome.note(),
                    "branches", outcome.branches().size(),
                    "workTree", outcome.workTreePath()));
            return config;
        } catch (RuntimeException e) {
            logService.error(id, "VERIFY", "仓库验证失败: " + e.getMessage());
            throw e;
        }
    }

    public void acceptCredential(String id, CredentialRequest credReq) {
        if (credReq == null) {
            return;
        }
        credentialStore.put(id, credReq.getUsername(), credReq.getPassword());
        try {
            TaskConfig config = store.loadConfig(id);
            config.setHasCredential(credentialStore.has(id));
            store.saveConfig(config);
        } catch (BizException ignored) {
        }
    }

    public void markRunning(String id) {
        logService.clearForRerun(id);
        TaskConfig config = store.loadConfig(id);
        config.setStatus(TaskStatus.RUNNING);
        config.setLastRunAt(Instant.now().toString());
        store.saveConfig(config);
    }

    public void markStatus(String id, TaskStatus status) {
        TaskConfig config = store.loadConfig(id);
        config.setStatus(status);
        store.saveConfig(config);
    }

    public TaskConfig requireRunnable(String id) {
        TaskConfig config = store.loadConfig(id);
        if (config.getStatus() == TaskStatus.RUNNING) {
            throw new BizException(409, "评审已在进行中");
        }
        if (config.getStatus() == TaskStatus.DRAFT) {
            throw new BizException("请先完成仓库验证");
        }
        if (!StringUtils.hasText(config.getProductName())) {
            throw new BizException("请填写产品名称");
        }
        if (!StringUtils.hasText(config.getBaseBranch()) || !StringUtils.hasText(config.getReviewBranch())) {
            throw new BizException("请选择基准分支与评审分支");
        }
        if (!StringUtils.hasText(config.getWorkTreePath())) {
            throw new BizException("工作区路径缺失，请重新验证仓库");
        }
        return config;
    }

    public void delete(String id) {
        store.deleteTask(id);
        credentialStore.clear(id);
    }

    private static String newId() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String rand = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x100000, 0xFFFFFF));
        return day + "-" + rand;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
