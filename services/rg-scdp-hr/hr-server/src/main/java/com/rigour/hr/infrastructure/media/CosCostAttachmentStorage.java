package com.rigour.hr.infrastructure.media;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import com.rigour.hr.application.port.out.CostAttachmentStorage;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 费用附件写入私有 COS；对象键 {tenantId}/hr-expense-attachments/{orderNo}/{uuid}.{ext}，
 * 读取在费用权限和附件归属校验后生成十分钟有效签名链接。
 */
@Component
public final class CosCostAttachmentStorage implements CostAttachmentStorage {
    static final String OBJECT_PREFIX = "hr-expense-attachments";

    private final COSClient client;
    private final String bucket;
    private final String unavailableReason;

    public CosCostAttachmentStorage(
            @Value(
                            "${rigour.hr.expense-files.region:${RIGOUR_COS_REGION:${COS_REGION:ap-beijing}}}")
                    String region,
            @Value(
                            "${rigour.hr.expense-files.bucket:${RIGOUR_COS_BUCKET:${COS_BUCKET:rigour-sales-recordings-1361731487}}}")
                    String bucket,
            @Value("${RIGOUR_COS_SECRET_ID:${COS_SECRET_ID:}}") String secretId,
            @Value("${RIGOUR_COS_SECRET_KEY:${COS_SECRET_KEY:}}") String secretKey,
            @Value("${RIGOUR_COS_SESSION_TOKEN:${COS_SESSION_TOKEN:}}") String sessionToken) {
        if (!StringUtils.hasText(region)
                || !StringUtils.hasText(bucket)
                || !StringUtils.hasText(secretId)
                || !StringUtils.hasText(secretKey)) {
            this.client = null;
            this.bucket = null;
            this.unavailableReason = "缺少报销附件 COS 配置";
            return;
        }
        COSCredentials credentials =
                StringUtils.hasText(sessionToken)
                        ? new BasicSessionCredentials(secretId, secretKey, sessionToken)
                        : new BasicCOSCredentials(secretId, secretKey);
        ClientConfig config = new ClientConfig(new Region(region));
        config.setConnectionTimeout(3000);
        config.setSocketTimeout(10000);
        config.setMaxErrorRetry(2);
        this.client = new COSClient(credentials, config);
        this.bucket = bucket;
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
