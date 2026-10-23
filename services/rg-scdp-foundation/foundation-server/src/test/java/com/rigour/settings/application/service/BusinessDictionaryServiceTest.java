package com.rigour.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rigour.settings.api.v1.model.DictCommand;
import com.rigour.settings.api.v1.model.DictItemCommand;
import com.rigour.settings.api.v1.model.DictItemView;
import com.rigour.settings.api.v1.model.DictSourceValue;
import com.rigour.settings.api.v1.model.DictSyncCommand;
import com.rigour.settings.api.v1.model.DictView;
import com.rigour.settings.application.port.out.BusinessDictionaryStore;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    void sourceSyncResolvesCanonicalNamesAndLeavesUnknownValuesUnchanged() {
        setService();
        when(store.findByCode("PRODUCT_UNIT")).thenReturn(Optional.of(new DictView(1L,"PRODUCT_UNIT","单位","COMMON",null,3)));
        when(store.items("PRODUCT_UNIT")).thenReturn(List.of(new DictItemView(11L,"PRODUCT_UNIT",1,null,"BOX","箱",null,10,1)));
        var command=new DictSyncCommand("PRODUCT_UNIT",List.of(new DictSourceValue("箱",null),new DictSourceValue("箱",null),new DictSourceValue("新单位",null)));
        var first=service.syncItems(command);
        var second=service.syncItems(command);
        assertThat(first.created()).isZero();
        assertThat(first.blocked()).isEqualTo(1);
        assertThat(first.resolutions().get("箱").itemCode()).isEqualTo("BOX");
        assertThat(first.resolutions()).doesNotContainKey("新单位");
        assertThat(second).isEqualTo(first);
        assertThat(first.effective().dictionary().revision()).isEqualTo(3);
    }

    @Test
    void historyKeepsAliasesWhileEffectiveOptionsOnlyContainStandards() {
        setTenant(UUID.randomUUID());
        when(store.findByCode("STORE_STATUS")).thenReturn(Optional.of(new DictView(1L,"STORE_STATUS","门店状态","CRM",null,1)));
        when(store.items("STORE_STATUS")).thenReturn(List.of(
            new DictItemView(11L,"STORE_STATUS",1,null,"ACTIVE","营业中",null,10,1),
            new DictItemView(12L,"STORE_STATUS",1,null,"LEGACY","营业中",null,20,1,"STORE_STATUS","ACTIVE")));
        assertThat(service.resolve("STORE_STATUS").items()).hasSize(2);
        assertThat(service.effective("STORE_STATUS").items()).extracting(DictItemView::dictionaryItemCode).containsExactly("ACTIVE");
        setService();
        var result=service.syncItems(new DictSyncCommand("STORE_STATUS",List.of(new DictSourceValue("LEGACY",null))));
        assertThat(result.resolutions().get("LEGACY").itemCode()).isEqualTo("ACTIVE");
    }

    @Test
    void hrSynonymsContinueToResolveStandardCodes() {
        setService();
        when(store.findByCode("EMPLOYEE_STATUS")).thenReturn(Optional.of(new DictView(1L,"EMPLOYEE_STATUS","员工状态","HR",null,1)));
        when(store.items("EMPLOYEE_STATUS")).thenReturn(List.of(new DictItemView(1L,"EMPLOYEE_STATUS",1,null,"INACTIVE","停用",null,1,1)));
        var result=service.syncItems(new DictSyncCommand("EMPLOYEE_STATUS",List.of(new DictSourceValue("禁用",null))));
        assertThat(result.resolutions().get("禁用").itemCode()).isEqualTo("INACTIVE");
    }

    @ParameterizedTest
    @CsvSource({
        "erp:cost:purchase:read,PRODUCT_UNIT",
        "erp:cost:loss:read,PRODUCT_UNIT",
        "erp:cost:rental:read,COST_PREMISES_TYPE",
        "erp:cost:marketing:read,MARKETING_EXPENSE_TYPE",
        "order:logistics-cost:read,COST_LOGISTICS_LEG",
        "order:logistics-cost:read,COST_TRANSPORT_METHOD",
        "order:logistics-cost:read,WAREHOUSE_TYPE",
        "hr:expense:read,REIMBURSEMENT_EXPENSE_TYPE",
        "hr:expense:read,COST_PAYMENT_STATUS",
        "erp:cost:rental:read,COST_STATUS",
        "analytics:cost-dashboard:read,MARKETING_EXPENSE_TYPE",
        "hr:payroll:read,PAYROLL_STATUS",
        "hr:payroll:read,PAYROLL_RULE_KIND",
        "hr:payroll:read,COMMISSION_RECIPIENT_ROLE",
        "hr:payroll-rule:read,PRODUCT_UNIT",
        "hr:attendance:read,ATTENDANCE_LEAVE_TYPE",
        "hr:attendance:read,ATTENDANCE_STATUS"
    })
    void businessReaderCanLoadOwnOptionsWithoutDictionaryManagement(String permission, String code) {
        setBusinessReader(permission);
        when(store.findByCode(code)).thenReturn(Optional.of(new DictView(1L,code,"业务选项","COMMON",null,1)));
        when(store.items(code)).thenReturn(List.of(new DictItemView(1L,code,1,null,"OPTION","业务选项",null,1,1)));

        assertThat(service.resolve(code).items()).extracting(DictItemView::dictionaryItemName)
                .containsExactly("业务选项");
    }

    @ParameterizedTest
    @CsvSource({
        "erp:cost:purchase:read,MARKETING_EXPENSE_TYPE",
        "hr:expense:read,PRODUCT_UNIT",
        "hr:employee:read,COST_STATUS",
        "analytics:cost-dashboard:read,PRIVATE_CUSTOM_DICTIONARY",
        "analytics:cost-dashboard:read,PAYROLL_RULE_KIND",
        "analytics:cost-dashboard:read,ATTENDANCE_STATUS",
        "hr:expense:read,ATTENDANCE_LEAVE_TYPE",
        "hr:attendance:read,PRIVATE_CUSTOM_DICTIONARY",
        "unrelated:read,COST_STATUS"
    })
    void businessReaderCannotEnumerateUnrelatedDictionaries(String permission, String code) {
        setBusinessReader(permission);
        assertThatThrownBy(() -> service.resolve(code)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }

    @Test
    void businessOptionsAccessDoesNotGrantManagementListItemsOrWrites() {
        setBusinessReader("analytics:cost-dashboard:read");
        assertThatThrownBy(() -> service.list(null, null)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.items(1L)).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> service.create(new DictCommand("COST_STATUS","费用状态","COMMON",null,0)))
                .isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }

    private static void setBusinessReader(String permission) {
        UUID actor = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT",actor,UUID.randomUUID(),actor,null,
                UUID.randomUUID(),0,0,0,Set.of(),Set.of(permission)));
    }

    private static void setService() {
        TestAuthorizationContext.set(new CallerIdentity("SERVICE",UUID.randomUUID(),UUID.randomUUID(),null,null,
            UUID.randomUUID(),0,0,0,Set.of("DICT_SYNC"),Set.of("business-settings:dict:sync")));
    }

    private static void setTenant(UUID actorId) {
        TestAuthorizationContext.set(new CallerIdentity("TENANT", actorId, UUID.randomUUID(), actorId, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("TENANT_SUPER_ADMIN"),
                Set.of("business-settings:dict:read", "business-settings:dict:write")));
    }
}
