package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.ErpCostEmployeeView;
import com.rigour.erp.api.v1.model.ErpCostSourceView;

import java.util.List;

public interface CostReferenceSource {

    List<ErpCostEmployeeView> employees(String action);

    List<ErpCostSourceView> deliveries(String keyword, List<Long> ids);
}
