package com.rigour.sales.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 拜访录音存储配置；非敏感资源参数由 application/Nacos 管理，COS 访问凭据由部署 Secret 注入。
 * 本地文件系统用于开发，生产通过 storage-type=cos 使用腾讯云 COS。
 */
@ConfigurationProperties(prefix = "sales.recording")
public class SalesRecordingProperties {

    /** 存储实现：filesystem 或 cos。 */
    private String storageType = "filesystem";

    /** 录音片段本地存储根目录；对象键始终带租户前缀。 */
    private String storageDir = "./data/sales-recordings";

    /** 单个片段最大字节数，默认 25MB（约 10 分钟 AAC）。 */
    private long maxClipBytes = 25L * 1024 * 1024;

    /** 小于该时长的片段视为误触或噪声，只登记审计元数据，不保存音频字节。 */
    private int minimumClipSeconds = 30;

    private final Cos cos = new Cos();

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public long getMaxClipBytes() {
        return maxClipBytes;
    }

    public void setMaxClipBytes(long maxClipBytes) {
        this.maxClipBytes = maxClipBytes;
    }

    public int getMinimumClipSeconds() {
        return minimumClipSeconds;
    }

    public void setMinimumClipSeconds(int minimumClipSeconds) {
        this.minimumClipSeconds = minimumClipSeconds;
    }

    public Cos getCos() {
        return cos;
    }

    public static class Cos {
        private String region;
        private String bucket;
        private String secretId;
        private String secretKey;
        private String sessionToken;
        private int connectionTimeoutMs = 5_000;
        private int socketTimeoutMs = 30_000;
        private boolean serverSideEncryption = true;

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
        public void setServerSideEncryption(boolean serverSideEncryption) {
            this.serverSideEncryption = serverSideEncryption;
        }
    }
}
