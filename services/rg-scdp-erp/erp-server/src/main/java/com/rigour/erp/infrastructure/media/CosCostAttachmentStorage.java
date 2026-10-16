package com.rigour.erp.infrastructure.media;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import com.rigour.erp.application.port.out.CostAttachmentStorage;
import com.rigour.erp.infrastructure.config.ProductMediaAccessProperties;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 费用附件写入私有 COS；对象键 {tenantId}/erp-cost-attachments/{orderNo}/{uuid}.{ext}，
 * 读取沿用费用附件签名器（erp-cost-attachments 在允许前缀内）。
 */
@Component
public final class CosCostAttachmentStorage implements CostAttachmentStorage {
    static final String OBJECT_PREFIX = "erp-cost-attachments";

    private final COSClient client;
    private final String bucket;
    private final String unavailableReason;

    public CosCostAttachmentStorage(ProductMediaAccessProperties properties) {
        ProductMediaAccessProperties.Cos cos = properties.getCos();
        if (!StringUtils.hasText(cos.getRegion())
                || !StringUtils.hasText(cos.getBucket())
                || !StringUtils.hasText(cos.getSecretId())
                || !StringUtils.hasText(cos.getSecretKey())) {
            this.client = null;
            this.bucket = null;
            this.unavailableReason = "缺少 COS region/bucket/secret 配置";
            return;
        }
        COSCredentials credentials =
                StringUtils.hasText(cos.getSessionToken())
                        ? new BasicSessionCredentials(
                                cos.getSecretId(), cos.getSecretKey(), cos.getSessionToken())
                        : new BasicCOSCredentials(cos.getSecretId(), cos.getSecretKey());
        ClientConfig config = new ClientConfig(new Region(cos.getRegion()));
        config.setConnectionTimeout(cos.getConnectionTimeoutMs());
        config.setSocketTimeout(cos.getSocketTimeoutMs());
        config.setMaxErrorRetry(2);
        this.client = new COSClient(credentials, config);
        this.bucket = cos.getBucket();
        this.unavailableReason = null;
    }

    @Override
    public String upload(String tenantId, String orderNo, String contentType, byte[] content) {
        if (client == null) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR, "费用附件存储不可用：" + unavailableReason, List.of());
        }
        String key = objectKey(tenantId, orderNo, contentType);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setContentType(contentType);
        try {
            client.putObject(
                    new PutObjectRequest(bucket, key, new ByteArrayInputStream(content), metadata));
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "费用附件上传失败，请稍后重试", List.of());
        }
        return key;
    }

    static String objectKey(String tenantId, String orderNo, String contentType) {
        String extension = extensionOf(contentType);
        String safeOrderNo =
                orderNo == null ? "unknown" : orderNo.strip().replaceAll("[^A-Za-z0-9._-]", "_");
        return tenantId
                + "/"
                + OBJECT_PREFIX
                + "/"
                + safeOrderNo
                + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + "."
                + extension;
    }

    static String extensionOf(String contentType) {
        String normalized = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            default -> throw new IllegalArgumentException("费用附件类型无效");
        };
    }

    public String url(String key) {
        if (client == null)
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "费用附件存储未配置", List.of());
        return client.generatePresignedUrl(
                        bucket, key, new java.util.Date(System.currentTimeMillis() + 600_000))
                .toString();
    }

    public void discard(String key) {
        try {
            if (client != null) client.deleteObject(bucket, key);
        } catch (RuntimeException ignored) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("费用附件回滚清理失败，需要检查孤立对象");
        }
    }

    @PreDestroy
    void shutdown() {
        if (client != null) client.shutdown();
    }
}
