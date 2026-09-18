package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.ExternalCrmCustomerRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncResult;
import com.rigour.merchant.api.v1.model.InternalCustomerCommand;
import com.rigour.merchant.api.v1.model.InternalCustomerDetailView;
import com.rigour.merchant.api.v1.model.InternalCustomerSummaryView;
import com.rigour.merchant.api.v1.model.PageView;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import java.util.List;
import java.util.Optional;

/** CRM 客户主档持久化端口；通过稳定的 Party 关联独立收货信息与来源档案。 */
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

    ExternalCrmCustomerSyncResult syncExternalCustomers(String tenantId, String sourceSystem,
                                                        List<ExternalCrmCustomerRowCommand> rows,
                                                        String actorId,
                                                        BusinessCodeGenerator codeGenerator);

    /** 列表页独立筛选条件；避免 keyword 式 OR 查询失控。 */
    java.util.List<String> creators(String tenantId);

    record CustomerSearchCriteria(
            String customerCode,
            String customerName,
            String contactPhone,
            String customerTypeCode,
            String regionCode,
            String ownerSalesUserId,
            String ownerEmployeeCode,
            String statusCode, String sortBy, String sortDirection, String loginAccount,
            java.time.LocalDate createdFrom, java.time.LocalDate createdTo, String creatorName, String dhbCustomerCode, String dhbLinkStatus) {
        public CustomerSearchCriteria(String customerCode, String customerName, String contactPhone,
                String customerTypeCode, String regionCode, String ownerSalesUserId, String ownerEmployeeCode,
                String statusCode, String sortBy, String sortDirection, String loginAccount,
                java.time.LocalDate createdFrom, java.time.LocalDate createdTo, String creatorName) {
            this(customerCode,customerName,contactPhone,customerTypeCode,regionCode,ownerSalesUserId,ownerEmployeeCode,statusCode,sortBy,sortDirection,loginAccount,createdFrom,createdTo,creatorName,null,null);
        }
        public CustomerSearchCriteria(String customerCode, String customerName, String contactPhone,
                String customerTypeCode, String regionCode, String ownerSalesUserId, String ownerEmployeeCode,
                String statusCode, String sortBy, String sortDirection, String loginAccount,
                java.time.LocalDate createdFrom, java.time.LocalDate createdTo) {
            this(customerCode,customerName,contactPhone,customerTypeCode,regionCode,ownerSalesUserId,ownerEmployeeCode,statusCode,sortBy,sortDirection,loginAccount,createdFrom,createdTo,null);
        }
        public CustomerSearchCriteria(String customerCode, String customerName, String contactPhone,
                String customerTypeCode, String regionCode, String ownerSalesUserId,
                String ownerEmployeeCode, String statusCode, String sortBy, String sortDirection) {
            this(customerCode, customerName, contactPhone, customerTypeCode, regionCode,
                    ownerSalesUserId, ownerEmployeeCode, statusCode, sortBy, sortDirection, null, null, null);
        }
        public CustomerSearchCriteria(String customerCode, String customerName, String contactPhone,
                String customerTypeCode, String regionCode, String ownerSalesUserId,
                String ownerEmployeeCode, String statusCode) {
            this(customerCode, customerName, contactPhone, customerTypeCode, regionCode,
                    ownerSalesUserId, ownerEmployeeCode, statusCode, "businessCreatedAt", "desc");
        }
    }
}
