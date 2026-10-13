package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.HrCostEmployeeView;

import java.util.List;

public interface HrCostReferenceStore {
    List<HrCostEmployeeView> employees(String tenant, String action);
}
