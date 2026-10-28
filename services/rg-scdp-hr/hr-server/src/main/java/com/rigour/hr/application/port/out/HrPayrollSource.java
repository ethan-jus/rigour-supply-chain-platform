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
    default List<com.rigour.order.api.v1.model.OrderCityCollectionSourceView> cityCollections(
            String tenant, String city, LocalDate from, LocalDate to) {
        throw new UnsupportedOperationException("城市到账来源尚未接入");
    }

    default com.rigour.settings.api.v1.model.StatutoryCalendarView statutoryCalendar(
            String tenant, int year) {
        throw new UnsupportedOperationException("法定节假日日历来源尚未接入");
    }

    SalesPayrollEvidenceView performanceEvidence(
            String tenant, String employee, LocalDate from, LocalDate to);

    List<OrderCollectionSourceView> collections(
            String tenant, String employee, LocalDate from, LocalDate to);

    List<OrderCommissionSourceLineView> commissions(
            String tenant, String employee, String city, LocalDate through);

    Set<String> dictionary(String tenant, String code);

    List<ErpPayrollProductView> products(
            String tenant, String keyword, Long variant, Long categoryId);

    List<ErpPayrollProductView> products(
            String tenant, String keyword, Long variant, Long categoryId, int begin);

    List<ErpPayrollCategoryView> productCategories(String tenant);

    List<ErpPayrollProductView> productsByVariants(String tenant, List<Long> ids);

    ErpPayrollProductView requireProduct(String tenant, Long product, Long variant, String unit);

    default List<com.rigour.order.api.v1.model.OrderPayrollLineView> payrollLines(
            String tenant, String employee, String city, LocalDate through) {
        throw new UnsupportedOperationException("整单付清来源尚未接入");
    }
}
