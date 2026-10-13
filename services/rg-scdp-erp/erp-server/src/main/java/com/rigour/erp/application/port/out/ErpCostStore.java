package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.ErpCostActionCommand;
import com.rigour.erp.api.v1.model.ErpCostAuditView;
import com.rigour.erp.api.v1.model.ErpCostCommand;
import com.rigour.erp.api.v1.model.ErpCostDetailView;
import com.rigour.erp.api.v1.model.ErpCostOptionView;
import com.rigour.erp.api.v1.model.ErpCostPageView;
import com.rigour.erp.api.v1.model.ErpCostQuery;
import com.rigour.erp.api.v1.model.ErpCostSourceView;

import java.util.List;

public interface ErpCostStore {
    ErpCostPageView entries(String tenant, String category, ErpCostQuery query, String action);

    ErpCostDetailView entry(String tenant, String category, String id, String action);

    ErpCostDetailView save(
            String tenant,
            String category,
            String id,
            ErpCostCommand command,
            List<ErpCostSourceView> sources,
            String warehouseName,
            String employeeName,
            String actor,
            String action);

    ErpCostDetailView transition(
            String tenant,
            String category,
            String id,
            ErpCostActionCommand command,
            String actor,
            String action);

    List<ErpCostAuditView> history(String tenant, String id);

    List<ErpCostSourceView> sources(
            String tenant, String leg, String keyword, List<Long> ids, String action);

    List<ErpCostOptionView> warehouses(String tenant, String keyword, String action);

    String warehouse(String tenant, Long id, String action);
}
