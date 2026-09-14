package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.SalesOrderProductRepair.Candidate;
import java.util.List;

/** 只读 ERP 版本化商品查询；结果不完整时必须失败，不得对截断结果宣称唯一。 */
public interface ErpOrderRepairCatalog {
    record Query(String productCode, String productName, String skuCode, String specification) { }
    List<Candidate> candidates(String tenantId, Query query);
}
