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
    LogisticsCostPageView entries(
            String tenant, String category, LogisticsCostQuery query, String action);

    LogisticsCostDetailView entry(String tenant, String category, String id, String action);

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

    List<LogisticsCostAuditView> history(String tenant, String id);

    List<LogisticsCostSourceView> sources(
            String tenant, String leg, String keyword, List<Long> ids, String action);
}
