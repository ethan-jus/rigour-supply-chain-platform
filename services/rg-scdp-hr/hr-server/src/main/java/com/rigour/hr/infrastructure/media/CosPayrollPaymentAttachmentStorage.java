package com.rigour.hr.infrastructure.media;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.*;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.*;
import com.qcloud.cos.region.Region;
import com.rigour.hr.application.port.out.PayrollPaymentAttachmentStorage;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

/** 私有对象空间；系统内经 HR 鉴权读取，报表导出使用明确到期的 COS 签名链接。 */
@Component
public final class CosPayrollPaymentAttachmentStorage implements PayrollPaymentAttachmentStorage {
    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024;
    private final COSClient client;
    private final String bucket;

    public CosPayrollPaymentAttachmentStorage(
            @Value("${rigour.hr.payroll-files.region:${rigour.hr.expense-files.region:${RIGOUR_COS_REGION:${COS_REGION:ap-beijing}}}}") String region,
            @Value("${rigour.hr.payroll-files.bucket:${rigour.hr.expense-files.bucket:${RIGOUR_COS_BUCKET:${COS_BUCKET:rigour-sales-recordings-1361731487}}}}") String bucket,
            @Value("${RIGOUR_COS_SECRET_ID:${COS_SECRET_ID:}}") String secretId,
            @Value("${RIGOUR_COS_SECRET_KEY:${COS_SECRET_KEY:}}") String secretKey,
            @Value("${RIGOUR_COS_SESSION_TOKEN:${COS_SESSION_TOKEN:}}") String sessionToken) {
        if (!StringUtils.hasText(region) || !StringUtils.hasText(bucket)
                || !StringUtils.hasText(secretId) || !StringUtils.hasText(secretKey)) {
            this.client = null;
            this.bucket = null;
            return;
        }
        COSCredentials credentials = StringUtils.hasText(sessionToken)
                ? new BasicSessionCredentials(secretId, secretKey, sessionToken)
                : new BasicCOSCredentials(secretId, secretKey);
        var configuration = new ClientConfig(new Region(region));
        configuration.setHttpProtocol(HttpProtocol.https);
        configuration.setConnectionTimeout(3000);
        configuration.setSocketTimeout(10000);
        configuration.setMaxErrorRetry(2);
        this.client = new COSClient(credentials, configuration);
        this.bucket = bucket;
    }

    public String upload(String tenant, String payrollId, String paymentId, String contentType, byte[] bytes) {
        requireStorage();
        String key = objectKey(tenant, payrollId, paymentId, contentType);
        var metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(contentType);
        var request = new PutObjectRequest(bucket, key, new ByteArrayInputStream(bytes), metadata);
        request.setCannedAcl(CannedAccessControlList.Private);
        try { client.putObject(request); }
        catch (RuntimeException failure) {
            discard(key);
            throw failure("发放凭证上传失败，请稍后重试");
        }
        return key;
    }

    public byte[] read(String key) {
        requireStorage();
        try (var stream = client.getObject(bucket, key).getObjectContent()) {
            byte[] content = stream.readNBytes(MAX_FILE_SIZE + 1);
            if (content.length > MAX_FILE_SIZE) throw failure("发放凭证超过允许大小");
            return content;
        } catch (IOException | RuntimeException error) {
            throw failure("发放凭证读取失败，请稍后重试");
        }
    }

    public String signedDownload(String key, String name, String type, java.time.Instant expiresAt) {
        requireStorage();
        if (!Set.of("image/png", "image/jpeg", "application/pdf").contains(type)
                || expiresAt == null || !expiresAt.isAfter(java.time.Instant.now())
                || expiresAt.isAfter(java.time.Instant.now().plus(java.time.Duration.ofDays(7)))
                || key == null || !key.matches("[a-fA-F0-9-]{36}/hr-payroll-payment-attachments/"
                        + "[a-fA-F0-9-]{36}/[a-fA-F0-9-]{36}/[a-fA-F0-9-]{36}\\.(png|jpg|pdf)"))
            throw failure("发放凭证地址无效，请联系管理员");
        try {
            var request = new GeneratePresignedUrlRequest(bucket, key, HttpMethodName.GET);
            request.setExpiration(Date.from(expiresAt));
            var headers = new ResponseHeaderOverrides();
            headers.setContentType(type);
            headers.setCacheControl("private, no-store");
            headers.setContentDisposition(org.springframework.http.ContentDisposition.inline()
                    .filename(name, java.nio.charset.StandardCharsets.UTF_8).build().toString());
            request.setResponseHeaders(headers);
            return client.generatePresignedUrl(request, true).toExternalForm();
        } catch (RuntimeException failure) {
            throw failure("发放凭证链接生成失败，请稍后重试");
        }
    }

    public void discard(String key) {
        if (client == null) return;
        try { client.deleteObject(bucket, key); }
        catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("发放凭证对象清理失败 key={} reason={}",
                    key, failure.getClass().getSimpleName());
        }
    }

    static String objectKey(String tenant, String payrollId, String paymentId, String type) {
        String extension = switch (type) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "application/pdf" -> "pdf";
            default -> throw new IllegalArgumentException("发放凭证类型无效");
        };
        return UUID.fromString(tenant) + "/hr-payroll-payment-attachments/" + UUID.fromString(payrollId)
                + "/" + UUID.fromString(paymentId) + "/" + UUID.randomUUID() + "." + extension;
    }

    private void requireStorage() {
        if (client == null) throw failure("发放凭证存储未配置，请联系管理员");
    }

    private static BusinessException failure(String message) {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, message, List.of());
    }

    @PreDestroy
    void close() { if (client != null) client.shutdown(); }
}
