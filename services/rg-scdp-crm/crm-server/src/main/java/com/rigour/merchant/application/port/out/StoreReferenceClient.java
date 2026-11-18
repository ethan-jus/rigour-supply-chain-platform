package com.rigour.merchant.application.port.out;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;

public interface StoreReferenceClient {
    StoreAccessView access(String action);
    java.util.List<com.rigour.settings.api.v1.model.EffectiveDictView> dictionaries(String tenant,java.util.List<String> codes);
    java.util.Set<String> businessStatusCodes(String tenant);
    AdminDivisionCatalog divisions();
    java.util.Set<String> storeTypeCodes(String tenant);
    java.util.List<com.rigour.settings.api.v1.model.DictItemView> storeTypes(String tenant);
}
