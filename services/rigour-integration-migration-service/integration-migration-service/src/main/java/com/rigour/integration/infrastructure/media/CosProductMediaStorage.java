package com.rigour.integration.infrastructure.media;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import com.rigour.integration.infrastructure.config.ProductMediaProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 订货宝商品图片 COS 私桶适配器；不提供公开 URL，也不把密钥写入日志。 */
@Component
public final class CosProductMediaStorage implements com.rigour.integration.application.port.out.ProductMediaStorage {
    private static final Logger log = LoggerFactory.getLogger(CosProductMediaStorage.class);
    private static final int MAX_UPLOAD_ATTEMPTS = 3;
    private static final long UPLOAD_RETRY_BACKOFF_MILLIS = 250L;

    private final COSClient client;
    private final String bucket;
    private final String objectPrefix;
    private final long maxBytes;
    private final boolean serverSideEncryption;

    @Autowired
    public CosProductMediaStorage(ProductMediaProperties properties) {
        this(properties, createClient(validatedCos(properties)));
    }

    CosProductMediaStorage(ProductMediaProperties properties, COSClient client) {
        ProductMediaProperties.Cos cos = validatedCos(properties);
        this.client = Objects.requireNonNull(client, "COSClient不能为空");
        this.bucket = normalizedCredential(cos.getBucket());
        this.maxBytes = properties.getMaxBytes();
        this.serverSideEncryption = cos.isServerSideEncryption();
        this.objectPrefix = normalizePrefix(cos.getObjectPrefix());
    }

    private static ProductMediaProperties.Cos validatedCos(ProductMediaProperties properties) {
        Objects.requireNonNull(properties, "商品图片 COS 配置不能为空");
        ProductMediaProperties.Cos cos = properties.getCos();
        requireText(cos.getRegion(), "rigour.integration.product-media.cos.region");
        requireText(cos.getBucket(), "rigour.integration.product-media.cos.bucket");
        requireText(cos.getSecretId(), "rigour.integration.product-media.cos.secret-id");
        requireText(cos.getSecretKey(), "rigour.integration.product-media.cos.secret-key");
        if (properties.getMaxBytes() <= 0 || cos.getConnectionTimeoutMs() <= 0
                || cos.getSocketTimeoutMs() <= 0) {
            throw new IllegalStateException("商品图片 COS 参数必须大于0");
        }
        normalizePrefix(cos.getObjectPrefix());
        return cos;
    }

    private static COSClient createClient(ProductMediaProperties.Cos cos) {
        String secretId = normalizedCredential(cos.getSecretId());
        String secretKey = normalizedCredential(cos.getSecretKey());
        String sessionToken = normalizedCredential(cos.getSessionToken());
        COSCredentials credentials = StringUtils.hasText(sessionToken)
                ? new BasicSessionCredentials(secretId, secretKey, sessionToken)
                : new BasicCOSCredentials(secretId, secretKey);
        return new COSClient(credentials, createClientConfig(cos));
    }

    static ClientConfig createClientConfig(ProductMediaProperties.Cos cos) {
        ClientConfig config = new ClientConfig(new Region(normalizedCredential(cos.getRegion())));
        config.setConnectionTimeout(cos.getConnectionTimeoutMs());
        config.setSocketTimeout(cos.getSocketTimeoutMs());
        // 商品图片请求体使用临时文件；关闭连接复用，避免 COS/网络侧关闭空闲连接后复用到坏连接。
        config.setShortConnection();
        // 重试交给下面的应用层，避免 SDK 内外层重复重试造成重复请求。
        config.setMaxErrorRetry(0);
        return config;
    }

    @Override
    public boolean exists(String tenantId, String objectKey) {
        validateKey(tenantId, objectKey, objectPrefix);
        return client.doesObjectExist(bucket, objectKey);
    }

    @Override
    public void put(String tenantId, String objectKey, String originalName,
                    String contentType, byte[] content) {
        validateKey(tenantId, objectKey, objectPrefix);
        if (content == null || content.length == 0 || content.length > maxBytes) {
            throw new IllegalArgumentException("商品图片大小无效");
        }
        if (client.doesObjectExist(bucket, objectKey)) {
            log.debug("订货宝商品图片已存在，跳过重复上传 tenantId={} objectKey={} bytes={}",
                    tenantId, objectKey, content.length);
            return;
        }
        for (int attempt = 1; attempt <= MAX_UPLOAD_ATTEMPTS; attempt++) {
            try {
                if (attempt > 1 && client.doesObjectExist(bucket, objectKey)) {
                    log.debug("订货宝商品图片已存在，重试前检测到上传已完成 tenantId={} objectKey={}",
                            tenantId, objectKey);
                    return;
                }
                uploadOnce(objectKey, contentType, content);
                log.info("订货宝商品图片已上传私有COS tenantId={} objectKey={} bytes={} contentType={}",
                        tenantId, objectKey, content.length, contentType);
                return;
            } catch (CosClientException exception) {
                if (attempt == MAX_UPLOAD_ATTEMPTS || !isRetryable(exception)) {
                    throw exception;
                }
                log.warn("订货宝商品图片 COS 上传准备重试 tenantId={} objectKey={} attempt={} errorType={}",
                        tenantId, objectKey, attempt, exception.getClass().getSimpleName());
                backoff(attempt);
            }
        }
        throw new IllegalStateException("订货宝商品图片 COS 上传未完成");
    }

    private void uploadOnce(String objectKey, String contentType, byte[] content) {
        Path temporaryFile = null;
        try {
            temporaryFile = Files.createTempFile("rigour-product-image-", ".bin");
            Files.write(temporaryFile, content);
            PutObjectRequest request = new PutObjectRequest(bucket, objectKey, temporaryFile.toFile());
            request.setMetadata(metadata(content.length, contentType));
            client.putObject(request);
        } catch (IOException exception) {
            throw new CosClientException("商品图片 COS 请求体准备失败", exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException exception) {
                    log.warn("商品图片 COS 临时文件清理失败 objectKey={} errorType={}",
                            objectKey, exception.getClass().getSimpleName());
                }
            }
        }
    }

    private ObjectMetadata metadata(long contentLength, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(contentLength);
        if (StringUtils.hasText(contentType)) metadata.setContentType(contentType);
        // 原始文件名可能包含中文；不写入 x-cos-meta-*，避免 COS SDK 签名与请求头编码不一致。
        if (serverSideEncryption) metadata.setServerSideEncryption("AES256");
        return metadata;
    }

    private static boolean isRetryable(CosClientException exception) {
        return !(exception instanceof CosServiceException)
                && (exception.isRetryable() || hasCause(exception, IOException.class));
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(UPLOAD_RETRY_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CosClientException("COS 上传重试被中断", exception);
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() { client.shutdown(); }

    private static void validateKey(String tenantId, String objectKey, String objectPrefix) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(objectKey)
                || !objectKey.startsWith(tenantId + "/" + objectPrefix + "/") || objectKey.contains("..")) {
            throw new IllegalArgumentException("商品图片对象 key 无效");
        }
    }

    private static String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.strip();
        if (!value.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")) {
            throw new IllegalStateException("商品图片 COS object-prefix 必须是安全的相对路径");
        }
        return value;
    }

    private static void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) throw new IllegalStateException(name + "未配置");
    }

    private static String normalizedCredential(String value) {
        return value == null ? null : value.strip();
    }
}
