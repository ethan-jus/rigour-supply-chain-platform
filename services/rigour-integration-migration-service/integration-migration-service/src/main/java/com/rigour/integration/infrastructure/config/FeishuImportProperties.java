package com.rigour.integration.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 飞书导出文件导入配置；用于限制单次预检资源占用。 */
@ConfigurationProperties(prefix = "rigour.integration.feishu.import-bundle")
public class FeishuImportProperties {
    private boolean enabled = true;
    private long maxBytes = 100L * 1024 * 1024;
    private int maxSheets = 100;
    private int maxColumns = 300;
    private long maxRowsPerSheet = 1_000_000L;
    private int sampleRows = 3;
    /** 批次未填写飞书Base地址时的兜底来源，用于服务端回查附件 token。 */
    private String defaultSourceUrl = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getMaxSheets() {
        return maxSheets;
    }

    public void setMaxSheets(int maxSheets) {
        this.maxSheets = maxSheets;
    }

    public int getMaxColumns() {
        return maxColumns;
    }

    public void setMaxColumns(int maxColumns) {
        this.maxColumns = maxColumns;
    }

    public long getMaxRowsPerSheet() {
        return maxRowsPerSheet;
    }

    public void setMaxRowsPerSheet(long maxRowsPerSheet) {
        this.maxRowsPerSheet = maxRowsPerSheet;
    }

    public int getSampleRows() {
        return sampleRows;
    }

    public void setSampleRows(int sampleRows) {
        this.sampleRows = sampleRows;
    }

    public String getDefaultSourceUrl() {
        return defaultSourceUrl;
    }

    public void setDefaultSourceUrl(String defaultSourceUrl) {
        this.defaultSourceUrl = defaultSourceUrl;
    }

    public void validate() {
        if (maxBytes < 1024 * 1024 || maxBytes > 1024L * 1024 * 1024
                || maxSheets < 1 || maxSheets > 500
                || maxColumns < 1 || maxColumns > 2000
                || maxRowsPerSheet < 1 || maxRowsPerSheet > 5_000_000
                || sampleRows < 0 || sampleRows > 20) {
            throw new IllegalStateException("飞书导入预检配置无效");
        }
    }
}
