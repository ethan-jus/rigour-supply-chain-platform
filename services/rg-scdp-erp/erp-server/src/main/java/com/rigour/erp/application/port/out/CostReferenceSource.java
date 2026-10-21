package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.ErpCostEmployeeView;
import com.rigour.erp.api.v1.model.ErpCostSourceView;

import java.util.List;

public interface CostReferenceSource {
    String city(String code, String action);

    List<ErpCostEmployeeView> employees(String action);

    List<ErpCostSourceView> deliveries(String keyword, List<Long> ids);

    default List<ErpCostSourceView> deliveries(
            String keyword, List<Long> ids, String city, int begin, String action) {
        return deliveries(keyword, ids);
    }
}
