package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.InternalCustomerCommand;
import com.rigour.merchant.api.v1.model.InternalCustomerDetailView;
import com.rigour.merchant.api.v1.model.InternalCustomerSummaryView;
import com.rigour.merchant.api.v1.model.PageView;
import java.util.Optional;

/** CRM 自研客户表持久化端口；只操作 `crm_customer`，不访问旧 Party/订货宝投影表。 */
public interface CrmInternalCustomerStore {

    PageView<InternalCustomerSummaryView> customers(String tenantId, int begin, int step,
                                                    CustomerSearchCriteria criteria);

    Optional<InternalCustomerDetailView> customer(String tenantId, Long id);

    boolean existsByCode(String tenantId, String customerCode);

    InternalCustomerDetailView create(String tenantId, String customerCode,
                                      InternalCustomerCommand command, String actorId);

    InternalCustomerDetailView update(String tenantId, Long id,
                                      InternalCustomerCommand command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    /** 列表页独立筛选条件；避免 keyword 式 OR 查询失控。 */
    record CustomerSearchCriteria(
            String customerCode,
            String customerName,
            String contactPhone,
            String customerTypeCode,
            String regionCode,
            String ownerSalesUserId,
            String ownerStaffCode,
            String statusCode) {
    }
}
