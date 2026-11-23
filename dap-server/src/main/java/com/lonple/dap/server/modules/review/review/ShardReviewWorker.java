package com.lonple.dap.server.modules.review.review;

import com.lonple.dap.server.modules.review.partition.ReviewShard;
import com.lonple.dap.server.modules.review.review.model.ReviewIssue;
import com.lonple.dap.server.modules.review.task.TaskConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * 单分片评审 Worker。实现可替换（预留日后 LangChain4j 等）。
 */
public interface ShardReviewWorker {

    ShardOutcome review(String taskId,
                        TaskConfig config,
                        Path workTree,
                        ReviewShard shard,
                        List<String> commitMessages);

    record ShardOutcome(String shardId, List<ReviewIssue> issues, int rounds, boolean success, String error) {
        public static ShardOutcome ok(String shardId, List<ReviewIssue> issues, int rounds) {
            return new ShardOutcome(shardId, issues, rounds, true, null);
        }

        public static ShardOutcome fail(String shardId, String error) {
            return new ShardOutcome(shardId, List.of(), 0, false, error);
        }
    }
}
