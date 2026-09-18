package com.rigour.order.infrastructure.media;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ResponseHeaderOverrides;
import com.qcloud.cos.region.Region;
import com.rigour.order.application.port.out.FundAttachmentUrlResolver;
import com.rigour.order.infrastructure.config.FundAttachmentAccessProperties;
import jakarta.annotation.PreDestroy;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** COS 私桶资金附件短时 URL 解析器；不缓存 URL，每次请求都重新签名。 */
@Component
@ConditionalOnProperty(prefix = "rigour.order.fund-attachment.cos", name = "enabled", havingValue = "true")
public final class CosFundAttachmentUrlResolver implements FundAttachmentUrlResolver {
    private final COSClient client;
    private final String bucket;
    private final List<String> allowedObjectPrefixes;
    private final java.time.Duration ttl;

    public CosFundAttachmentUrlResolver(FundAttachmentAccessProperties properties) {
        FundAttachmentAccessProperties.Cos cos = properties.getCos();
        requireText(cos.getRegion(), "rigour.order.fund-attachment.cos.region");
        requireText(cos.getBucket(), "rigour.order.fund-attachment.cos.bucket");
        requireText(cos.getSecretId(), "rigour.order.fund-attachment.cos.secret-id");
        requireText(cos.getSecretKey(), "rigour.order.fund-attachment.cos.secret-key");
        LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        prefixes.add(normalizePrefix(cos.getObjectPrefix()));
        for (String prefix : properties.getAdditionalObjectPrefixes()) {
            prefixes.add(normalizePrefix(prefix));
        }
        this.allowedObjectPrefixes = List.copyOf(prefixes);
        if (properties.getUrlTtl() == null || properties.getUrlTtl().isNegative()
                || properties.getUrlTtl().isZero()) {
            throw new IllegalStateException("资金附件 URL 有效期必须大于0");
        }
        if (cos.getConnectionTimeoutMs() <= 0 || cos.getSocketTimeoutMs() <= 0) {
            throw new IllegalStateException("资金附件 COS 连接和读取超时必须大于0");
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
        this.ttl = properties.getUrlTtl();
    }

    @Override
    public String temporaryUrl(String tenantId, String objectKey) {
        validateKey(tenantId, objectKey, allowedObjectPrefixes);
        Date expiration = Date.from(Instant.now().plus(ttl));
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethodName.GET);
        request.setExpiration(expiration);
        request.setResponseHeaders(previewHeaders(objectKey));
        URL url = client.generatePresignedUrl(request);
        return url.toExternalForm();
    }

    @PreDestroy
    void shutdown() { client.shutdown(); }

    static void validateKey(String tenantId, String objectKey, String objectPrefix) {
        validateKey(tenantId, objectKey, List.of(normalizePrefix(objectPrefix)));
    }

    static void validateKey(String tenantId, String objectKey, List<String> objectPrefixes) {
        List<String> prefixes = objectPrefixes == null ? List.of() : new ArrayList<>(objectPrefixes);
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(objectKey)
                || objectKey.contains("..") || prefixes.stream()
                .noneMatch(prefix -> objectKey.startsWith(tenantId + "/" + prefix + "/"))) {
            throw new IllegalArgumentException("资金附件对象 key 无效");
        }
    }

    private static String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.strip();
        if (!value.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) {
            throw new IllegalStateException("资金附件 COS object-prefix 必须是安全的相对路径");
        }
        return value;
    }

    static ResponseHeaderOverrides previewHeaders(String objectKey) {
        ResponseHeaderOverrides headers = new ResponseHeaderOverrides()
                .withContentDisposition("inline");
        String contentType = previewContentType(objectKey);
        if (StringUtils.hasText(contentType)) headers.setContentType(contentType);
        return headers;
    }

    static String previewContentType(String objectKey) {
        String value = objectKey == null ? "" : objectKey.toLowerCase(Locale.ROOT);
        if (value.endsWith(".jpg") || value.endsWith(".jpeg")) return "image/jpeg";
        if (value.endsWith(".png")) return "image/png";
        if (value.endsWith(".gif")) return "image/gif";
        if (value.endsWith(".webp")) return "image/webp";
        if (value.endsWith(".bmp")) return "image/bmp";
        if (value.endsWith(".svg")) return "image/svg+xml";
        if (value.endsWith(".pdf")) return "application/pdf";
        return null;
    }

    private static void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) throw new IllegalStateException(name + "未配置");
    }
}
