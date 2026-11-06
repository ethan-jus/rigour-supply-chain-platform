package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.ProcurementCostSource;
import java.util.Optional;

public interface ProcurementCostSources {
    Optional<ProcurementCostSource> source(String tenant, String code);

}
