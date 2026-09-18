package com.rigour.sales.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 拜访照片技术约束；业务必传数量仍来自版本化拜访规则。 */
@ConfigurationProperties(prefix = "sales.evidence")
public class SalesEvidenceProperties {

    /** 单张门头照最大12MB，避免H5弱网请求和服务端内存失控。 */
    private long maxPhotoBytes = 12L * 1024 * 1024;
    /** 门头照最小边界，过滤图标、缩略图和无可审核细节的伪文件。 */
    private int minPhotoWidth = 320;
    private int minPhotoHeight = 240;
    /** 解码前先限制总像素，防止小体积高压缩图片造成内存放大。 */
    private long maxPhotoPixels = 25_000_000L;

    public long getMaxPhotoBytes() {
        return maxPhotoBytes;
    }

    public void setMaxPhotoBytes(long maxPhotoBytes) {
        this.maxPhotoBytes = maxPhotoBytes;
    }

    public int getMinPhotoWidth() {
        return minPhotoWidth;
    }

    public void setMinPhotoWidth(int minPhotoWidth) {
        this.minPhotoWidth = minPhotoWidth;
    }

    public int getMinPhotoHeight() {
        return minPhotoHeight;
    }

    public void setMinPhotoHeight(int minPhotoHeight) {
        this.minPhotoHeight = minPhotoHeight;
    }

    public long getMaxPhotoPixels() {
        return maxPhotoPixels;
    }

    public void setMaxPhotoPixels(long maxPhotoPixels) {
        this.maxPhotoPixels = maxPhotoPixels;
    }
}
