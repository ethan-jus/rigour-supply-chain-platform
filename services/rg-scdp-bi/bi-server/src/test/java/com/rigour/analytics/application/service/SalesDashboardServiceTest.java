package com.rigour.analytics.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.analytics.application.port.out.BiDataScopeStore;
import com.rigour.analytics.application.port.out.BiDataScopeStore.*;
import com.rigour.analytics.application.port.out.SalesDashboardStore;
import com.rigour.analytics.application.port.out.BiProductImages;
import com.rigour.analytics.application.model.SalesDashboardData;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

class SalesDashboardServiceTest {
    private final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-24T00:00:00Z");
    private final BiDataScopeStore scopeStore = mock(BiDataScopeStore.class);
    private final SalesDashboardStore data = mock(SalesDashboardStore.class);
    private final BiProductImages images = mock(BiProductImages.class);
    private final SalesDashboardService service =
            new SalesDashboardService(data, new BiDataScopeService(scopeStore), images);

    @BeforeEach
    void setup() {
        com.rigour.analytics.TestRoleScope.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        1,
                        1,
                        1,
                        Set.of("sales"),
                        Set.of("analytics:dashboard:read")));
    }

    @AfterEach
    void clear() {
        com.rigour.analytics.TestRoleScope.clear();
    }

    @Test
    void queryUsesCurrentTenantAndKeepsBusinessFiltersSeparateFromAuthorization() {
        when(data.query(anyString(), any())).thenReturn(snapshot(List.of()));
        service.query(now, now, "HZ", "E2", null, null, null);
        verify(data)
                .query(
                        eq(tenant.toString()),
                        argThat(
                                f ->
                                        "HZ".equals(f.regionCode())
                                                && "E2".equals(f.ownerStaffCode())));
        verifyNoInteractions(scopeStore);
        verifyNoInteractions(images);
    }

    @Test
    void dashboardOnlyUserGetsImagesForScopedProductsWithoutChangingAmounts() {
        var amount = new java.math.BigDecimal("12.34");
        var product = new SalesDashboardData.Product("C", "分类", "101", "商品", "SKU",
                java.math.BigDecimal.TEN, amount, amount, amount, true, null);
        when(data.query(eq(tenant.toString()), any())).thenReturn(snapshot(List.of(product)));
        when(images.urls(tenant.toString(), List.of("101"))).thenReturn(Map.of("101", "https://img.test/101.png"));
        var result = service.query(now, now, "HZ", "E2", null, null, null);
        assertThat(result.products().getFirst().imageUrl()).isEqualTo("https://img.test/101.png");
        assertThat(result.products().getFirst().sales()).isEqualTo(amount);
        assertThat(result.products().getFirst().quantity()).isEqualTo(java.math.BigDecimal.TEN);
        verify(images).urls(tenant.toString(), List.of("101"));
    }

    private SalesDashboardData snapshot(List<SalesDashboardData.Product> products) {
        return new SalesDashboardData(List.of(), List.of(), null, products, List.of(), List.of(), null, now, List.of());
    }
}
