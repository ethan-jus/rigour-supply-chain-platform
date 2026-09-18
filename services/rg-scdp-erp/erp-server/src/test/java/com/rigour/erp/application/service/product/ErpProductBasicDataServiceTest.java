package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.InternalProductBrandCommand;
import com.rigour.erp.api.v1.model.InternalProductBrandView;
import com.rigour.erp.api.v1.model.InternalProductCategoryCommand;
import com.rigour.erp.api.v1.model.InternalProductCategoryView;
import com.rigour.erp.api.v1.model.InternalProductSpecificationCommand;
import com.rigour.erp.api.v1.model.InternalProductSpecificationValueCommand;
import com.rigour.erp.api.v1.model.InternalProductSpecificationValueView;
import com.rigour.erp.api.v1.model.InternalProductSpecificationView;
import com.rigour.erp.api.v1.model.InternalProductTagCommand;
import com.rigour.erp.api.v1.model.InternalProductTagView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProductBrandStore;
import com.rigour.erp.application.port.out.ErpProductCategoryStore;
import com.rigour.erp.application.port.out.ErpProductCategoryStore.CategorySearchCriteria;
import com.rigour.erp.application.port.out.ErpProductSpecificationStore;
import com.rigour.erp.application.port.out.ErpProductTagStore;
import com.rigour.erp.application.port.out.ErpProductTagStore.TagSearchCriteria;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErpProductBasicDataServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-0000-7000-8000-000000000002");
    private static final String TENANT = TENANT_ID.toString();
    private static final String ACTOR = USER_ID.toString();

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void createCategoryGeneratesCodeAndNormalizesCommand() {
        ErpProductCategoryStore store = mock(ErpProductCategoryStore.class);
        ErpProductCategoryService service = new ErpProductCategoryService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:write"));
        when(store.existsByCode(TENANT, "CAT202608201234")).thenReturn(false);
        when(store.create(eq(TENANT), eq("CAT202608201234"), any(), eq(1), eq(ACTOR)))
                .thenReturn(new InternalProductCategoryView(1L, "CAT202608201234", "饮品",
                        null, 1, 0, "一级分类", 1, ACTOR, Instant.now(), ACTOR, Instant.now()));

        InternalProductCategoryView created = service.create(new InternalProductCategoryCommand(
                null, " 饮品 ", null, " 一级分类 ", null));

        ArgumentCaptor<InternalProductCategoryCommand> command =
                ArgumentCaptor.forClass(InternalProductCategoryCommand.class);
        verify(store).create(eq(TENANT), eq("CAT202608201234"), command.capture(), eq(1), eq(ACTOR));
        assertThat(created.categoryCode()).isEqualTo("CAT202608201234");
        assertThat(command.getValue().categoryName()).isEqualTo("饮品");
        assertThat(command.getValue().ordinal()).isZero();
        assertThat(command.getValue().remark()).isEqualTo("一级分类");
        assertThat(command.getValue().revision()).isZero();
    }

    @Test
    void listCategoryUsesIndependentCriteriaWithoutKeywordAggregation() {
        ErpProductCategoryStore store = mock(ErpProductCategoryStore.class);
        ErpProductCategoryService service = new ErpProductCategoryService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:read"));
        when(store.categories(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new MasterDataPageView<>(0, 0, 20, List.of()));

        service.categories(0, 20, " cat ", " 饮品 ", 9L);

        ArgumentCaptor<CategorySearchCriteria> criteria =
                ArgumentCaptor.forClass(CategorySearchCriteria.class);
        verify(store).categories(eq(TENANT), eq(0), eq(20), criteria.capture());
        assertThat(criteria.getValue().categoryCode()).isEqualTo("cat");
        assertThat(criteria.getValue().categoryName()).isEqualTo("饮品");
        assertThat(criteria.getValue().parentId()).isEqualTo(9L);
    }

    @Test
    void updateBrandRequiresOptimisticRevision() {
        ErpProductBrandService service = new ErpProductBrandService(mock(ErpProductBrandStore.class), fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:write"));

        assertThatThrownBy(() -> service.update(1L, new InternalProductBrandCommand("瑞盖", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void deleteCategoryBlocksWhenChildrenExist() {
        ErpProductCategoryStore store = mock(ErpProductCategoryStore.class);
        ErpProductCategoryService service = new ErpProductCategoryService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:write"));
        when(store.hasChildren(TENANT, 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(1L, 1))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        verify(store, never()).delete(eq(TENANT), eq(1L), eq(1), eq(ACTOR));
    }

    @Test
    void listTagUppercasesDictionaryCodeAsDedicatedFilter() {
        ErpProductTagStore store = mock(ErpProductTagStore.class);
        ErpProductTagService service = new ErpProductTagService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:read"));
        when(store.tags(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new MasterDataPageView<>(0, 0, 20, List.of()));

        service.tags(0, 20, " tag ", " 新品 ", " product_label ");

        ArgumentCaptor<TagSearchCriteria> criteria = ArgumentCaptor.forClass(TagSearchCriteria.class);
        verify(store).tags(eq(TENANT), eq(0), eq(20), criteria.capture());
        assertThat(criteria.getValue().tagCode()).isEqualTo("tag");
        assertThat(criteria.getValue().tagName()).isEqualTo("新品");
        assertThat(criteria.getValue().tagTypeCode()).isEqualTo("PRODUCT_LABEL");
    }

    @Test
    void createTagGeneratesOwnCodeAndRequiresDictionaryType() {
        ErpProductTagStore store = mock(ErpProductTagStore.class);
        ErpProductTagService service = new ErpProductTagService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:write"));
        when(store.existsByCode(TENANT, "TAG202608201234")).thenReturn(false);
        when(store.create(eq(TENANT), eq("TAG202608201234"), any(), eq(ACTOR)))
                .thenReturn(new InternalProductTagView(1L, "TAG202608201234", "新品",
                        "PRODUCT_LABEL", "首发", 1, ACTOR, Instant.now(), ACTOR, Instant.now()));

        service.create(new InternalProductTagCommand(" 新品 ", " product_label ", " 首发 ", null));

        ArgumentCaptor<InternalProductTagCommand> command =
                ArgumentCaptor.forClass(InternalProductTagCommand.class);
        verify(store).create(eq(TENANT), eq("TAG202608201234"), command.capture(), eq(ACTOR));
        assertThat(command.getValue().tagName()).isEqualTo("新品");
        assertThat(command.getValue().tagTypeCode()).isEqualTo("PRODUCT_LABEL");
        assertThat(command.getValue().remark()).isEqualTo("首发");
    }

    @Test
    void createSpecificationNormalizesCodeAndNestedValues() {
        ErpProductSpecificationStore store = mock(ErpProductSpecificationStore.class);
        ErpProductSpecificationService service = new ErpProductSpecificationService(store);
        TestAuthorizationContext.set(caller("erp:product:write"));
        when(store.existsByCode(TENANT, "11", null)).thenReturn(false);
        when(store.create(eq(TENANT), any(), eq(ACTOR)))
                .thenReturn(new InternalProductSpecificationView(1L, "11", "球杆型号", "ACTIVE", 2,
                        List.of(
                                new InternalProductSpecificationValueView(11L, "V001", "10", 0,
                                        "ACTIVE", 1, ACTOR, Instant.now(), ACTOR, Instant.now()),
                                new InternalProductSpecificationValueView(12L, "A300", "A300", 100,
                                        "INACTIVE", 1, ACTOR, Instant.now(), ACTOR, Instant.now())),
                        1, ACTOR, Instant.now(), ACTOR, Instant.now()));

        service.create(new InternalProductSpecificationCommand(" 11 ", " 球杆型号 ", null,
                List.of(
                        new InternalProductSpecificationValueCommand(null, null, " 10 ", null, null),
                        new InternalProductSpecificationValueCommand(null, " a300 ", " A300 ", 100, " inactive ")),
                null));

        ArgumentCaptor<InternalProductSpecificationCommand> command =
                ArgumentCaptor.forClass(InternalProductSpecificationCommand.class);
        verify(store).create(eq(TENANT), command.capture(), eq(ACTOR));
        assertThat(command.getValue().specificationCode()).isEqualTo("11");
        assertThat(command.getValue().specificationName()).isEqualTo("球杆型号");
        assertThat(command.getValue().statusCode()).isEqualTo("ACTIVE");
        assertThat(command.getValue().revision()).isZero();
        assertThat(command.getValue().values())
                .extracting(InternalProductSpecificationValueCommand::valueCode)
                .containsExactly("V001", "A300");
        assertThat(command.getValue().values())
                .extracting(InternalProductSpecificationValueCommand::valueName)
                .containsExactly("10", "A300");
        assertThat(command.getValue().values())
                .extracting(InternalProductSpecificationValueCommand::ordinal)
                .containsExactly(0, 100);
        assertThat(command.getValue().values())
                .extracting(InternalProductSpecificationValueCommand::statusCode)
                .containsExactly("ACTIVE", "INACTIVE");
    }

    @Test
    void updateSpecificationRejectsDuplicateNestedValueCodes() {
        ErpProductSpecificationStore store = mock(ErpProductSpecificationStore.class);
        ErpProductSpecificationService service = new ErpProductSpecificationService(store);
        TestAuthorizationContext.set(caller("erp:product:write"));
        InternalProductSpecificationCommand command = new InternalProductSpecificationCommand("MODEL", "球杆型号", "ACTIVE",
                List.of(
                        new InternalProductSpecificationValueCommand(1L, "A300", "A300", 100, "ACTIVE"),
                        new InternalProductSpecificationValueCommand(2L, " a300 ", "A300重复", 200, "ACTIVE")),
                1);

        assertThatThrownBy(() -> service.update(1L, command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).update(eq(TENANT), eq(1L), any(), eq(ACTOR));
    }

    private static BusinessCodeGenerator fixedGenerator() {
        return new BusinessCodeGenerator(
                Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ignored -> "1234");
    }

    private static CallerIdentity caller(String permission) {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("erp"), Set.of(permission));
    }
}
