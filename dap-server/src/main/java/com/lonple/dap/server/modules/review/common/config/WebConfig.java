package com.lonple.dap.server.modules.review.common.config;

import com.lonple.dap.server.modules.review.store.ReviewProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
public class WebConfig {

    @Bean(name = "reviewExecutor")
    public Executor reviewExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(20);
        exec.setThreadNamePrefix("review-");
        exec.initialize();
        return exec;
    }

    /** 分片并行（与 reviewExecutor 分离，避免嵌套等待死锁） */
    @Bean(name = "shardExecutor")
    public Executor shardExecutor(ReviewProperties properties) {
        int n = Math.max(1, properties.getPartition().getParallelism());
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(n);
        exec.setMaxPoolSize(Math.max(n, n * 2));
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("shard-");
        exec.initialize();
        return exec;
    }
}
