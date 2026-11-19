package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.LogisticsCostActionCommand;
import com.rigour.order.api.v1.model.LogisticsCostAuditView;
import com.rigour.order.api.v1.model.LogisticsCostCommand;
import com.rigour.order.api.v1.model.LogisticsCostDetailView;
import com.rigour.order.api.v1.model.LogisticsCostPageView;
import com.rigour.order.api.v1.model.LogisticsCostQuery;
import com.rigour.order.api.v1.model.LogisticsCostSourceView;

import java.util.List;

public interface OrderLogisticsCostStore {
    record DepartmentCandidate(String id, int revision, LogisticsCostCommand data) {}

    default List<String> departmentTenants() {
        return List.of();
    }

    default List<DepartmentCandidate> missingDepartments(String tenant, String afterId) {
        return List.of();
    }

    default boolean fillDepartments(
            String tenant,
            String id,
            int revision,
            java.util.Map<
                            OrderLogisticsReferences.DepartmentAt,
                            com.rigour.order.api.v1.model.LogisticsCostEmployeeView>
                    employees) {
        return false;
    }

    default List<LogisticsCostDetailView> linkedSales(
            String tenant, String orderNo, String action) {
        return List.of();
    }

    default java.util.Map<String, String> businessStatuses(
            String tenant, List<String> ids, String action) {
        return java.util.Map.of();
    }

    default List<LogisticsCostDetailView> trackingCandidates(
            String tenant, List<String> numbers, String carrierCode, String action) {
        return List.of();
    }

    LogisticsCostPageView entries(
            String tenant, String category, LogisticsCostQuery query, String action);

    LogisticsCostDetailView entry(String tenant, String category, String id, String action);

    List<LogisticsCostDetailView> selectedEntries(String tenant, List<String> ids, String action);

    LogisticsCostDetailView save(
            String tenant,
            String category,
            String id,
            LogisticsCostCommand command,
            List<LogisticsCostSourceView> sources,
            String warehouseName,
            String employeeName,
            String actor,
            String action);

    LogisticsCostDetailView transition(
            String tenant,
            String category,
            String id,
            LogisticsCostActionCommand command,
            String actor,
            String action);

    LogisticsCostDetailView lifecycle(
            String tenant,
            String category,
            String id,
            LogisticsCostActionCommand command,
            String actor,
            String permission,
            boolean delete);

    void attachmentChanged(
            String tenant,
            String category,
            String id,
            String actor,
            String operation,
            String description,
            String permission);

    void attachmentAdded(
            String tenant,
            String category,
            String id,
            String actor,
            String attachmentId,
            String action);

    List<LogisticsCostAuditView> history(String tenant, String id);

    List<LogisticsCostSourceView> sources(
            String tenant, String leg, String keyword, List<Long> ids, String action);

    default List<LogisticsCostSourceView> sources(
            String tenant,
            String leg,
            String keyword,
            List<Long> ids,
            String action,
            String city,
            int begin) {
        return sources(tenant, leg, keyword, ids, action);
    }
}
