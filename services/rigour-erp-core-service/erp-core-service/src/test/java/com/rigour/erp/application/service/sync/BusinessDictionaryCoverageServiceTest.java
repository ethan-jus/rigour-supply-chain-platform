package com.rigour.erp.application.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rigour.erp.application.port.out.DhbProductMasterDataClient;
import com.rigour.erp.application.port.out.DhbSupplyDataClient;
import com.rigour.erp.domain.model.product.MasterDataObjectType;
import com.rigour.erp.domain.model.product.Product;
import com.rigour.erp.domain.model.supply.PurchaseOrder;
import com.rigour.erp.domain.model.supply.PurchaseReturn;
import com.rigour.erp.domain.model.supply.SupplyDataObjectType;
import com.rigour.erp.domain.model.supply.Warehouse;
import com.rigour.erp.domain.model.supply.WarehousingReceipt;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BusinessDictionaryCoverageServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb900-0000-7000-8000-000000000001");

    @Test
    void productSyncIncludesExplicitSourceStatusAndUnits() {
        List<Observation> observed = new ArrayList<>();
        BusinessDictionaryCoverageService service = service(observed);
        Product product = new Product("P-1", "SPU-1", "商品一", "T", null, "件",
                null, null, List.of(), Map.of("status", "T"), "a".repeat(64));
        DhbProductMasterDataClient.Collected data = new DhbProductMasterDataClient.Collected(
                MasterDataObjectType.PRODUCT_SPU, 1, 1, List.of(product),
                List.of(), List.of(), List.of(), List.of());

        service.inspect(TENANT_ID, data);

        assertThat(observed).extracting(Observation::dictionaryCode, Observation::fieldCode,
                        Observation::sourceValue)
                .contains(tuple("DHB_PRODUCT_STATUS", "product.status", "T"),
                        tuple("DHB_PRODUCT_PUTAWAY", "product.putaway", "T"),
                        tuple("DHB_UNIT", "product.baseUnit", "件"));
    }

    @Test
    void supplySyncIncludesBusinessEnumsButNotBooleanFlags() {
        List<Observation> observed = new ArrayList<>();
        BusinessDictionaryCoverageService service = service(observed);
        PurchaseOrder order = mock(PurchaseOrder.class);
        when(order.downloaded()).thenReturn(true);
        when(order.lines()).thenReturn(List.of());
        PurchaseReturn purchaseReturn = mock(PurchaseReturn.class);
        when(purchaseReturn.sourceDevice()).thenReturn("APP");
        when(purchaseReturn.downloaded()).thenReturn(false);
        when(purchaseReturn.lines()).thenReturn(List.of());
        WarehousingReceipt receipt = mock(WarehousingReceipt.class);
        when(receipt.apiFlag()).thenReturn(true);
        when(receipt.splitType()).thenReturn("SPLIT");
        when(receipt.lines()).thenReturn(List.of());
        Warehouse warehouse = mock(Warehouse.class);
        when(warehouse.sourceStatus()).thenReturn("T");
        when(warehouse.defaultFlag()).thenReturn(true);
        DhbSupplyDataClient.Collected data = new DhbSupplyDataClient.Collected(
                SupplyDataObjectType.PURCHASE_ORDER, 4, 1, List.of(), List.of(order),
                List.of(purchaseReturn), List.of(receipt), List.of(warehouse), List.of());

        service.inspect(TENANT_ID, data);

        assertThat(observed).extracting(Observation::dictionaryCode, Observation::fieldCode,
                        Observation::sourceValue)
                .contains(tuple("DHB_WAREHOUSE_STATUS", "warehouse.sourceStatus", "T"));
        assertThat(observed).extracting(Observation::dictionaryCode)
                .doesNotContain("DHB_PURCHASE_RETURN_DEVICE", "DHB_WAREHOUSING_SPLIT_TYPE")
                .noneMatch(code -> code.endsWith("_FLAG"));
    }

    @Test
    void productSyncDoesNotTreatUnitLevelCodeAsUnitName() {
        List<Observation> observed = new ArrayList<>();
        BusinessDictionaryCoverageService service = service(observed);
        Product product = new Product("P-1", "SPU-1", "商品一", "T", null, "base_units",
                "middle_units", "container_units", List.of(), Map.of(), "a".repeat(64));

        service.inspect(TENANT_ID, new DhbProductMasterDataClient.Collected(
                MasterDataObjectType.PRODUCT_SPU, 1, 1, List.of(product),
                List.of(), List.of(), List.of(), List.of()));

        assertThat(observed).extracting(Observation::dictionaryCode).doesNotContain("DHB_UNIT");
    }

    private static BusinessDictionaryCoverageService service(List<Observation> observed) {
        BusinessDictionaryBatchClient client = mock(BusinessDictionaryBatchClient.class);
        when(client.sync(any(), any(), any())).thenAnswer(invocation -> {
            Collection<Observation> values = invocation.getArgument(2);
            observed.addAll(values);
            return Audit.empty();
        });
        return new BusinessDictionaryCoverageService(client);
    }
}
