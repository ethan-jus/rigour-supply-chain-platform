package com.rigour.merchant.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.merchant.infrastructure.persistence.CrmUuidCodec;
import com.rigour.merchant.infrastructure.persistence.entity.*;
import com.rigour.merchant.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.*;

import java.time.Clock;
import java.util.*;

class CustomerSourceReviewTest {
    final UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID(), actor = UUID.randomUUID();
    final SourceBindingMapper bindings = mock(SourceBindingMapper.class);
    final InternalCustomerMapper customers = mock(InternalCustomerMapper.class);
    final CustomerIdentityGuard identities = mock(CustomerIdentityGuard.class);
    final CrmDataScope scopes = mock(CrmDataScope.class);
    final MybatisPlusCrmRepository store =
            new MybatisPlusCrmRepository(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    customers,
                    null,
                    null,
                    null,
                    null,
                    bindings,
                    null,
                    null,
                    tools.jackson.databind.json.JsonMapper.builder().build(),
                    Clock.systemUTC(),
                    scopes,
                    null,
                    identities);
    SourceBindingEntity source, primary;
    InternalCustomerEntity customer;

    @BeforeEach
    void setup() {
        source = new SourceBindingEntity();
        source.id = new byte[16];
        source.sourceObjectId = "SECOND";
        source.revision = 7L;
        source.bindingStatus = "UNRESOLVED";
        source.resolutionErrorCode = "CUSTOMER_IDENTITY_REVIEW_REQUIRED";
        primary = new SourceBindingEntity();
        primary.sourceObjectId = "FIRST";
        customer = new InternalCustomerEntity();
        customer.setPartyId(CrmUuidCodec.encode(UUID.randomUUID()));
        customer.setStatusCode("ACTIVE");
        when(bindings.selectOne(any())).thenReturn(source);
        when(customers.selectOne(any())).thenReturn(customer);
        when(bindings.selectList(any())).thenReturn(List.of(primary));
        when(bindings.update(isNull(), any())).thenReturn(1);
    }

    @Test
    void casualCustomerIsRecordedAsExcludedWithoutCreatingACustomer() {
        when(bindings.selectOne(any())).thenReturn(null);
        var result = importCustomer(Map.of("clientAreaName", "散客", "staffID", 0));
        assertThat(result.duplicates()).isEqualTo(1);
        var saved = org.mockito.ArgumentCaptor.forClass(SourceBindingEntity.class);
        verify(bindings).insert(saved.capture());
        assertThat(saved.getValue().bindingStatus).isEqualTo("EXCLUDED");
        assertThat(saved.getValue().resolutionErrorCode).isEqualTo("CUSTOMER_CASUAL_EXCLUDED");
        assertThat(saved.getValue().sourceFieldsJson).contains("散客");
        verifyNoInteractions(customers);
    }

    @Test
    void casualPolicyPreservesExistingResolvedIdentityAndHistoricalOwner() {
        source.bindingStatus = "RESOLVED";
        source.targetId = customer.getPartyId();
        source.resolutionErrorCode = null;
        var originalParty = source.targetId;
        importCustomer(Map.of("clientAreaName", " 散客 ", "staffID", 0));
        assertThat(source.bindingStatus).isEqualTo("RESOLVED");
        assertThat(source.targetId).containsExactly(originalParty);
        assertThat(source.resolutionErrorCode).isEqualTo("CUSTOMER_CASUAL_EXCLUDED");
        verifyNoInteractions(customers);
    }

    @Test
    void manuallyExcludedCustomerCannotBeRecreatedEvenWhenUpstreamOwnerAndAreaChange() {
        source.bindingStatus = "EXCLUDED";
        source.resolutionErrorCode = "CUSTOMER_MANUALLY_EXCLUDED";
        source.resolutionErrorMessage = "用户已确认删除，保留来源留痕";
        var result = importCustomer(Map.of("clientAreaName", "深圳市", "staffID", 42, "staffName", "张三"));
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(source.bindingStatus).isEqualTo("EXCLUDED");
        assertThat(source.resolutionErrorCode).isEqualTo("CUSTOMER_MANUALLY_EXCLUDED");
        verifyNoInteractions(customers);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CUSTOMER_MANUALLY_EXCLUDED", "CUSTOMER_CASUAL_EXCLUDED"})
    void excludedSourceDoesNotAppearAsAnUnresolvedCustomer(String reason) {
        source.resolutionErrorCode = reason;
        source.sourceFieldsJson = "{}";
        when(bindings.selectList(any())).thenReturn(List.of(source));
        assertThat(store.customerReviews(tenant, connector)).isEmpty();
        store.pendingCustomerRecords(tenant, connector);
        var query = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(bindings, times(2)).selectList(query.capture());
        assertThat(query.getAllValues().getLast().getSqlSegment()).contains("COALESCE(resolution_error_code", reason);
    }

    @Test
    void excludedSourcePublishesIgnoredMappingInsteadOfAnUnresolvedDependency() {
        source.bindingStatus = "EXCLUDED";
        source.resolutionErrorCode = "CUSTOMER_MANUALLY_EXCLUDED";
        source.resolutionErrorMessage = "用户确认删除并排除导入";
        source.sourceFieldsJson = "{}";
        when(bindings.selectList(any())).thenReturn(List.of(source));
        var mappings = store.externalObjectMappings(tenant, connector, UUID.randomUUID(),
                com.rigour.merchant.domain.model.CrmMasterDataObjectType.CUSTOMER);
        assertThat(mappings).hasSize(1);
        assertThat(mappings.getFirst().mappingStatus()).isEqualTo("IGNORED");
        assertThat(mappings.getFirst().conflictReason()).isEqualTo("CUSTOMER_MANUALLY_EXCLUDED");
        assertThat(mappings.getFirst().sourceObjectId()).isEqualTo("SECOND");
        assertThat(mappings.getFirst().internalObjectId()).isNull();
    }

    private com.rigour.merchant.application.port.out.CrmMasterDataStore.ImportResult importCustomer(Map<String,Object> fields) {
        var record = new com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.SourceRecord(
                "SECOND", "C2", "客户", null, null, null, fields);
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(store, "importRecordInternal",
                tenant, connector, UUID.randomUUID(), com.rigour.merchant.domain.model.CrmMasterDataObjectType.CUSTOMER, record);
    }

    @Test
    void historicalReviewsAreTenantScopedAndKeepEachSourceConnectorWhenNoTaskIsActive() {
        source.connectorId = CrmUuidCodec.encode(connector);
        source.sourceFieldsJson = "{}";
        when(bindings.selectList(any())).thenReturn(List.of(source));
        var reviews = store.customerReviews(tenant, null);
        assertThat(reviews).hasSize(1);
        assertThat(reviews.getFirst().connectorId()).isEqualTo(connector);
        assertThat(reviews.getFirst().issueMessage()).isEqualTo("存在同名客户，请核对是否同一客户");
        var query =
                org.mockito.ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(bindings).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment())
                .contains(
                        "tenant_id",
                        "source_system",
                        "source_object_type",
                        "source_presence",
                        "deleted")
                .doesNotContain("connector_id");
        verifyNoInteractions(identities);
        verify(bindings, never()).update(any(), any());
    }

    @Test
    void duplicateIdentityExposesReviewActionInsteadOfProfileCompletion() {
        source.connectorId = CrmUuidCodec.encode(connector);
        source.sourceFieldsJson = "{}";
        source.resolutionErrorCode = "CUSTOMER_IDENTITY_DUPLICATE";
        when(bindings.selectList(any())).thenReturn(List.of(source));
        var review = store.customerReviews(tenant, connector).getFirst();
        assertThat(review.issueCode()).isEqualTo("CUSTOMER_IDENTITY_REVIEW_REQUIRED");
        assertThat(review.issueMessage()).isEqualTo("存在同名客户，请核对是否同一客户");
        verify(bindings, never()).update(any(), any());
    }

    @Test
    void explicitChoiceUpdatesOnlyUnresolvedSourceAndKeepsThePrimaryCustomer() {
        store.linkReviewedCustomer(tenant, connector, "SECOND", 7, 15, "核对同一门店", actor);
        verify(identities).lock(tenant.toString());
        verify(scopes).requireCustomer(tenant.toString(), 15, "crm:customer:write");
        var update =
                org.mockito.ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(bindings).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet())
                .contains(
                        "primary_customer_source_id",
                        "independence_evidence",
                        "revision=revision+1");
        assertThat(update.getValue().getSqlSegment())
                .contains("tenant_id", "connector_id", "revision", "binding_status");
        assertThat(update.getValue().getParamNameValuePairs().values())
                .contains("FIRST", "核对同一门店", 7L);
        verify(customers).selectOne(any());
        verifyNoMoreInteractions(customers);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void independentReviewKeepsOnlyCustomerCreatedByThisExactSource(boolean sameSource) {
        source.targetId = customer.getPartyId();
        customer.setSourceSystemCode("DINGHUOBAO");
        customer.setSourceCustomerId(sameSource ? source.sourceObjectId : "FIRST");
        store.confirmIndependentCustomer(tenant, connector, "SECOND", 7, false, "地址核对为不同门店", actor);
        var update = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(bindings).update(isNull(), update.capture());
        var assignments = update.getValue().getSqlSet();
        var target = java.util.regex.Pattern.compile("(?:^|,)target_id=#\\{ew.paramNameValuePairs.([^}]+)}").matcher(assignments);
        assertThat(target.find()).isTrue();
        assertThat(update.getValue().getParamNameValuePairs().get(target.group(1)))
                .isEqualTo(sameSource ? source.targetId : null);
        verify(customers, never()).insert(any(InternalCustomerEntity.class));
    }

    @Test
    void customerOutsideBusinessDataScopeCannotBeLinkedBySubmittingItsId() {
        doThrow(new com.rigour.shared.context.AuthorizationDeniedException("crm:customer:write"))
                .when(scopes)
                .requireCustomer(tenant.toString(), 15, "crm:customer:write");
        assertThatThrownBy(
                        () ->
                                store.linkReviewedCustomer(
                                        tenant, connector, "SECOND", 7, 15, "说明", actor))
                .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        verify(bindings, never()).update(any(), any());
        verifyNoInteractions(customers);
    }

    @Test
    void staleOrAlreadyResolvedOrOtherProblemCannotRebind() {
        assertThatThrownBy(
                        () ->
                                store.linkReviewedCustomer(
                                        tenant, connector, "SECOND", 6, 15, "说明", actor))
                .hasMessageContaining("已变化");
        source.bindingStatus = "RESOLVED";
        assertThatThrownBy(
                        () ->
                                store.linkReviewedCustomer(
                                        tenant, connector, "SECOND", 7, 15, "说明", actor))
                .hasMessageContaining("不能直接改绑");
        source.bindingStatus = "UNRESOLVED";
        source.resolutionErrorCode = "CUSTOMER_EMPLOYEE_MAPPING_REQUIRED";
        assertThatThrownBy(
                        () ->
                                store.linkReviewedCustomer(
                                        tenant, connector, "SECOND", 7, 15, "说明", actor))
                .hasMessageContaining("不能直接改绑");
        verify(bindings, never()).update(any(), any());
    }

    @Test
    void ambiguousDestinationOrConcurrentChangeRejectsWithoutCreatingCustomer() {
        when(bindings.selectList(any())).thenReturn(List.of(primary, new SourceBindingEntity()));
        assertThatThrownBy(
                        () ->
                                store.linkReviewedCustomer(
                                        tenant, connector, "SECOND", 7, 15, "说明", actor))
                .hasMessageContaining("唯一");
        when(bindings.selectList(any())).thenReturn(List.of(primary));
        when(bindings.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(
                        () ->
                                store.linkReviewedCustomer(
                                        tenant, connector, "SECOND", 7, 15, "说明", actor))
                .hasMessageContaining("已变化");
        verify(customers, never()).insert(any(InternalCustomerEntity.class));
    }
}
