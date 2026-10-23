package com.rigour.merchant.application.port.out;

import com.rigour.hr.api.v1.model.HrCostCityReference;

import java.util.List;

public interface CostOperatingCitySource {
    List<HrCostCityReference> cities(String action);
}
