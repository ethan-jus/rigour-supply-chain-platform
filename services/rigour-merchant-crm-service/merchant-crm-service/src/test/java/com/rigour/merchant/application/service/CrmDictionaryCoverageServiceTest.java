package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.Collected;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.SourceRecord;
import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CrmDictionaryCoverageServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb900-0000-7000-8000-000000000001");

    @Test
    void customerSyncIncludesBusinessEnumsButAddressBooleanIsNotADictionary() {
        List<Observation> observed = new ArrayList<>();
        BusinessDictionaryBatchClient client = mock(BusinessDictionaryBatchClient.class);
        when(client.sync(any(), any(), any())).thenAnswer(invocation -> {
            Collection<Observation> values = invocation.getArgument(2);
            observed.addAll(values);
            return Audit.empty();
        });
        CrmDictionaryCoverageService service = new CrmDictionaryCoverageService(client);

        service.sync(TENANT_ID, new Collected(CrmMasterDataObjectType.CUSTOMER, 1, 1,
                List.of(new SourceRecord("C-1", null, "客户一", "T", null, null,
                        Map.of("clientClearingForm", "PREPAID")))));
        service.sync(TENANT_ID, new Collected(CrmMasterDataObjectType.ADDRESS, 1, 1,
                List.of(new SourceRecord("A-1", null, "地址一", null, null, null,
                        Map.of("isDefault", "T")))));

        assertThat(observed).extracting(Observation::dictionaryCode, Observation::fieldCode,
                        Observation::sourceValue)
                .contains(tuple("DHB_CUSTOMER_STATUS", "customer.sourceStatus", "T"),
                        tuple("DHB_CUSTOMER_CLEARING_FORM", "customer.clearingForm", "PREPAID"));
        assertThat(observed).extracting(Observation::dictionaryCode)
                .doesNotContain("DHB_ADDRESS_DEFAULT_FLAG", "DHB_STAFF_TYPE", "DHB_STAFF_STATUS");
    }
}
