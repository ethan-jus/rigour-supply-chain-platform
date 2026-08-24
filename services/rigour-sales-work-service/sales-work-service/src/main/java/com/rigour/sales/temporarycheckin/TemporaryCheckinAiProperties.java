package com.rigour.sales.temporarycheckin;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 临时打卡录音转写与摘要配置。
 *
 * <p>凭据必须从 {@code rigour.sales.temporary-checkin.ai} 独立注入，不复用 COS 凭据。</p>
 */
@Component
@ConfigurationProperties(prefix = "rigour.sales.temporary-checkin.ai")
@ConditionalOnProperty(
        prefix = "rigour.sales.temporary-checkin.ai",
        name = "enabled",
        havingValue = "true")
public class TemporaryCheckinAiProperties {

    private static final int MAX_CONFIGURED_INPUT_CHARACTERS = 200_000;
    private static final int MAX_CONFIGURED_OUTPUT_CHARACTERS = 8_000;

    private boolean enabled;
    private String secretId;
    private String secretKey;
    private String sessionToken;
    private String region = "ap-shanghai";
    private String asrEndpoint = "https://asr.tencentcloudapi.com";
    private String asrEngineModelType = "16k_zh_en";
    private int asrChannelNum = 1;
    private int asrResTextFormat;
    private String hunyuanEndpoint = "https://hunyuan.ai.tencentcloudapi.com";
    private String hunyuanModel = "hunyuan-turbos-latest";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(60);
    private int maxSummaryInputCharacters = 20_000;
    private int maxSummaryOutputCharacters = 2_000;

    /** 在客户端 Bean 创建时校验启用所需的全部配置。 */
    void requireConfigured() {
        requireText(secretId, "rigour.sales.temporary-checkin.ai.secret-id");
        requireText(secretKey, "rigour.sales.temporary-checkin.ai.secret-key");
        requireHeaderSafe(secretId, "rigour.sales.temporary-checkin.ai.secret-id");
        requireHeaderSafe(secretKey, "rigour.sales.temporary-checkin.ai.secret-key");
        if (StringUtils.hasText(sessionToken)) {
            requireHeaderSafe(sessionToken, "rigour.sales.temporary-checkin.ai.session-token");
        }
        requireText(region, "rigour.sales.temporary-checkin.ai.region");
        requireHeaderSafe(region, "rigour.sales.temporary-checkin.ai.region");
        requireText(asrEndpoint, "rigour.sales.temporary-checkin.ai.asr-endpoint");
        requireText(asrEngineModelType, "rigour.sales.temporary-checkin.ai.asr-engine-model-type");
        requireHeaderSafe(asrEngineModelType, "rigour.sales.temporary-checkin.ai.asr-engine-model-type");
        requireText(hunyuanEndpoint, "rigour.sales.temporary-checkin.ai.hunyuan-endpoint");
        requireText(hunyuanModel, "rigour.sales.temporary-checkin.ai.hunyuan-model");
        requireHeaderSafe(hunyuanModel, "rigour.sales.temporary-checkin.ai.hunyuan-model");
        requirePositive(connectTimeout, "rigour.sales.temporary-checkin.ai.connect-timeout");
        requirePositive(readTimeout, "rigour.sales.temporary-checkin.ai.read-timeout");
        if (asrChannelNum != 1 && asrChannelNum != 2) {
            throw new IllegalStateException("rigour.sales.temporary-checkin.ai.asr-channel-num只能为1或2");
        }
        if (asrResTextFormat < 0 || asrResTextFormat > 3) {
            throw new IllegalStateException("rigour.sales.temporary-checkin.ai.asr-res-text-format必须为0至3");
        }
        if (maxSummaryInputCharacters < 128
                || maxSummaryInputCharacters > MAX_CONFIGURED_INPUT_CHARACTERS) {
            throw new IllegalStateException(
                    "rigour.sales.temporary-checkin.ai.max-summary-input-characters必须为128至200000");
        }
        if (maxSummaryOutputCharacters < 128
                || maxSummaryOutputCharacters > MAX_CONFIGURED_OUTPUT_CHARACTERS) {
            throw new IllegalStateException(
                    "rigour.sales.temporary-checkin.ai.max-summary-output-characters必须为128至8000");
        }
    }

    private static void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) throw new IllegalStateException(name + "未配置");
    }

    private static void requireHeaderSafe(String value, String name) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalStateException(name + "包含非法换行符");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + "必须大于0");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSecretId() { return secretId; }
    public void setSecretId(String secretId) { this.secretId = secretId; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getAsrEndpoint() { return asrEndpoint; }
    public void setAsrEndpoint(String asrEndpoint) { this.asrEndpoint = asrEndpoint; }
    public String getAsrEngineModelType() { return asrEngineModelType; }
    public void setAsrEngineModelType(String value) { this.asrEngineModelType = value; }
    public int getAsrChannelNum() { return asrChannelNum; }
    public void setAsrChannelNum(int asrChannelNum) { this.asrChannelNum = asrChannelNum; }
    public int getAsrResTextFormat() { return asrResTextFormat; }
    public void setAsrResTextFormat(int value) { this.asrResTextFormat = value; }
    public String getHunyuanEndpoint() { return hunyuanEndpoint; }
    public void setHunyuanEndpoint(String hunyuanEndpoint) { this.hunyuanEndpoint = hunyuanEndpoint; }
    public String getHunyuanModel() { return hunyuanModel; }
    public void setHunyuanModel(String hunyuanModel) { this.hunyuanModel = hunyuanModel; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getMaxSummaryInputCharacters() { return maxSummaryInputCharacters; }
    public void setMaxSummaryInputCharacters(int value) { this.maxSummaryInputCharacters = value; }
    public int getMaxSummaryOutputCharacters() { return maxSummaryOutputCharacters; }
    public void setMaxSummaryOutputCharacters(int value) { this.maxSummaryOutputCharacters = value; }
}
