package com.rigour.analytics.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.analytics.api.v1.model.PaymentRiskView.Category;
import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.analytics.application.port.out.PaymentRiskStore;
import com.rigour.analytics.application.port.out.PaymentRiskStore.Fact;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class PaymentRiskServiceTest {
    private final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-19T16:00:00Z");
    private final PaymentRiskStore store = mock(PaymentRiskStore.class);
    private final PaymentRiskService service = new PaymentRiskService(store,
            new BiDataScopeService(mock(BiDataScopeStore.class)), Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach void setup() {
        com.rigour.analytics.TestRoleScope.set(new CallerIdentity("TENANT", user, tenant, user,
                null, UUID.randomUUID(), 1, 1, 1, Set.of("sales"), Set.of("analytics:dashboard:read")));
        when(store.categories(anyString())).thenReturn(List.of(new Category("1", null, "台呢"),
                new Category("11", "1", "台呢子分类"), new Category("2", null, "球杆")));
        when(store.load(anyString(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(fact("11", "台呢子分类"), fact("2", "球杆"), fact("11", "台呢子分类")));
    }
    @AfterEach void clear() { com.rigour.analytics.TestRoleScope.clear(); }

    @Test void thresholdsIncludeBoundaryDayAndUnknownNeverBecomesLow() {
        int[] cloth = {0, 10, 11, 20, 21, 30, 31};
        int[] other = {0, 30, 31, 60, 61, 90, 91};
        String[] expected = {"LOW", "LOW", "MEDIUM", "MEDIUM", "HIGH", "HIGH", "SEVERE"};
        for (int i = 0; i < cloth.length; i++) {
            assertThat(PaymentRiskService.classify(cloth[i], true, true)).isEqualTo(expected[i]);
            assertThat(PaymentRiskService.classify(other[i], false, true)).isEqualTo(expected[i]);
        }
        assertThat(PaymentRiskService.classify(10, false, false)).isEqualTo("UNKNOWN");
        assertThat(PaymentRiskService.classify(-1, true, true)).isEqualTo("UNKNOWN");
    }

    @Test void mixedOrderKeepsFullAmountAndOnlyHighestRiskAtBeijingMidnight() {
        var result = query(null, null);
        assertThat(result.orders()).hasSize(1);
        var order = result.orders().getFirst();
        assertThat(order.ageDays()).isEqualTo(25);
        assertThat(order.orderAmount()).isEqualByComparingTo("10000");
        assertThat(order.paidAmount()).isEqualByComparingTo("6000");
        assertThat(order.products()).hasSize(1).extracting(p -> p.riskLevel()).containsExactly("HIGH");
        verify(store).load(eq(tenant.toString()), any(), eq(now), eq("CD"), eq("A"), eq(50001));
    }

    @Test void categoryMultiSelectFiltersStableAttributionIncludingChildren() {
        assertThat(query(List.of(1L, 11L, 2L), null).orders()).hasSize(1);
        assertThat(query(List.of(2L), "HIGH").orders()).isEmpty();
        assertThat(query(List.of(2L), null).orders()).isEmpty();
        assertThat(query(null, "LOW").orders()).isEmpty();
        assertThat(query(List.of(1L), "HIGH").orders().getFirst().products()).hasSize(1);
    }

    @Test void highestRiskWinsBeforeAmountAndTiedProductsUseCombinedLineAmount() {
        var a = fact("11", "台呢子分类");
        var b = new Fact(a.orderId(), a.orderNo(), a.customerName(), a.regionCode(), a.regionName(),
                a.ownerStaffCode(), a.ownerStaffName(), a.orderDate(), a.categoryId(), a.categoryName(),
                "B", "另一个台呢", a.orderAmount(), a.paidAmount(), now, new BigDecimal("1500"));
        when(store.load(anyString(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(b, fact("2", "球杆"), a, a));
        var assigned = query(null, null).orders().getFirst().products().getFirst();
        assertThat(assigned.productId()).isEqualTo("11");
        assertThat(assigned.productAmount()).isEqualByComparingTo("2000");
        when(store.load(anyString(), any(), any(), any(), any(), anyInt())).thenReturn(List.of(a, b));
        assertThat(query(null, null).orders().getFirst().products().getFirst().productId()).isEqualTo("B");
    }

    @Test void rejectsIncompleteResultsUnknownCategoryAndMissingAuthority() {
        assertThatThrownBy(() -> query(List.of(999L), null)).isInstanceOf(BusinessException.class);
        when(store.load(anyString(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.nCopies(50001, fact("2", "球杆")));
        assertThatThrownBy(() -> query(null, null)).isInstanceOf(BusinessException.class).hasMessageContaining("50000");
        com.rigour.analytics.TestRoleScope.clear();
        assertThatThrownBy(() -> query(null, null)).isInstanceOf(AuthorizationDeniedException.class);
    }

    private com.rigour.analytics.api.v1.model.PaymentRiskView query(List<Long> categories, String risk) {
        return service.query(Instant.parse("2026-09-01T00:00:00Z"), now.plusSeconds(86400),
                "CD", "A", categories, risk);
    }
    private Fact fact(String category, String name) {
        return new Fact("1", "DEMO-1", "客户", "CD", "成都", "A", "业务员A",
                Instant.parse("2026-09-24T16:00:00Z"), category, name, category, name,
                new BigDecimal("10000"), new BigDecimal("6000"), now, new BigDecimal("11".equals(category) ? "1000" : "8000"));
    }
}
