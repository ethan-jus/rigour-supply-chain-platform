package com.rigour.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.settings.api.v1.model.DictCommand;
import com.rigour.settings.api.v1.model.DictItemCommand;
import com.rigour.settings.api.v1.model.DictItemView;
import com.rigour.settings.api.v1.model.DictSourceValue;
import com.rigour.settings.api.v1.model.DictSyncCommand;
import com.rigour.settings.api.v1.model.DictView;
import com.rigour.settings.application.port.out.BusinessDictionaryStore;
import com.rigour.settings.application.port.out.BusinessDictionaryStore.SyncItem;
import com.rigour.settings.application.port.out.BusinessDictionaryStore.SyncStats;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 验证系统、模块和租户作用域的写入边界。 */
class BusinessDictionaryServiceTest {
    private final BusinessDictionaryStore store = mock(BusinessDictionaryStore.class);
    private final BusinessDictionaryService service = new BusinessDictionaryService(store,
            JsonMapper.builder().build());

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void tenantCreateAlwaysUsesTrustedCurrentTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        setTenant(actorId, tenantId);
        DictCommand command = new DictCommand("customer_level", "客户等级", "tenant", "crm",
                null, null, "active", 10, null, 0);
        DictView expected = new DictView(UUID.randomUUID(), "CUSTOMER_LEVEL", "客户等级", "TENANT",
                tenantId.toString(), "CRM", tenantId.toString(), null, "ACTIVE", 10, null, 0, 0);
        when(store.create(any(), eq(tenantId.toString()), eq(tenantId.toString()), eq(actorId.toString())))
                .thenReturn(expected);

        DictView created = service.create(command);

        assertThat(created).isEqualTo(expected);
        verify(store).create(any(), eq(tenantId.toString()), eq(tenantId.toString()), eq(actorId.toString()));
    }

    @Test
    void tenantCannotModifyModuleDictionary() {
        UUID tenantId = UUID.randomUUID();
        setTenant(UUID.randomUUID(), tenantId);
        UUID dictId = UUID.randomUUID();
        DictView module = new DictView(dictId, "PRODUCT_STATUS", "商品状态", "MODULE", "ERP",
                "ERP", null, null, "ACTIVE", 0, null, 0, 0);
        when(store.find(dictId)).thenReturn(Optional.of(module));

        assertThatThrownBy(() -> service.update(dictId,
                new DictCommand("PRODUCT_STATUS", "商品状态", "MODULE", "ERP",
                        null, null, "ACTIVE", 0, null, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能修改该作用域");
    }

    @Test
    void createItemRejectsNonZeroVersion() {
        UUID tenantId = UUID.randomUUID();
        setTenant(UUID.randomUUID(), tenantId);
        UUID dictId = UUID.randomUUID();
        DictView dictionary = new DictView(dictId, "PRODUCT_UNIT", "商品单位", "TENANT",
                tenantId.toString(), "ERP", tenantId.toString(), null, "ACTIVE", 0, null, 0, 0);
        when(store.find(dictId)).thenReturn(Optional.of(dictionary));

        assertThatThrownBy(() -> service.createItem(dictId,
                new DictItemCommand(dictId, null, "BOX", "箱", "BOX", 0, "ACTIVE", null, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须为0");
    }

    @Test
    void effectivePrefersCurrentTenantAndExcludesDisabledItems() {
        UUID tenantId = UUID.randomUUID();
        setTenant(UUID.randomUUID(), tenantId);
        UUID dictId = UUID.randomUUID();
        DictView tenantDictionary = new DictView(dictId, "PRODUCT_UNIT", "商品单位", "TENANT",
                tenantId.toString(), "ERP", tenantId.toString(), null, "ACTIVE", 0, null, 0, 4);
        when(store.findActive("TENANT", tenantId.toString(), "ERP", "PRODUCT_UNIT"))
                .thenReturn(Optional.of(tenantDictionary));
        when(store.items(dictId)).thenReturn(List.of(
                new DictItemView(UUID.randomUUID(), dictId, null, 1, "BOX", "箱", "BOX", 0,
                        "ACTIVE", null, 0),
                new DictItemView(UUID.randomUUID(), dictId, null, 1, "OLD", "旧单位", "OLD", 1,
                        "DISABLED", null, 0)));

        var effective = service.effective("erp", "product_unit");

        assertThat(effective.dictionary()).isEqualTo(tenantDictionary);
        assertThat(effective.items()).extracting(DictItemView::code).containsExactly("BOX");
        assertThat(effective.dictionary().revision()).isEqualTo(4);
    }

    @Test
    void resolveIncludesDisabledItemsForHistoricalDisplay() {
        UUID tenantId = UUID.randomUUID();
        setTenant(UUID.randomUUID(), tenantId);
        UUID dictId = UUID.randomUUID();
        DictView dictionary = new DictView(dictId, "PRODUCT_UNIT", "商品单位", "MODULE",
                "ERP", "ERP", null, null, "ACTIVE", 0, null, 0, 7);
        when(store.findActive("TENANT", tenantId.toString(), "ERP", "PRODUCT_UNIT"))
                .thenReturn(Optional.empty());
        when(store.findActive("MODULE", "ERP", "ERP", "PRODUCT_UNIT"))
                .thenReturn(Optional.of(dictionary));
        when(store.items(dictId)).thenReturn(List.of(
                new DictItemView(UUID.randomUUID(), dictId, null, 1, "BOX", "箱", "BOX", 0,
                        "ACTIVE", null, 0),
                new DictItemView(UUID.randomUUID(), dictId, null, 1, "OLD", "旧单位", "OLD", 1,
                        "DISABLED", null, 0)));

        var resolved = service.resolve("erp", "product_unit");

        assertThat(resolved.items()).extracting(DictItemView::code).containsExactly("BOX", "OLD");
        assertThat(resolved.dictionary().revision()).isEqualTo(7);
    }

    @Test
    void tenantCannotListAnotherTenantsDictionaries() {
        setTenant(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> service.list(null, null, UUID.randomUUID().toString(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能查询其他租户");
    }

    @Test
    void serviceBatchSyncPrefersExplicitSourceNameAndDoesNotCreateDictionary() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("SERVICE", serviceId, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ERP_DICTIONARY_SYNC"),
                Set.of("business-settings:dict:sync")));
        UUID dictId = UUID.randomUUID();
        DictView dictionary = new DictView(dictId, "DHB_UNIT", "订货宝计量单位", "MODULE",
                "COMMON", "COMMON", null, null, "ACTIVE", 0, null, 0, 3);
        DictView refreshed = new DictView(dictId, "DHB_UNIT", "订货宝计量单位", "MODULE",
                "COMMON", "COMMON", null, null, "ACTIVE", 0, null, 0, 4);
        when(store.findActive("TENANT", tenantId.toString(), "COMMON", "DHB_UNIT"))
                .thenReturn(Optional.empty());
        when(store.findActive("MODULE", "COMMON", "COMMON", "DHB_UNIT"))
                .thenReturn(Optional.of(dictionary));
        when(store.syncMissingItems(eq(dictId), any(), eq(serviceId.toString())))
                .thenReturn(new SyncStats(1, 0, 0, 0));
        when(store.find(dictId)).thenReturn(Optional.of(refreshed));
        when(store.items(dictId)).thenReturn(List.of());

        var result = service.syncItems(new DictSyncCommand("common", "dhb_unit", List.of(
                new DictSourceValue("BOX", null), new DictSourceValue("BOX", "箱"))));

        ArgumentCaptor<List<SyncItem>> items = ArgumentCaptor.forClass(List.class);
        verify(store).syncMissingItems(eq(dictId), items.capture(), eq(serviceId.toString()));
        assertThat(items.getValue()).singleElement().satisfies(item -> {
            assertThat(item.value()).isEqualTo("BOX");
            assertThat(item.name()).isEqualTo("箱");
            assertThat(item.code()).startsWith("AUTO_").hasSize(64);
        });
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.effective().dictionary().revision()).isEqualTo(4);
    }

    @Test
    void serviceBatchSyncUsesOfficialEnumNameAndRejectsUnknownRawPlaceholder() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("SERVICE", serviceId, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ORDER_DICTIONARY_SYNC"),
                Set.of("business-settings:dict:sync")));
        UUID dictId = UUID.randomUUID();
        DictView dictionary = new DictView(dictId, "DHB_ORDER_LINE_TYPE", "订货宝订单明细商品类型",
                "MODULE", "ORDER", "ORDER", null, null, "ACTIVE", 0, null, 0, 3);
        when(store.findActive("TENANT", tenantId.toString(), "ORDER", "DHB_ORDER_LINE_TYPE"))
                .thenReturn(Optional.empty());
        when(store.findActive("MODULE", "ORDER", "ORDER", "DHB_ORDER_LINE_TYPE"))
                .thenReturn(Optional.of(dictionary));
        when(store.syncMissingItems(eq(dictId), any(), eq(serviceId.toString())))
                .thenReturn(new SyncStats(1, 0, 0, 0));
        when(store.find(dictId)).thenReturn(Optional.of(dictionary));
        when(store.items(dictId)).thenReturn(List.of());

        service.syncItems(new DictSyncCommand("ORDER", "DHB_ORDER_LINE_TYPE", List.of(
                new DictSourceValue("c", null), new DictSourceValue("unverified", null))));

        ArgumentCaptor<List<SyncItem>> items = ArgumentCaptor.forClass(List.class);
        verify(store).syncMissingItems(eq(dictId), items.capture(), eq(serviceId.toString()));
        assertThat(items.getValue()).singleElement().satisfies(item -> {
            assertThat(item.value()).isEqualTo("c");
            assertThat(item.name()).isEqualTo("正常售卖");
        });
    }

    private static void setTenant(UUID actorId, UUID tenantId) {
        TestAuthorizationContext.set(new CallerIdentity("TENANT", actorId, tenantId, actorId, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("TENANT_SUPER_ADMIN"),
                Set.of("business-settings:dict:read", "business-settings:dict:write")));
    }
}
