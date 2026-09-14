package com.rigour.integration.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class FeishuReconciliationPropertiesTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PREFIX = "rigour.integration.feishu.reconciliation";

    @Test
    void defaultsEmptyWithoutAnyRealTenantOrBaseFallback() {
        assertThat(new FeishuReconciliationProperties().forTenant(TENANT)).isEmpty();
    }

    @Test
    void bindsExactConfigAndOptionalThirdTableAndViewWithoutShowingAnotherTenant() {
        var values = settings();
        values.put(PREFIX + ".sources[0].tables[2].table-id", "tblProduct");
        values.put(PREFIX + ".sources[0].tables[2].table-code", "FEISHU_PRODUCT");
        values.put(PREFIX + ".sources[0].tables[1].view-id", "viwSubset");
        var properties = bind(values);
        var source = properties.forTenant(TENANT).getFirst();
        assertThat(source.id()).isEqualTo("source-fixture");
        assertThat(source.tables()).hasSize(3);
        assertThat(source.filtered()).isTrue();
        assertThat(source.tables().getLast().name()).isEqualTo("商品库");
        assertThat(properties.forTenant(UUID.randomUUID())).isEmpty();
        assertThat(properties.forTenant(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALES_ORDER", "SALES_ORDER_ITEM", "FEISHU_STAFF", "FEISHU_PRODUCT_CALCULATION", ""})
    void rejectsTableCodesOutsideExplicitReconciliationContract(String tableCode) {
        var values = settings();
        values.put(PREFIX + ".sources[0].tables[0].table-code", tableCode);
        assertThatThrownBy(() -> bind(values).forTenant(TENANT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsDuplicateIdsTablesAndMissingRequiredTable() {
        var duplicateIds = bind(settings());
        duplicateIds.setSources(List.of(duplicateIds.getSources().getFirst(), duplicateIds.getSources().getFirst()));
        assertThatThrownBy(() -> duplicateIds.forTenant(TENANT)).isInstanceOf(IllegalStateException.class);
        var values = settings();
        values.put(PREFIX + ".sources[0].tables[1].table-id", "tblOrder");
        var duplicateTables = bind(values);
        assertThatThrownBy(() -> duplicateTables.forTenant(TENANT)).isInstanceOf(IllegalStateException.class);
        values = settings();
        values.put(PREFIX + ".sources[0].tables[1].table-code", "FEISHU_PRODUCT");
        var missingLine = bind(values);
        assertThatThrownBy(() -> missingLine.forTenant(TENANT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUrlOrQueryInjectionInConfiguredIdentifiers() {
        for (String key : List.of("app-token", "tables[0].table-id", "tables[0].view-id")) {
            var values = settings();
            values.put(PREFIX + ".sources[0]." + key, "https://other.invalid/?token=x");
            var properties = bind(values);
            assertThatThrownBy(() -> properties.forTenant(TENANT)).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("other.invalid");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://tenant-fixture.feishu.cn/base/appFixture",
            "https://tenant-fixture.feishu.cn/base/appFixture?table=tblOrder&view=viwFixture",
            "https://TENANT-FIXTURE.FEISHU.CN:443/base/appFixture"
    })
    void bindsServerOnlySourceUrlAndPreservesItsTenantHost(String url) {
        var values = settings();
        values.put(PREFIX + ".sources[0].source-url", url);
        assertThat(bind(values).forTenant(TENANT).getFirst().sourceUrl()).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://tenant.feishu.cn/base/appFixture",
            "https://tenant.feishu.cn/base/otherApp",
            "https://tenant.feishu.cn/base/appFixture/extra",
            "https://tenant.feishu.cn/base/%61ppFixture",
            "https://tenant.feishu.cn.evil.example/base/appFixture",
            "https://evilfeishu.cn/base/appFixture",
            "https://evil.example/base/appFixture",
            "https://person@tenant.feishu.cn/base/appFixture",
            "https://tenant.feishu.cn:8443/base/appFixture",
            "https://tenant.feishu.cn/base/appFixture#fragment",
            "//tenant.feishu.cn/base/appFixture",
            "not a url"
    })
    void rejectsUntrustedSourceUrlsWithoutEchoingThem(String url) {
        var values = settings();
        values.put(PREFIX + ".sources[0].source-url", url);
        assertThatThrownBy(() -> bind(values).forTenant(TENANT)).isInstanceOf(IllegalStateException.class)
                .hasMessage("当前租户飞书对账来源配置无效，请联系管理员核对配置");
    }

    @Test
    void absentUrlPreservesLegacySourceConstructorAndFallback() {
        var source = bind(settings()).forTenant(TENANT).getFirst();
        assertThat(source.sourceUrl()).isEqualTo("https://feishu.cn/base/appFixture");
        var legacy = new com.rigour.integration.application.port.out.FeishuReconciliationSources.Source(
                source.id(), source.name(), source.tenantId(), source.appToken(), source.tables());
        assertThat(legacy.sourceUrl()).isEqualTo(source.sourceUrl());
    }

    private static FeishuReconciliationProperties bind(Map<String, Object> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bind(PREFIX,
                Bindable.of(FeishuReconciliationProperties.class)).get();
    }
    private static Map<String, Object> settings() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PREFIX + ".sources[0].id", "source-fixture");
        values.put(PREFIX + ".sources[0].name", "销售只读来源");
        values.put(PREFIX + ".sources[0].tenant-id", TENANT.toString());
        values.put(PREFIX + ".sources[0].app-token", "appFixture");
        values.put(PREFIX + ".sources[0].tables[0].table-id", "tblOrder");
        values.put(PREFIX + ".sources[0].tables[0].table-code", "FEISHU_SALES_ORDER");
        values.put(PREFIX + ".sources[0].tables[1].table-id", "tblLine");
        values.put(PREFIX + ".sources[0].tables[1].table-code", "FEISHU_SALES_ORDER_LINE");
        return values;
    }
}
