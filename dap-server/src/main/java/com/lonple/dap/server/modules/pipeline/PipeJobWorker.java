package com.lonple.dap.server.modules.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lonple.dap.server.modules.pipeline.entity.PipeJob;
import com.lonple.dap.server.modules.pipeline.mapper.PipeJobMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流水线 Job 调度：同节点多单元、跨节点 Job 均可并行执行（不再串行堵在一次 poll 里）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipeJobWorker {

    /** 同时跑 Agent/合并/推进 的上限 */
    private static final int MAX_PARALLEL = 8;
    private static final int CLAIM_BATCH = 8;

    private final PipeJobMapper jobMapper;
    private final PipeRuntimeService runtimeService;

    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger threadSeq = new AtomicInteger(0);
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL, r -> {
        Thread t = new Thread(r, "pipe-job-" + threadSeq.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    @Scheduled(fixedDelay = 1500L)
    public void poll() {
        int slots = MAX_PARALLEL - inFlight.get();
        if (slots <= 0) {
            return;
        }
        int limit = Math.min(slots, CLAIM_BATCH);
        List<PipeJob> candidates = jobMapper.selectList(new LambdaQueryWrapper<PipeJob>()
                .eq(PipeJob::getStatus, "PENDING")
                .le(PipeJob::getNextRunAt, LocalDateTime.now())
                .orderByAsc(PipeJob::getId)
                .last("LIMIT " + limit));
        if (candidates.isEmpty()) {
            return;
        }

        List<PipeJob> claimed = new ArrayList<>();
        for (PipeJob job : candidates) {
            if (claim(job)) {
                claimed.add(job);
            }
        }
        for (PipeJob job : claimed) {
            inFlight.incrementAndGet();
            executor.execute(() -> {
                try {
                    runtimeService.executeJob(job);
                } catch (Exception e) {
                    log.error("pipe worker unexpected jobId={}", job.getId(), e);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    /** 乐观抢占，避免重叠 poll / 多实例重复执行同一 Job */
    private boolean claim(PipeJob job) {
        LocalDateTime now = LocalDateTime.now();
        int n = jobMapper.update(null, new LambdaUpdateWrapper<PipeJob>()
                .eq(PipeJob::getId, job.getId())
                .eq(PipeJob::getStatus, "PENDING")
                .set(PipeJob::getStatus, "RUNNING")
                .set(PipeJob::getUpdatedAt, now));
        if (n > 0) {
            job.setStatus("RUNNING");
            job.setUpdatedAt(now);
            return true;
        }
        return false;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
