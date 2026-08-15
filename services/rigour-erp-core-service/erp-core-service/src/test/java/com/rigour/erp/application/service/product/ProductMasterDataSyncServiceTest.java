package com.rigour.erp.application.service.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.erp.application.port.out.DhbProductMasterDataClient;
import com.rigour.erp.application.port.out.DhbProductMasterDataClient.Collected;
import com.rigour.erp.application.port.out.DhbProductSyncTargetDiscoveryClient;
import com.rigour.erp.application.port.out.ProductMasterDataStore;
import com.rigour.erp.application.port.out.ProductMasterDataStore.ImportResult;
import com.rigour.erp.application.service.sync.BusinessDictionaryCoverageService;
import com.rigour.erp.domain.model.product.Brand;
import com.rigour.erp.domain.model.product.Category;
import com.rigour.erp.domain.model.product.MasterDataObjectType;
import com.rigour.erp.domain.model.product.Product;
import com.rigour.erp.domain.model.product.Specification;
import com.rigour.erp.domain.model.product.Tag;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.client.ConnectorSyncLeaseClient;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class ProductMasterDataSyncServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb100-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb100-0000-7000-8000-000000000002");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fb100-0000-7000-8000-000000000003");
    private static final UUID TASK_ID = UUID.fromString("019fb100-0000-7000-8000-000000000004");
    private static final UUID RUN_ID = UUID.fromString("019fb100-0000-7000-8000-000000000005");

    @AfterEach
    void clearCaller() throws Exception {
        invokeContextMethod("clear", new Class<?>[0], new Object[0]);
    }

    @ParameterizedTest
    @EnumSource(MasterDataObjectType.class)
    void oneEndpointDispatchesExactlyOneMasterDataType(MasterDataObjectType objectType) throws Exception {
        DhbProductMasterDataClient integration = mock(DhbProductMasterDataClient.class);
        DhbProductSyncTargetDiscoveryClient discovery = mock(DhbProductSyncTargetDiscoveryClient.class);
        ProductMasterDataStore store = mock(ProductMasterDataStore.class);
        BusinessDictionaryCoverageService dictionaryCoverage = mock(BusinessDictionaryCoverageService.class);
        ProductMasterDataSyncService service = new ProductMasterDataSyncService(
                integration, discovery, store, dictionaryCoverage, passthroughLease());
        when(discovery.discover(any())).thenReturn(List.of(
                new SyncTargetView(TASK_ID, TENANT_ID, CONNECTOR_ID)));
        when(store.startRun(TENANT_ID.toString(), CONNECTOR_ID, USER_ID, objectType, 3))
                .thenReturn(RUN_ID);
        Collected collected = collected(objectType);
        when(integration.collect(any(), eq(CONNECTOR_ID), eq(objectType), eq(3)))
                .thenReturn(collected);
        stubImport(store, objectType, collected);
        setCaller();

        var result = service.run(objectType, 3);

        assertThat(result.runId()).isEqualTo(RUN_ID);
        assertThat(result.objectType()).isEqualTo(objectType.name());
        assertThat(result.fetched()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        verify(store).completeRun(eq(TENANT_ID.toString()), eq(RUN_ID), any());
        ArgumentCaptor<CallerIdentity> integrationCaller = ArgumentCaptor.forClass(CallerIdentity.class);
        verify(integration).collect(integrationCaller.capture(), eq(CONNECTOR_ID), eq(objectType), eq(3));
        assertThat(integrationCaller.getValue().principalScope()).isEqualTo("SERVICE");
        assertThat(integrationCaller.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(integrationCaller.getValue().permissions()).containsExactly("integration:dhb:read");
    }

    @Test
    void scheduledRunUsesTheScheduledBatchEntryWithoutRediscoveringTheTarget() {
        DhbProductMasterDataClient integration = mock(DhbProductMasterDataClient.class);
        DhbProductSyncTargetDiscoveryClient discovery = mock(DhbProductSyncTargetDiscoveryClient.class);
        ProductMasterDataStore store = mock(ProductMasterDataStore.class);
        BusinessDictionaryCoverageService dictionaryCoverage = mock(BusinessDictionaryCoverageService.class);
        ProductMasterDataSyncService service = new ProductMasterDataSyncService(
                integration, discovery, store, dictionaryCoverage, passthroughLease());
        Collected collected = collected(MasterDataObjectType.BRAND);
        when(store.startScheduledRun(TENANT_ID.toString(), CONNECTOR_ID, null,
                MasterDataObjectType.BRAND, 3)).thenReturn(RUN_ID);
        when(integration.collect(any(), eq(CONNECTOR_ID), eq(MasterDataObjectType.BRAND), eq(3)))
                .thenReturn(collected);
        stubImport(store, MasterDataObjectType.BRAND, collected);
        CallerIdentity caller = new CallerIdentity("SERVICE", UUID.randomUUID(), TENANT_ID,
                null, null, UUID.randomUUID(), 0, 0, 0, Set.of("ERP_SYNC_SERVICE"),
                Set.of("integration:dhb:read"));

        var result = service.runScheduled(caller, CONNECTOR_ID, MasterDataObjectType.BRAND, 3);

        assertThat(result.runId()).isEqualTo(RUN_ID);
        verify(store).startScheduledRun(TENANT_ID.toString(), CONNECTOR_ID, null,
                MasterDataObjectType.BRAND, 3);
        verify(discovery, never()).discover(any());
    }

    private static Collected collected(MasterDataObjectType objectType) {
        Product product = new Product("P-1", "SPU-1", "商品一", "T", null, "件",
                null, null, List.of(), "a".repeat(64));
        Category category = new Category("C-1", null, "分类一", "b".repeat(64));
        Brand brand = new Brand("B-1", null, "品牌一", "c".repeat(64));
        Specification specification = new Specification(
                "S-1", "COLOR", "颜色", List.of(), "d".repeat(64));
        Tag tag = new Tag("T-1", "NEW", "新品", "e".repeat(64));
        return new Collected(objectType, 1, 1,
                objectType == MasterDataObjectType.PRODUCT_SPU ? List.of(product) : List.of(),
                objectType == MasterDataObjectType.CATEGORY ? List.of(category) : List.of(),
                objectType == MasterDataObjectType.BRAND ? List.of(brand) : List.of(),
                objectType == MasterDataObjectType.SPECIFICATION ? List.of(specification) : List.of(),
                objectType == MasterDataObjectType.TAG ? List.of(tag) : List.of());
    }

    private static ConnectorSyncLeaseClient passthroughLease() {
        ConnectorSyncLeaseClient lease = mock(ConnectorSyncLeaseClient.class);
        when(lease.execute(any(), any(), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(2)).get());
        return lease;
    }

    private static void stubImport(ProductMasterDataStore store, MasterDataObjectType objectType,
                                   Collected collected) {
        ImportResult created = ImportResult.created(1);
        switch (objectType) {
            case PRODUCT_SPU -> when(store.importProduct(any(), any(), eq(collected.products().getFirst())))
                    .thenReturn(created);
            case CATEGORY -> when(store.importCategory(any(), any(), eq(collected.categories().getFirst())))
                    .thenReturn(created);
            case BRAND -> when(store.importBrand(any(), any(), eq(collected.brands().getFirst())))
                    .thenReturn(created);
            case SPECIFICATION -> when(store.importSpecification(
                    any(), any(), eq(collected.specifications().getFirst()))).thenReturn(created);
            case TAG -> when(store.importTag(any(), any(), eq(collected.tags().getFirst())))
                    .thenReturn(created);
        }
    }

    private static void setCaller() throws Exception {
        CallerIdentity caller = new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.fromString("019fb100-0000-7000-8000-000000000006"), 0, 0, 0,
                Set.of("ERP_OPERATOR"), Set.of("erp:product:read", "erp:product:write"));
        invokeContextMethod("set", new Class<?>[]{CallerIdentity.class}, new Object[]{caller});
    }

    private static void invokeContextMethod(String name, Class<?>[] types, Object[] args)
            throws Exception {
        Method method = AuthorizationContext.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(null, args);
    }
}
