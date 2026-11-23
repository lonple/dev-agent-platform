package com.lonple.dap.server.modules.sandbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dap.sandbox")
public class SandboxProperties {
    /**
     * 打开后：写/删/action/commit 在会话有 ATTACHED lease 时走远程沙箱。
     * list/read/search 始终本机，不依赖 Docker。
     */
    private boolean enabled = false;
    private String managerBaseUrl = "http://127.0.0.1:9210";
    private String sharedToken = "dev-sandbox-token";
    private boolean attachOnLease = true;
    /** remote 失败时是否回落本机写（生产建议 false） */
    private boolean fallbackLocal = false;
    private String defaultImage = "dap-sandbox-webdev:dev";
    private String defaultNodeCode = "local-dev";
    /** 业务端口池（Profile 实例化） */
    private int businessPortStart = 18000;
    private int businessPortEnd = 18999;
    /** 租约默认 TTL（小时），可被 Profile 覆盖 */
    private int defaultLeaseTtlHours = 8;
    /** 超时回收扫描间隔秒 */
    private int reclaimIntervalSec = 60;
    /** 编码节点触发时自动申请沙箱 */
    private boolean autoAcquireOnCoding = true;
    /** 编码节点结束时自动释放 */
    private boolean autoReleaseOnCodingDone = true;
    /**
     * 进入「等待人工确认」后，若这么多分钟内没有再次触发/确认继续，
     * 由超时回收关掉沙箱容器；worktree 目录始终保留。
     */
    private int confirmIdleReleaseMinutes = 10;
}
