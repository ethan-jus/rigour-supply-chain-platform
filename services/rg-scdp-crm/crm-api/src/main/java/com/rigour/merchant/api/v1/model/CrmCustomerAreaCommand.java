package com.rigour.merchant.api.v1.model;

/** CRM 客户区域/城市维护命令。 */
public record CrmCustomerAreaCommand(
        String areaName,
        String parentAreaCode,
        String status,
        Integer revision,
        Integer sortOrder,
        /** 订货宝展示编号；null保留已有编号，空字符串清空，不改变来源绑定。 */
        String sourceCode) {
    public CrmCustomerAreaCommand(String areaName, String parentAreaCode, String status, Integer revision, Integer sortOrder) {
        this(areaName, parentAreaCode, status, revision, sortOrder, null);
    }
    public CrmCustomerAreaCommand(String areaName, String parentAreaCode, String status, Integer revision) {
        this(areaName, parentAreaCode, status, revision, null);
    }
}
