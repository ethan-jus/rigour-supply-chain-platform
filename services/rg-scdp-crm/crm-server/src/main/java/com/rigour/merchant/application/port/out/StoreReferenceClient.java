package com.rigour.merchant.application.port.out;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;

public interface StoreReferenceClient {
    StoreAccessView access(String action);
    AdminDivisionCatalog divisions();
    java.util.Set<String> storeTypeCodes(String tenant);
}
