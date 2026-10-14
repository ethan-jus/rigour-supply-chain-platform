package com.rigour.hr.application.port.out;

import com.rigour.erp.api.v1.model.ErpPayrollCategoryView;
import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.order.api.v1.model.OrderCollectionSourceView;
import com.rigour.order.api.v1.model.OrderCommissionSourceLineView;
import com.rigour.sales.api.v1.model.SalesPayrollEvidenceView;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface HrPayrollSource {
    SalesPayrollEvidenceView performanceEvidence(
            String tenant, String employee, LocalDate from, LocalDate to);

    List<OrderCollectionSourceView> collections(
            String tenant, String employee, LocalDate from, LocalDate to);

    List<OrderCommissionSourceLineView> commissions(
            String tenant, String employee, String city, LocalDate through);

    Set<String> dictionary(String tenant, String code);

    List<ErpPayrollProductView> products(
            String tenant, String keyword, Long variant, Long categoryId);

    List<ErpPayrollCategoryView> productCategories(String tenant);

    List<ErpPayrollProductView> productsByVariants(String tenant, List<Long> ids);

    ErpPayrollProductView requireProduct(String tenant, Long product, Long variant, String unit);
}
