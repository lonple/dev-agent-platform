package com.lonple.dap.server.modules.issue;

import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.config.DataRootProperties;
import com.lonple.dap.server.modules.tenant.entity.SysTenant;
import com.lonple.dap.server.modules.tenant.mapper.TenantMapper;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IssueAttachmentService {

    private static final Set<String> ALLOWED_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final DataRootProperties dataRootProperties;
    private final TenantMapper tenantMapper;

    public Map<String, String> store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择图片文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException("图片不能超过 5MB");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException("仅支持 png/jpg/jpeg/gif/webp/bmp");
        }

        Long tenantId = TenantSupport.requireTenantId();
        Path dir = resolveTenantDir(tenantId);
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(storedName).normalize();
        if (!target.startsWith(dir)) {
            throw new BizException("非法文件路径");
        }
        try {
            Files.createDirectories(dir);
            file.transferTo(target);
        } catch (IOException e) {
            throw new BizException("保存图片失败");
        }
        String url = "/api/issues/attachments/" + storedName;
        return Map.of("fileName", storedName, "url", url);
    }

    public LoadedFile load(String fileName) {
        String safe = sanitizeFileName(fileName);
        Long tenantId = TenantSupport.requireTenantId();
        Path path = resolveTenantDir(tenantId).resolve(safe).normalize();
        Path dir = resolveTenantDir(tenantId);
        if (!path.startsWith(dir) || !Files.isRegularFile(path)) {
            throw new BizException(404, "图片不存在");
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            return new LoadedFile(bytes, mediaTypeOf(safe));
        } catch (IOException e) {
            throw new BizException("读取图片失败");
        }
    }

    private Path resolveTenantDir(Long tenantId) {
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || !StringUtils.hasText(tenant.getCode())) {
            throw new BizException("租户不存在");
        }
        Path root = Paths.get(dataRootProperties.getRoot()).toAbsolutePath().normalize();
        return root.resolve(tenant.getCode().trim()).resolve("issue-attachments").normalize();
    }

    private static String extensionOf(String original) {
        if (!StringUtils.hasText(original) || !original.contains(".")) {
            throw new BizException("无法识别图片类型");
        }
        String ext = original.substring(original.lastIndexOf('.') + 1).trim().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(ext)) {
            throw new BizException("无法识别图片类型");
        }
        return ext;
    }

    private static String sanitizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName) || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..")) {
            throw new BizException(400, "非法文件名");
        }
        String name = fileName.trim();
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            throw new BizException(400, "非法文件名");
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(400, "非法文件类型");
        }
        return name;
    }

    private static MediaType mediaTypeOf(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "bmp" -> MediaType.parseMediaType("image/bmp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    public record LoadedFile(byte[] bytes, MediaType mediaType) {
    }
}
