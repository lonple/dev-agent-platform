package com.lonple.dap.server.modules.review.store;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "dap.review")
public class ReviewProperties {
    private String dataDir = "review-data";
    private Diff diff = new Diff();
    private Context context = new Context();
    private Llm llm = new Llm();
    private Rules rules = new Rules();
    private Partition partition = new Partition();
    private Sse sse = new Sse();

    @Data
    public static class Diff {
        /** 兼容旧配置；分片路径不再整包截断 */
        @Deprecated
        private int maxChars = 200_000;
        @Deprecated
        private int maxFiles = 80;
        /** 单上下文模式：文件数上限（含） */
        private int singleMaxFiles = 25;
        /** 单上下文模式：字符数上限（含） */
        private int singleMaxChars = 80_000;
        /** 单片上限：超则模块/批切 */
        private int shardMaxFiles = 20;
        private int shardMaxChars = 60_000;
    }

    @Data
    public static class Context {
        private int maxFileChars = 30_000;
        private int maxTotalChars = 80_000;
    }

    @Data
    public static class Llm {
        private int maxRounds = 8;
    }

    @Data
    public static class Rules {
        private String javaPath = "rule/java_review.md";
        private String frontendPath = "rule/frontend_review.md";
        private String mybatisXmlPath = "rule/mybatis_xml_review.md";
        private String sqlPath = "rule/sql_review.md";
        private String configPath = "rule/config_review.md";
    }

    @Data
    public static class Partition {
        /** 路径模块 depth（剥前缀后取几段） */
        private int moduleDepth = 2;
        /** 分片并行度 */
        private int parallelism = 4;
    }

    @Data
    public static class Sse {
        private long timeoutMs = 1_800_000L;
    }
}
