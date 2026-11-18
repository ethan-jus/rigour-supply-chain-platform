package com.rigour.sales.infrastructure.storage;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import com.rigour.sales.infrastructure.config.SalesRecordingProperties;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.file.FileMetadata;
import com.rigour.shared.file.FileStorage;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 腾讯云 COS 录音对象存储；凭据只从部署 Secret 注入，不进入日志、Nacos 或业务表。 */
@Component
@ConditionalOnProperty(prefix = "sales.recording", name = "storage-type", havingValue = "cos")
public class CosRecordingFileStorage implements FileStorage {

    private static final Duration PRESIGNED_GET_TTL = Duration.ofMinutes(30);

    private final COSClient client;
    private final String bucket;
    private final boolean serverSideEncryption;

    public CosRecordingFileStorage(SalesRecordingProperties properties) {
        SalesRecordingProperties.Cos cos = properties.getCos();
        requireText(cos.getRegion(), "sales.recording.cos.region");
        requireText(cos.getBucket(), "sales.recording.cos.bucket");
        requireText(cos.getSecretId(), "sales.recording.cos.secret-id");
        requireText(cos.getSecretKey(), "sales.recording.cos.secret-key");
        if (cos.getConnectionTimeoutMs() <= 0 || cos.getSocketTimeoutMs() <= 0) {
            throw new IllegalStateException("COS连接和读取超时必须大于0");
        }
        COSCredentials credentials = StringUtils.hasText(cos.getSessionToken())
                ? new BasicSessionCredentials(cos.getSecretId(), cos.getSecretKey(), cos.getSessionToken())
                : new BasicCOSCredentials(cos.getSecretId(), cos.getSecretKey());
        ClientConfig config = new ClientConfig(new Region(cos.getRegion()));
        config.setConnectionTimeout(cos.getConnectionTimeoutMs());
        config.setSocketTimeout(cos.getSocketTimeoutMs());
        config.setMaxErrorRetry(2);
        this.client = new COSClient(credentials, config);
        this.bucket = cos.getBucket();
        this.serverSideEncryption = cos.isServerSideEncryption();
    }

    @Override
    public FileMetadata put(FileMetadata metadata, InputStream content) {
        validateObjectKey(metadata.tenantId(), metadata.objectKey());
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentLength(metadata.size());
        objectMetadata.setContentType(metadata.contentType());
        objectMetadata.setUserMetadata(Map.of("sha256", metadata.checksum()));
        if (serverSideEncryption) objectMetadata.setServerSideEncryption("AES256");
        try {
            client.putObject(bucket, metadata.objectKey(), content, objectMetadata);
            return metadata;
        } catch (RuntimeException error) {
            throw storageFailure("录音片段写入COS失败", error);
        }
    }

    @Override
    public InputStream open(String tenantId, String objectKey) {
        validateObjectKey(tenantId, objectKey);
        try {
            return client.getObject(bucket, objectKey).getObjectContent();
        } catch (RuntimeException error) {
            throw storageFailure("录音片段从COS读取失败", error);
        }
    }

    @Override
    public void delete(String tenantId, String objectKey) {
        validateObjectKey(tenantId, objectKey);
        try {
            client.deleteObject(bucket, objectKey);
        } catch (RuntimeException error) {
            throw storageFailure("录音片段从COS删除失败", error);
        }
    }

    /**
     * 为指定租户的录音对象生成 30 分钟有效的 GET 预签名地址。
     *
     * <p>地址仅用于服务端向语音识别供应商临时授权，不应写入日志或数据库。</p>
     */
    public URL generatePresignedGetUrl(String tenantId, String objectKey) {
        validateObjectKey(tenantId, objectKey);
        try {
            GeneratePresignedUrlRequest request =
                    new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethodName.GET);
            request.setExpiration(Date.from(Instant.now().plus(PRESIGNED_GET_TTL)));
            return client.generatePresignedUrl(request);
        } catch (RuntimeException error) {
            throw storageFailure("录音对象访问地址生成失败", error);
        }
    }

    @PreDestroy
    void shutdown() {
        client.shutdown();
    }

    private static void validateObjectKey(String tenantId, String objectKey) {
        if (!StringUtils.hasText(tenantId)
                || !tenantId.equals(tenantId.trim())
                || tenantId.indexOf('/') >= 0
                || tenantId.indexOf('\\') >= 0
                || hasControlCharacter(tenantId)
                || !StringUtils.hasText(objectKey)
                || !objectKey.startsWith(tenantId + "/")
                || objectKey.length() == tenantId.length() + 1
                || objectKey.contains("..")
                || objectKey.indexOf('\\') >= 0
                || hasControlCharacter(objectKey)) {
            throw storageFailure("录音对象键无效", null);
        }
    }

    private static boolean hasControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(name + "未配置");
        }
    }

    private static BusinessException storageFailure(String message, RuntimeException cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.SALES_RECORDING_STORAGE_FAILED, message, List.of());
        if (cause != null) exception.initCause(cause);
        return exception;
    }
}
