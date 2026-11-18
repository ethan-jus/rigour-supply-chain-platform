package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.LogisticsCostSourceView;

import java.util.List;
import java.util.Set;

public interface OrderLogisticsReferences {
    default java.util.Map<String, List<com.rigour.order.api.v1.model.LogisticsLedgerPartyView>>
            ledgerCustomersByCities(
                    java.util.Map<String, List<String>> codesByCity, String action) {
        var result =
                new java.util.LinkedHashMap<
                        String, List<com.rigour.order.api.v1.model.LogisticsLedgerPartyView>>();
        codesByCity.forEach(
                (city, codes) -> result.put(city, ledgerCustomers(city, codes, action)));
        return result;
    }

    java.util.Map<String, String> cities(String action);

    default List<com.rigour.order.api.v1.model.LogisticsLedgerPartyView> ledgerCustomers(
            String city, List<String> codes, String action) {
        return codes.stream()
                .flatMap(
                        code ->
                                ledgerParties("CUSTOMER", code, null, null, city, 0, action)
                                        .stream())
                .toList();
    }

    default List<com.rigour.order.api.v1.model.LogisticsLedgerPartyView> ledgerParties(
            String kind,
            String code,
            Long id,
            String keyword,
            String city,
            int begin,
            String action) {
        throw new UnsupportedOperationException("编码收发方引用尚未接入");
    }

    default List<LogisticsCostSourceView> ledgerSources(
            com.rigour.order.api.v1.model.LogisticsLedgerSourceQuery query, String action) {
        throw new UnsupportedOperationException("编码单据引用尚未接入");
    }

    List<com.rigour.order.api.v1.model.LogisticsCostEmployeeView> employees(String action);

    /** 按稳定编码核验最小员工身份，供自动同步补齐缺失部门，不读取人事个人资料。 */
    default List<com.rigour.order.api.v1.model.LogisticsCostEmployeeView> employeeDepartments(
            Set<String> codes, String action) {
        return employees(action).stream().filter(e -> codes.contains(e.employeeCode())).toList();
    }

    default List<com.rigour.order.api.v1.model.LogisticsCostEmployeeView> employeeDepartments(
            com.rigour.shared.context.CallerIdentity caller, Set<String> codes, String action) {
        throw new UnsupportedOperationException("员工部门最小身份核验尚未配置");
    }

    String city(String code, String action);

    String party(String kind, Long id, String city, String action);

    com.rigour.order.api.v1.model.LogisticsCostWarehouseView warehouseDetail(
            Long id, String action);

    String warehouse(Long id, String action);

    Set<String> validCodes(String tenant, String code);

    List<LogisticsCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action);

    default List<LogisticsCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action, String city, int begin) {
        return sources(leg, keyword, ids, action);
    }
}
