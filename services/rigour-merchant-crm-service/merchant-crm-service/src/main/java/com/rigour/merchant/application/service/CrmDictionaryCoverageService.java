package com.rigour.merchant.application.service;

import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.Collected;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.SourceRecord;
import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** CRM 订货宝同步的显式字典字段白名单；不会遍历任意 sourceFields。 */
@Service
public final class CrmDictionaryCoverageService {
    private final BusinessDictionaryBatchClient client;

    public CrmDictionaryCoverageService(BusinessDictionaryBatchClient client) {
        this.client = client;
    }

    /** 自动补齐当前 CRM 对象批次中明确建模的状态和结算方式。 */
    public Audit sync(UUID tenantId, Collected data) {
        List<Observation> values = new ArrayList<>();
        for (SourceRecord item : data.items()) {
            if (data.objectType() == CrmMasterDataObjectType.CUSTOMER) {
                add(values, "DHB_CUSTOMER_STATUS", "customer.sourceStatus", item.sourceStatus(), null);
                add(values, "DHB_CUSTOMER_CLEARING_FORM", "customer.clearingForm",
                        exact(item.sourceFields(), "clientClearingForm"), null);
            } else if (data.objectType() == CrmMasterDataObjectType.STAFF) {
                add(values, "DHB_STAFF_STATUS", "staff.sourceStatus", item.sourceStatus(), null);
                add(values, "DHB_STAFF_TYPE", "staff.staffType",
                        exact(item.sourceFields(), "staff_type"), null);
            }
        }
        return client.sync(BusinessDictionaryBatchClient.serviceCaller(
                "rigour-merchant-crm-service", "CRM_DICTIONARY_SYNC", tenantId),
                data.objectType().name(), values);
    }

    private static void add(List<Observation> target, String dictCode, String fieldCode,
                            String sourceValue, String sourceName) {
        if (sourceValue == null || sourceValue.isBlank()) return;
        target.add(new Observation("CRM", dictCode, fieldCode, sourceValue.strip(), sourceName));
    }

    private static String exact(Map<String, Object> fields, String key) {
        if (fields == null || !fields.containsKey(key) || fields.get(key) == null) return null;
        String value = String.valueOf(fields.get(key));
        return value.isBlank() ? null : value;
    }
}
