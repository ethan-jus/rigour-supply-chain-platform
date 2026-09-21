package com.rigour.order.infrastructure.media;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import com.rigour.order.application.port.out.InvoiceAttachmentStorage;
import com.rigour.order.infrastructure.config.FundAttachmentAccessProperties;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 发票附件写入私有 COS；对象键 {tenantId}/order-invoices/{orderNo}/{uuid}.{ext}，
 * 读取沿用资金附件签名器（order-invoices 在允许前缀内）。
 */
@Component
public final class CosInvoiceAttachmentStorage implements InvoiceAttachmentStorage {
    static final String OBJECT_PREFIX = "order-invoices";

    private final COSClient client;
    private final String bucket;
    private final String unavailableReason;

    public CosInvoiceAttachmentStorage(FundAttachmentAccessProperties properties) {
        FundAttachmentAccessProperties.Cos cos = properties.getCos();
        if (!cos.isEnabled()) {
            this.client = null;
            this.bucket = null;
            this.unavailableReason = "资金附件 COS 未启用";
            return;
        }
        if (!StringUtils.hasText(cos.getRegion())
                || !StringUtils.hasText(cos.getBucket())
                || !StringUtils.hasText(cos.getSecretId())
                || !StringUtils.hasText(cos.getSecretKey())) {
            this.client = null;
            this.bucket = null;
            this.unavailableReason = "缺少 COS region/bucket/secret 配置";
            return;
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
        this.unavailableReason = null;
    }

    @Override
    public String upload(String tenantId, String orderNo, String fileName, String contentType, byte[] content) {
        if (client == null) {
            throw new BusinessException(
                    ErrorCode.ORDER_INVOICE_ATTACHMENT_STORAGE_FAILED,
                    "发票附件存储不可用：" + unavailableReason,
                    List.of());
        }
        String key = objectKey(tenantId, orderNo, contentType);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setContentType(contentType);
        try {
            client.putObject(
                    new PutObjectRequest(bucket, key, new ByteArrayInputStream(content), metadata));
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    ErrorCode.ORDER_INVOICE_ATTACHMENT_STORAGE_FAILED,
                    "发票附件上传失败，请稍后重试",
                    List.of());
        }
        return key;
    }

    static String objectKey(String tenantId, String orderNo, String contentType) {
        String extension = extensionOf(contentType);
        String safeOrderNo = orderNo == null ? "unknown" : orderNo.strip().replaceAll("[^A-Za-z0-9._-]", "_");
        return tenantId + "/" + OBJECT_PREFIX + "/" + safeOrderNo + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extension;
    }

    static String extensionOf(String contentType) {
        String normalized = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "application/pdf" -> "pdf";
            default -> throw new IllegalArgumentException("发票附件类型无效");
        };
    }

    @PreDestroy
    void shutdown() {
        if (client != null) client.shutdown();
    }
}
