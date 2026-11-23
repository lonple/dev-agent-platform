package com.lonple.dap.server.modules.review.partition;

/**
 * 单个文件的 diff 片段。
 */
public record DiffFile(String path, String patch, int charCount) {
    public DiffFile {
        if (path == null) {
            path = "";
        }
        if (patch == null) {
            patch = "";
        }
        if (charCount <= 0) {
            charCount = patch.length();
        }
    }
}
