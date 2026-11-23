package com.rigour.sales.application.port.out;

import com.rigour.hr.api.v1.model.HrFieldworkOrganization;
import com.rigour.merchant.api.v1.model.FieldworkSources;

import java.time.Instant;
import java.util.*;

public interface FieldworkDirectory {
    List<HrFieldworkOrganization> organization(UUID tenant, Instant instant, List<String> codes);

    Map<String, String> salesDepartments(UUID tenant);

    Map<Long, String> storeRegions(UUID tenant, List<Long> stores);

    FieldworkSources.Result sources(UUID tenant, FieldworkSources.Query query);
}
