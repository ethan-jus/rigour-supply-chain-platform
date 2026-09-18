package com.rigour.erp.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** ERP 商品图片 COS 访问配置；凭据只从部署 Secret 注入。 */
@ConfigurationProperties(prefix = "rigour.erp.product-media")
public class ProductMediaAccessProperties {
    private Duration urlTtl = Duration.ofMinutes(10);
    private final Cos cos = new Cos();

    public Duration getUrlTtl() { return urlTtl; }
    public void setUrlTtl(Duration urlTtl) { this.urlTtl = urlTtl; }
    public Cos getCos() { return cos; }

    public static class Cos {
        /** 商品图片对象 Key 前缀；必须是安全的相对路径。 */
        private String objectPrefix = "product-images";
        private String region;
        private String bucket;
        private String secretId;
        private String secretKey;
        private String sessionToken;
        private int connectionTimeoutMs = 5_000;
        private int socketTimeoutMs = 30_000;

        public String getObjectPrefix() { return objectPrefix; }
        public void setObjectPrefix(String objectPrefix) { this.objectPrefix = objectPrefix; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getSecretId() { return secretId; }
        public void setSecretId(String secretId) { this.secretId = secretId; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getSessionToken() { return sessionToken; }
        public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }
        public int getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public void setConnectionTimeoutMs(int connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }
        public int getSocketTimeoutMs() { return socketTimeoutMs; }
        public void setSocketTimeoutMs(int socketTimeoutMs) { this.socketTimeoutMs = socketTimeoutMs; }
    }
}
