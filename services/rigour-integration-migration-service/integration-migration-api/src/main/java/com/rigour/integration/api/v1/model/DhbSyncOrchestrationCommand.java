package com.rigour.integration.api.v1.model;

import java.time.Instant;

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
        Boolean includeDictionary,
        /** 是否同步 ERP 商品主数据；省略时沿用 includeErp。 */
        Boolean includeErpProduct,
        /** 是否同步 ERP 供应链数据；省略时沿用 includeErp。 */
        Boolean includeErpSupply,
        /** 手动同步窗口开始时间；必须与 to 同时提供。 */
        Instant from,
        /** 手动同步窗口结束时间；必须与 from 同时提供。 */
        Instant to) {

    public DhbSyncOrchestrationCommand {
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException("订货宝统一同步窗口from和to必须同时提供");
        }
        if (from != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("订货宝统一同步窗口from必须早于to");
        }
    }

    public DhbSyncOrchestrationCommand(Integer maxPages, Boolean includeErp,
                                       Boolean includeCrm, Boolean includeOrder,
                                       Boolean includeIam, Boolean includeDictionary) {
        this(maxPages, includeErp, includeCrm, includeOrder, includeIam, includeDictionary,
                null, null, null, null);
    }

    public DhbSyncOrchestrationCommand(Integer maxPages, Boolean includeErp,
                                       Boolean includeCrm, Boolean includeOrder,
                                       Boolean includeIam) {
        this(maxPages, includeErp, includeCrm, includeOrder, includeIam, null,
                null, null, null, null);
    }

    public DhbSyncOrchestrationCommand(Integer maxPages, Boolean includeErp,
                                       Boolean includeCrm, Boolean includeOrder,
                                       Boolean includeIam, Boolean includeDictionary,
                                       Boolean includeErpProduct, Boolean includeErpSupply) {
        this(maxPages, includeErp, includeCrm, includeOrder, includeIam, includeDictionary,
                includeErpProduct, includeErpSupply, null, null);
    }
}
