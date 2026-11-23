package com.lonple.dap.server.modules.review.partition;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
public class FileTypeClassifier {

    public ShardKind classify(String path) {
        if (!StringUtils.hasText(path)) {
            return ShardKind.OTHER;
        }
        String p = path.replace('\\', '/');
        String lower = p.toLowerCase(Locale.ROOT);
        String name = lower;
        int slash = lower.lastIndexOf('/');
        if (slash >= 0 && slash < lower.length() - 1) {
            name = lower.substring(slash + 1);
        }

        if (isFrontend(lower, name)) {
            return ShardKind.FRONTEND;
        }
        if (isMybatisXml(lower, name)) {
            return ShardKind.MYBATIS_XML;
        }
        if (name.endsWith(".sql")) {
            return ShardKind.SQL;
        }
        if (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties")) {
            return ShardKind.CONFIG;
        }
        if (name.endsWith(".java") || name.endsWith(".kt")) {
            return ShardKind.BACKEND;
        }
        return ShardKind.OTHER;
    }

    private static boolean isFrontend(String lowerPath, String name) {
        if (lowerPath.contains("/ada-front/")
                || lowerPath.startsWith("ada-front/")
                || lowerPath.contains("/frontend/")
                || lowerPath.contains("/src/modules/")) {
            if (name.endsWith(".vue") || name.endsWith(".ts") || name.endsWith(".tsx")
                    || name.endsWith(".js") || name.endsWith(".jsx")
                    || name.endsWith(".css") || name.endsWith(".scss")
                    || name.endsWith(".less")) {
                return true;
            }
            // 前端目录下的其它源码也归前端（排除 java）
            if (!name.endsWith(".java") && !name.endsWith(".kt")
                    && !name.endsWith(".xml") && !name.endsWith(".sql")
                    && !name.endsWith(".yml") && !name.endsWith(".yaml")
                    && !name.endsWith(".properties")) {
                if (name.endsWith(".vue") || name.endsWith(".ts") || name.endsWith(".tsx")
                        || name.endsWith(".js") || name.endsWith(".jsx")
                        || name.endsWith(".css") || name.endsWith(".scss")) {
                    return true;
                }
            }
        }
        return name.endsWith(".vue") || name.endsWith(".ts") || name.endsWith(".tsx")
                || name.endsWith(".js") || name.endsWith(".jsx")
                || name.endsWith(".css") || name.endsWith(".scss") || name.endsWith(".less");
    }

    private static boolean isMybatisXml(String lowerPath, String name) {
        if (!name.endsWith(".xml")) {
            return false;
        }
        if (name.endsWith("mapper.xml")) {
            return true;
        }
        return lowerPath.contains("/mapper/") || lowerPath.contains("/mybatis/");
    }
}
