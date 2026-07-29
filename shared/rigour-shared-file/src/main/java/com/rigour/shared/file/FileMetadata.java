package com.rigour.shared.file;

import java.time.OffsetDateTime;

/**
 * 文件元数据契约。
 * objectKey 必须包含租户隔离前缀；访问授权和业务归属仍由具体领域服务判断。
 */
public record FileMetadata(
        String tenantId,
        String objectKey,
        String originalName,
        String contentType,
        long size,
        String checksum,
        OffsetDateTime createdAt
) {
}
