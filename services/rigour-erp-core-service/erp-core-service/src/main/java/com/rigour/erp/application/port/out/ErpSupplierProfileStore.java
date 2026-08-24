package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.InternalSupplierProfileCommand;
import com.rigour.erp.api.v1.model.InternalSupplierProfileView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import java.util.Optional;

/** ERP 自研供应商档案持久化端口。 */
public interface ErpSupplierProfileStore {
    MasterDataPageView<InternalSupplierProfileView> suppliers(
            String tenantId, int begin, int step, SupplierSearchCriteria criteria);

    Optional<InternalSupplierProfileView> supplier(String tenantId, Long id);

    boolean existsByCode(String tenantId, String supplierCode);

    InternalSupplierProfileView create(
            String tenantId, String supplierCode, InternalSupplierProfileCommand command, String actorId);

    InternalSupplierProfileView update(
            String tenantId, Long id, InternalSupplierProfileCommand command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    /** 供应商列表独立筛选条件；不使用 keyword 聚合字段。 */
    record SupplierSearchCriteria(
            String supplierCode,
            String supplierName,
            String contactPhone,
            String statusCode) {
    }
}
