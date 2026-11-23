package com.lonple.dap.server.modules.review.review;

import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.modules.review.partition.ShardKind;
import com.lonple.dap.server.modules.review.store.ReviewProperties;
import com.lonple.dap.server.modules.review.store.WorkspacePaths;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class ReviewPromptService {
    private final ReviewProperties properties;
    private final String promptTemplate;
    private final String crossPromptTemplate;

    public ReviewPromptService(ReviewProperties properties) {
        this.properties = properties;
        this.promptTemplate = loadClasspath("prompts/code_review.md");
        this.crossPromptTemplate = loadClasspath("prompts/cross_review.md");
    }

    public String systemPrompt() {
        return promptTemplate;
    }

    public String crossSystemPrompt() {
        return crossPromptTemplate;
    }

    public String loadRules(ShardKind kind) {
        Path path = rulesPath(kind);
        try {
            if (!Files.isRegularFile(path)) {
                throw new BizException("规则文件不存在: " + path
                        + "（可用 ADA_REVIEW_*_RULES 指定绝对路径）");
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(500, "读取规则失败: " + e.getMessage());
        }
    }

    /** @deprecated 使用 {@link #loadRules(ShardKind)} */
    @Deprecated
    public String loadJavaRules() {
        return loadRules(ShardKind.BACKEND);
    }

    public Path rulesPath(ShardKind kind) {
        String configured = switch (kind) {
            case FRONTEND -> properties.getRules().getFrontendPath();
            case BACKEND -> properties.getRules().getJavaPath();
            case MYBATIS_XML -> properties.getRules().getMybatisXmlPath();
            case SQL -> properties.getRules().getSqlPath();
            case CONFIG -> properties.getRules().getConfigPath();
            case OTHER -> properties.getRules().getJavaPath();
        };
        return WorkspacePaths.resolveExistingFile(configured);
    }

    public Path javaRulesPath() {
        return rulesPath(ShardKind.BACKEND);
    }

    private static String loadClasspath(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("缺少 Prompt: " + path, e);
        }
    }
}
