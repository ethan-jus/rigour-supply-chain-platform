package com.rigour.integration.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 商品图片对象存储配置；Secret 只从部署环境注入，非敏感资源参数可由 Nacos 管理。 */
@ConfigurationProperties(prefix = "rigour.integration.product-media")
public class ProductMediaProperties {
    /** 单张图片最大字节数，防止供应商异常响应耗尽内存。 */
    private long maxBytes = 10L * 1024 * 1024;
    /** 后台消费者总开关；本地单条重放可关闭，避免误领共享DEV图片队列。 */
    private boolean workerEnabled = true;
    /** 后台消费者并发数；按实例限制，避免图片任务打满连接池和 COS。 */
    private int workerConcurrency = 4;
    private long workerPollIntervalMs = 1000L;
    private int workerMaxAttempts = 3;
    /** 订货宝收付款附件对象 Key 前缀；与商品图片共用 COS 桶但分目录隔离。 */
    private String fundAttachmentPrefix = "fund-attachments";
    private final Cos cos = new Cos();

    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }
    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean workerEnabled) { this.workerEnabled = workerEnabled; }
    public int getWorkerConcurrency() { return workerConcurrency; }
    public void setWorkerConcurrency(int workerConcurrency) { this.workerConcurrency = workerConcurrency; }
    public long getWorkerPollIntervalMs() { return workerPollIntervalMs; }
    public void setWorkerPollIntervalMs(long workerPollIntervalMs) { this.workerPollIntervalMs = workerPollIntervalMs; }
    public int getWorkerMaxAttempts() { return workerMaxAttempts; }
    public void setWorkerMaxAttempts(int workerMaxAttempts) { this.workerMaxAttempts = workerMaxAttempts; }
    public String getFundAttachmentPrefix() { return fundAttachmentPrefix; }
    public void setFundAttachmentPrefix(String fundAttachmentPrefix) { this.fundAttachmentPrefix = fundAttachmentPrefix; }
    public Cos getCos() { return cos; }

    public static class Cos {
        /** 商品图片对象 Key 前缀；不能以斜杠开头或结尾。 */
        private String objectPrefix = "product-images";
        private String region;
        private String bucket;
        private String secretId;
        private String secretKey;
        private String sessionToken;
        private int connectionTimeoutMs = 5_000;
        /** 单次 COS 读写无数据超过该时长才判定超时；商品图片上传保留慢链路余量。 */
        private int socketTimeoutMs = 60_000;
        private boolean serverSideEncryption = true;

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
        public boolean isServerSideEncryption() { return serverSideEncryption; }
        public void setServerSideEncryption(boolean serverSideEncryption) { this.serverSideEncryption = serverSideEncryption; }
    }
}
