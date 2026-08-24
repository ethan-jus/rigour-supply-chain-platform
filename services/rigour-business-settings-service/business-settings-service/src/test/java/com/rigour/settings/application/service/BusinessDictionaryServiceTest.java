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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 验证新业务字典只按 dictionaryCode 维护和解析。 */
class BusinessDictionaryServiceTest {
    private final BusinessDictionaryStore store = mock(BusinessDictionaryStore.class);
    private final BusinessDictionaryService service = new BusinessDictionaryService(store);

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void createNormalizesDictionaryCodeAndType() {
        UUID actorId = UUID.randomUUID();
        setTenant(actorId);
        DictView expected = new DictView(1L, "PRODUCT_UNIT", "商品单位", "COMMON", null, 1);
        when(store.create(any(), eq(actorId.toString()))).thenReturn(expected);

        DictView created = service.create(new DictCommand(
                "product_unit", "商品单位", "common", null, 0));

        ArgumentCaptor<DictCommand> command = ArgumentCaptor.forClass(DictCommand.class);
        verify(store).create(command.capture(), eq(actorId.toString()));
        assertThat(command.getValue().dictionaryCode()).isEqualTo("PRODUCT_UNIT");
        assertThat(command.getValue().dictionaryType()).isEqualTo("COMMON");
        assertThat(created).isEqualTo(expected);
    }

    @Test
    void createItemRejectsNonZeroRevision() {
        setTenant(UUID.randomUUID());
        when(store.find(1L)).thenReturn(Optional.of(
                new DictView(1L, "PRODUCT_UNIT", "商品单位", "COMMON", null, 1)));

        assertThatThrownBy(() -> service.createItem(1L,
                new DictItemCommand("PRODUCT_UNIT", null, "BOX", "箱", null, 10, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("revision必须为0");
    }

    @Test
    void effectiveFindsDictionaryByCodeAndReturnsItems() {
        setTenant(UUID.randomUUID());
        DictView dictionary = new DictView(1L, "PRODUCT_UNIT", "商品单位", "COMMON", null, 3);
        when(store.findByCode("PRODUCT_UNIT")).thenReturn(Optional.of(dictionary));
        when(store.items("PRODUCT_UNIT")).thenReturn(List.of(
                new DictItemView(11L, "PRODUCT_UNIT", 1, null, "BOX", "箱", null, 10, 1)));

        var effective = service.effective("product_unit");

        assertThat(effective.dictionary()).isEqualTo(dictionary);
        assertThat(effective.items()).extracting(DictItemView::dictionaryItemCode).containsExactly("BOX");
    }

    @Test
    void serviceBatchSyncUsesDictionaryCodeOnlyAndDoesNotCreateDictionary() {
        UUID serviceId = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("SERVICE", serviceId, UUID.randomUUID(), null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("ERP_DICTIONARY_SYNC"),
                Set.of("business-settings:dict:sync")));
        DictView dictionary = new DictView(1L, "PRODUCT_UNIT", "商品单位", "COMMON", null, 3);
        DictView refreshed = new DictView(1L, "PRODUCT_UNIT", "商品单位", "COMMON", null, 4);
        when(store.findByCode("PRODUCT_UNIT")).thenReturn(Optional.of(dictionary), Optional.of(refreshed));
        when(store.syncMissingItems(eq("PRODUCT_UNIT"), any(), eq(serviceId.toString())))
                .thenReturn(new SyncStats(1, 0, 0, 0));
        when(store.items("PRODUCT_UNIT")).thenReturn(List.of());

        var result = service.syncItems(new DictSyncCommand("product_unit", List.of(
                new DictSourceValue("BOX", null), new DictSourceValue("BOX", "箱"))));

        ArgumentCaptor<List<SyncItem>> items = ArgumentCaptor.forClass(List.class);
        verify(store).syncMissingItems(eq("PRODUCT_UNIT"), items.capture(), eq(serviceId.toString()));
        assertThat(items.getValue()).singleElement().satisfies(item -> {
            assertThat(item.dictionaryItemCode()).isEqualTo("BOX");
            assertThat(item.dictionaryItemName()).isEqualTo("箱");
        });
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.effective().dictionary().revision()).isEqualTo(4);
    }

    private static void setTenant(UUID actorId) {
        TestAuthorizationContext.set(new CallerIdentity("TENANT", actorId, UUID.randomUUID(), actorId, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("TENANT_SUPER_ADMIN"),
                Set.of("business-settings:dict:read", "business-settings:dict:write")));
    }
}
