package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.WarehouseDepartmentView;

import java.util.List;

public interface WarehouseDepartments {
    List<WarehouseDepartmentView> departments(String action);
}
