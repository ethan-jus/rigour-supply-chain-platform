package com.rigour.sales.temporarycheckin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 临时打卡表单的运行配置。租户只能由服务端配置注入，不接受浏览器传入。
 */
@Component
@ConfigurationProperties(prefix = "rigour.sales.temporary-checkin")
public class TemporaryCheckinProperties {

    private boolean enabled;
    private String tenantId;
    private long maxStorefrontPhotoBytes = 10L * 1024 * 1024;
    private long maxWechatScreenshotBytes = 10L * 1024 * 1024;
    private long maxAudioBytes = 25L * 1024 * 1024;
    private int maxCheckinDistanceMeters = 300;
    private int maxCheckinAccuracyMeters = 200;
    private int maxLocationAgeMinutes = 60;
    private boolean identityEnforcementEnabled = true;
    private String identitySigningKeyBase64;
    private String riskHmacKeyBase64;
    private String trustedProxyMarker;
    private int credentialPbkdf2Iterations = 210_000;
    private int identityTtlDays = 30;
    private int deviceTtlDays = 365;
    private int riskIpNetworksPerDay = 4;
    private int riskDevicesPerDay = 3;

    private List<String> cities = new ArrayList<>(List.of(
            "北京", "深圳", "杭州", "成都", "武汉", "西安", "长沙", "南京", "石家庄", "重庆",
            "苏州", "金华", "东莞", "上海", "洛阳", "广州", "总部"));
    private List<String> storeAttributes = new ArrayList<>(List.of("台球", "游泳馆", "网球", "足球"));
    private List<String> operatingStatuses = new ArrayList<>(List.of("营业中", "暂停营业", "倒闭", "装修中"));
    private List<String> areaRanges = new ArrayList<>(List.of(
            "100平米以下", "100-300平米", "300-600平米", "600平米以上"));
    private List<String> businessTypes = new ArrayList<>(List.of("竞技赛事", "培训教育", "商业娱乐", "综合经营"));
    private List<String> intendedBusinesses = new ArrayList<>(List.of("高德业务", "零售业务", "台球周边", "鹰眼业务"));
    private List<String> cooperationIntents = new ArrayList<>(List.of("高意向", "中意向", "低意向", "无意向"));
    private List<String> storeGrades = new ArrayList<>(List.of("A类", "B类", "C类"));
    private List<String> storeTags = new ArrayList<>(List.of("追分", "连锁", "单店", "好沟通", "品牌店", "可动销"));

    public UUID requireTenantId() {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("rigour.sales.temporary-checkin.tenant-id未配置");
        }
        try {
            return UUID.fromString(tenantId.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("rigour.sales.temporary-checkin.tenant-id必须是UUID", exception);
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public long getMaxStorefrontPhotoBytes() { return maxStorefrontPhotoBytes; }
    public void setMaxStorefrontPhotoBytes(long value) { this.maxStorefrontPhotoBytes = value; }
    public long getMaxWechatScreenshotBytes() { return maxWechatScreenshotBytes; }
    public void setMaxWechatScreenshotBytes(long value) { this.maxWechatScreenshotBytes = value; }
    public long getMaxAudioBytes() { return maxAudioBytes; }
    public void setMaxAudioBytes(long value) { this.maxAudioBytes = value; }
    public int getMaxCheckinDistanceMeters() { return maxCheckinDistanceMeters; }
    public void setMaxCheckinDistanceMeters(int value) { this.maxCheckinDistanceMeters = value; }
    public int getMaxCheckinAccuracyMeters() { return maxCheckinAccuracyMeters; }
    public void setMaxCheckinAccuracyMeters(int value) { this.maxCheckinAccuracyMeters = value; }
    public int getMaxLocationAgeMinutes() { return maxLocationAgeMinutes; }
    public void setMaxLocationAgeMinutes(int value) { this.maxLocationAgeMinutes = value; }
    public boolean isIdentityEnforcementEnabled() { return identityEnforcementEnabled; }
    public void setIdentityEnforcementEnabled(boolean value) { identityEnforcementEnabled = value; }
    public String getIdentitySigningKeyBase64() { return identitySigningKeyBase64; }
    public void setIdentitySigningKeyBase64(String value) { identitySigningKeyBase64 = value; }
    public String getRiskHmacKeyBase64() { return riskHmacKeyBase64; }
    public void setRiskHmacKeyBase64(String value) { riskHmacKeyBase64 = value; }
    public String getTrustedProxyMarker() { return trustedProxyMarker; }
    public void setTrustedProxyMarker(String value) { trustedProxyMarker = value; }
    public int getCredentialPbkdf2Iterations() { return credentialPbkdf2Iterations; }
    public void setCredentialPbkdf2Iterations(int value) { credentialPbkdf2Iterations = value; }
    public int getIdentityTtlDays() { return identityTtlDays; }
    public void setIdentityTtlDays(int value) { identityTtlDays = value; }
    public int getDeviceTtlDays() { return deviceTtlDays; }
    public void setDeviceTtlDays(int value) { deviceTtlDays = value; }
    public int getRiskIpNetworksPerDay() { return riskIpNetworksPerDay; }
    public void setRiskIpNetworksPerDay(int value) { riskIpNetworksPerDay = value; }
    public int getRiskDevicesPerDay() { return riskDevicesPerDay; }
    public void setRiskDevicesPerDay(int value) { riskDevicesPerDay = value; }
    public List<String> getCities() { return List.copyOf(cities); }
    public void setCities(List<String> value) { cities = copy(value); }
    public List<String> getStoreAttributes() { return List.copyOf(storeAttributes); }
    public void setStoreAttributes(List<String> value) { storeAttributes = copy(value); }
    public List<String> getOperatingStatuses() { return List.copyOf(operatingStatuses); }
    public void setOperatingStatuses(List<String> value) { operatingStatuses = copy(value); }
    public List<String> getAreaRanges() { return List.copyOf(areaRanges); }
    public void setAreaRanges(List<String> value) { areaRanges = copy(value); }
    public List<String> getBusinessTypes() { return List.copyOf(businessTypes); }
    public void setBusinessTypes(List<String> value) { businessTypes = copy(value); }
    public List<String> getIntendedBusinesses() { return List.copyOf(intendedBusinesses); }
    public void setIntendedBusinesses(List<String> value) { intendedBusinesses = copy(value); }
    public List<String> getCooperationIntents() { return List.copyOf(cooperationIntents); }
    public void setCooperationIntents(List<String> value) { cooperationIntents = copy(value); }
    public List<String> getStoreGrades() { return List.copyOf(storeGrades); }
    public void setStoreGrades(List<String> value) { storeGrades = copy(value); }
    public List<String> getStoreTags() { return List.copyOf(storeTags); }
    public void setStoreTags(List<String> value) { storeTags = copy(value); }

    private static ArrayList<String> copy(List<String> value) {
        return value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
}
