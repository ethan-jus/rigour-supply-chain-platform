package com.rigour.sales.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 高德 Web 服务配置；key 只从运行环境注入，不写入 Git/Nacos/日志。 */
@ConfigurationProperties(prefix = "rigour.amap")
public class AmapProperties {

    private String baseUrl = "https://restapi.amap.com/v3";
    private String webKey;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);
    private Duration nearbyCacheTtl = Duration.ofSeconds(60);
    private int nearbyCacheMaxEntries = 500;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getWebKey() {
        return webKey;
    }

    public void setWebKey(String webKey) {
        this.webKey = webKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getNearbyCacheTtl() {
        return nearbyCacheTtl;
    }

    public void setNearbyCacheTtl(Duration nearbyCacheTtl) {
        this.nearbyCacheTtl = nearbyCacheTtl;
    }

    public int getNearbyCacheMaxEntries() {
        return nearbyCacheMaxEntries;
    }

    public void setNearbyCacheMaxEntries(int nearbyCacheMaxEntries) {
        this.nearbyCacheMaxEntries = nearbyCacheMaxEntries;
    }
}
