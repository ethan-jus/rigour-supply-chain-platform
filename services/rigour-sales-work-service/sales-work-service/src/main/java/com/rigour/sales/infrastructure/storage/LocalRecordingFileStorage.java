package com.rigour.sales.infrastructure.storage;

import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.file.FileMetadata;
import com.rigour.shared.file.FileStorage;
import com.rigour.sales.infrastructure.config.SalesRecordingProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地磁盘 FileStorage 实现，仅供开发联调；生产环境用 COS 实现替换同一端口。
 * 对象键必须含租户前缀，禁止路径穿越；对象键外的相对路径一律拒绝。
 */
@Component
@ConditionalOnProperty(prefix = "sales.recording", name = "storage-type",
        havingValue = "filesystem", matchIfMissing = true)
public class LocalRecordingFileStorage implements FileStorage {

    private final Path root;

    public LocalRecordingFileStorage(SalesRecordingProperties properties) {
        this.root = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
    }

    @Override
    public FileMetadata put(FileMetadata metadata, InputStream content) {
        Path target = resolve(metadata.tenantId(), metadata.objectKey());
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            return metadata;
        } catch (IOException error) {
            throw new BusinessException(ErrorCode.SALES_RECORDING_STORAGE_FAILED,
                    "录音片段存储失败", List.of());
        }
    }

    @Override
    public InputStream open(String tenantId, String objectKey) {
        try {
            return Files.newInputStream(resolve(tenantId, objectKey));
        } catch (IOException error) {
            throw new BusinessException(ErrorCode.SALES_RECORDING_STORAGE_FAILED,
                    "录音片段读取失败", List.of());
        }
    }

    @Override
    public void delete(String tenantId, String objectKey) {
        try {
            Files.deleteIfExists(resolve(tenantId, objectKey));
        } catch (IOException error) {
            throw new BusinessException(ErrorCode.SALES_RECORDING_STORAGE_FAILED,
                    "录音片段删除失败", List.of());
        }
    }

    private Path resolve(String tenantId, String objectKey) {
        if (tenantId == null || tenantId.isBlank() || objectKey == null || objectKey.isBlank()
                || !objectKey.startsWith(tenantId + "/") || objectKey.contains("..")) {
            throw new BusinessException(ErrorCode.SALES_RECORDING_STORAGE_FAILED,
                    "录音对象键无效", List.of());
        }
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new BusinessException(ErrorCode.SALES_RECORDING_STORAGE_FAILED,
                    "录音对象键无效", List.of());
        }
        return resolved;
    }
}
