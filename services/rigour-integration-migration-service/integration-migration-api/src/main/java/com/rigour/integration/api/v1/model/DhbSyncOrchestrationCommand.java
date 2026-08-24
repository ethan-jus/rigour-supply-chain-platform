package com.rigour.integration.api.v1.model;

/** Portal 发起订货宝统一同步的参数。 */
public record DhbSyncOrchestrationCommand(
        /** 最多读取页数，范围 1..100；省略时由同步中心配置决定。 */
        Integer maxPages,
        /** 是否同步 ERP 商品和供应链对象；省略时为 true。 */
        Boolean includeErp,
        /** 是否同步 CRM 客户主数据；省略时为 true。 */
        Boolean includeCrm,
        /** 是否同步 Order 订单域；省略时为 true。 */
        Boolean includeOrder,
        /** 是否先同步 IAM 人员主档和订货宝员工来源映射；省略时为 true。 */
        Boolean includeIam,
        /** 是否纳入业务字典前置步骤；省略时为 true。字典值由各领域按白名单批量补齐。 */
        Boolean includeDictionary) {

    public DhbSyncOrchestrationCommand(Integer maxPages, Boolean includeErp,
                                       Boolean includeCrm, Boolean includeOrder,
                                       Boolean includeIam) {
        this(maxPages, includeErp, includeCrm, includeOrder, includeIam, null);
    }
}
