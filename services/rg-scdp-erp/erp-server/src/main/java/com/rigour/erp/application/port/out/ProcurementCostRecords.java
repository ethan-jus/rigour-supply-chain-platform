package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.*;

import java.time.LocalDate;

public interface ProcurementCostRecords {
    MasterDataPageView<ProcurementCostRecordView> page(
            String tenant,
            int begin,
            int step,
            String number,
            String supplier,
            String status,
            LocalDate from,
            LocalDate to,
            String action);

    ProcurementCostRecordView record(String tenant, String code, String action, boolean lock);

    java.util.List<String> freightRecordIds(String tenant, long orderId);
}
