package com.rigour.shared.file;

import java.io.InputStream;

/**
 * 对象存储端口。
 * 实现负责流关闭约定、加密、校验和、租户路径隔离以及失败重试；shared 不提供本地磁盘回退。
 */
public interface FileStorage {

    FileMetadata put(FileMetadata metadata, InputStream content);

    InputStream open(String tenantId, String objectKey);

    void delete(String tenantId, String objectKey);
}
