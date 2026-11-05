package com.rigour.settings;

import static org.assertj.core.api.Assertions.*;

import com.rigour.settings.api.v1.model.*;
import com.rigour.settings.application.service.BusinessDictionaryService;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TenantDictionaryIntegrationTest {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("rigour_settings")
                    .withUsername("rigour_settings_test")
                    .withPassword("rigour_settings_test");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        r.add("spring.flyway.enabled", () -> true);
        r.add("spring.flyway.url", MYSQL::getJdbcUrl);
        r.add("spring.flyway.user", MYSQL::getUsername);
        r.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired BusinessDictionaryService service;
    @Autowired JdbcTemplate jdbc;
    UUID tenant;
    UUID user;

    @BeforeEach
    void setup() {
        tenant = UUID.randomUUID();
        user = UUID.randomUUID();
        actor(tenant, "TENANT");
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    void actor(UUID t, String scope) {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        scope,
                        user,
                        t,
                        "SERVICE".equals(scope) ? null : user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of(
                                "business-settings:dict:read",
                                "business-settings:dict:write",
                                "business-settings:dict:sync")));
    }

    @Test
    void storeBusinessStatesKeepCodesWhileAllowingTenantLabelsAndOrdering() {
        var dictionary=service.resolve("CRM_STORE_BUSINESS_STATUS");
        assertThat(dictionary.dictionary().allowNewItems()).isFalse();
        assertThat(dictionary.items()).extracting(DictItemView::dictionaryItemCode)
                .containsExactly("UNKNOWN","OPEN","TEMP_CLOSED","CLOSED");
        var open=dictionary.items().stream().filter(i->"OPEN".equals(i.dictionaryItemCode())).findFirst().orElseThrow();
        var changed=service.updateItem(open.id(),new DictItemCommand("CRM_STORE_BUSINESS_STATUS",null,"OPEN","正常营业","现场核实",1,open.revision(),true));
        assertThat(changed.dictionaryItemCode()).isEqualTo("OPEN");
        assertThat(changed.dictionaryItemName()).isEqualTo("正常营业");
        assertThat(changed.ordinal()).isEqualTo(1);
        assertThatThrownBy(()->service.createItem(dictionary.dictionary().id(),new DictItemCommand("CRM_STORE_BUSINESS_STATUS",null,"ARBITRARY","任意状态",null,50,0,true)))
                .hasMessageContaining("不能新增状态");
        assertThatThrownBy(()->service.updateItem(changed.id(),new DictItemCommand("CRM_STORE_BUSINESS_STATUS",null,"OPEN","正常营业",null,1,changed.revision(),false)))
                .hasMessageContaining("仅允许修改显示名称");
        assertThatThrownBy(()->service.updateItem(changed.id(),new DictItemCommand("CRM_STORE_BUSINESS_STATUS",null,"NEW_CODE","正常营业",null,1,changed.revision(),true)))
                .hasMessageContaining("编码和所属字典不可修改");
        actor(UUID.randomUUID(),"TENANT");
        assertThat(service.resolve("CRM_STORE_BUSINESS_STATUS").items())
                .filteredOn(i->"OPEN".equals(i.dictionaryItemCode())).extracting(DictItemView::dictionaryItemName).containsExactly("营业中");
    }

    @Test
    void tenantOverrideDoesNotChangeBaselineOrAnotherTenantAndSourceCannotUndoManualLabel() {
        jdbc.update(
                "INSERT INTO"
                    + " data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,created_by)"
                    + " VALUES('GLOBAL','PRODUCT_UNIT','BASE_UNIT','基线单位','SYSTEM')");
        var baseline = service.resolve("PRODUCT_UNIT");
        var original = baseline.items().getFirst();
        var updated =
                service.updateItem(
                        original.id(),
                        new DictItemCommand(
                                "PRODUCT_UNIT",
                                original.parentDictionaryItemCode(),
                                original.dictionaryItemCode(),
                                "杭州箱",
                                null,
                                0,
                                original.revision(),
                                true));
        assertThat(service.resolve("PRODUCT_UNIT").items())
                .anyMatch(i -> i.dictionaryItemName().equals("杭州箱"));
        actor(UUID.randomUUID(), "TENANT");
        assertThat(service.resolve("PRODUCT_UNIT").items())
                .noneMatch(i -> i.dictionaryItemName().equals("杭州箱"));
        assertThatThrownBy(
                        () ->
                                service.updateItem(
                                        updated.id(),
                                        new DictItemCommand(
                                                "PRODUCT_UNIT",
                                                null,
                                                updated.dictionaryItemCode(),
                                                "越权",
                                                null,
                                                0,
                                                updated.revision(),
                                                true)))
                .hasMessageContaining("不存在");
        actor(tenant, "SERVICE");
        service.syncItems(
                new DictSyncCommand(
                        "PRODUCT_UNIT",
                        List.of(new DictSourceValue(original.dictionaryItemCode(), "来源名称"))));
        assertThat(service.resolve("PRODUCT_UNIT").items())
                .anyMatch(i -> i.dictionaryItemName().equals("杭州箱"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT dictionary_item_name FROM data_dictionary_item WHERE"
                                        + " tenant_key='GLOBAL' AND id=?",
                                String.class,
                                original.id()))
                .isEqualTo(original.dictionaryItemName());
    }

    @Test
    void disabledParentIsUnavailableForNewSelectionsButHistoryRemainsAndStaleUpdateFails() {
        var d = service.create(new DictCommand("CUSTOM_OPTIONS", "业务选项", "COMMON", null, 0));
        var parent =
                service.createItem(
                        d.id(),
                        new DictItemCommand(
                                "CUSTOM_OPTIONS", null, "PARENT", "父项", null, 0, 0, true));
        service.createItem(
                d.id(),
                new DictItemCommand("CUSTOM_OPTIONS", "PARENT", "CHILD", "子项", null, 1, 0, true));
        service.updateItem(
                parent.id(),
                new DictItemCommand(
                        "CUSTOM_OPTIONS", null, "PARENT", "父项", null, 0, parent.revision(), false));
        assertThat(service.effective("CUSTOM_OPTIONS").items()).isEmpty();
        assertThat(service.resolve("CUSTOM_OPTIONS").items()).hasSize(2);
        assertThatThrownBy(
                        () ->
                                service.updateItem(
                                        parent.id(),
                                        new DictItemCommand(
                                                "CUSTOM_OPTIONS",
                                                null,
                                                "PARENT",
                                                "旧请求",
                                                null,
                                                0,
                                                parent.revision(),
                                                true)))
                .hasMessageContaining("已被修改");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM settings_operation_audit WHERE tenant_id=?",
                                Integer.class,
                                tenant.toString()))
                .isEqualTo(4);
        actor(UUID.randomUUID(), "TENANT");
        assertThat(service.list(null, "CUSTOM_OPTIONS")).isEmpty();
    }

    @Test
    void sourceSyncResolvesOnlyUsableStandardItemsAndNeverOverwritesTenantChoices() {
        var d = service.resolve("PRODUCT_UNIT").dictionary();
        actor(tenant, "SERVICE");
        var unknown =
                service.syncItems(
                        new DictSyncCommand(
                                "PRODUCT_UNIT", List.of(new DictSourceValue("SYNC_UNIT", "同步单位"))));
        assertThat(unknown.created()).isZero();
        assertThat(unknown.blocked()).isEqualTo(1);
        assertThat(service.resolve("PRODUCT_UNIT").items())
                .noneMatch(i -> i.dictionaryItemCode().equals("SYNC_UNIT"));
        actor(tenant, "TENANT");
        var item =
                service.createItem(
                        d.id(),
                        new DictItemCommand(
                                "PRODUCT_UNIT", null, "SYNC_UNIT", "人工单位", null, 0, 0, true));
        actor(tenant, "SERVICE");
        var resolved =
                service.syncItems(
                        new DictSyncCommand(
                                "PRODUCT_UNIT", List.of(new DictSourceValue("SYNC_UNIT", "来源名称"))));
        assertThat(resolved.resolutions()).containsKey("SYNC_UNIT");
        assertThat(service.resolve("PRODUCT_UNIT").items())
                .anyMatch(i -> i.dictionaryItemName().equals("人工单位"));
        actor(tenant, "TENANT");
        service.updateItem(
                item.id(),
                new DictItemCommand(
                        "PRODUCT_UNIT",
                        null,
                        "SYNC_UNIT",
                        "人工单位",
                        null,
                        0,
                        item.revision(),
                        false));
        actor(tenant, "SERVICE");
        assertThat(
                        service.syncItems(
                                        new DictSyncCommand(
                                                "PRODUCT_UNIT",
                                                List.of(new DictSourceValue("SYNC_UNIT", "其他名称"))))
                                .blocked())
                .isEqualTo(1);
        actor(UUID.randomUUID(), "TENANT");
        assertThat(service.resolve("PRODUCT_UNIT").items())
                .noneMatch(i -> i.dictionaryItemCode().equals("SYNC_UNIT"));
    }

    @Test
    void processStatusCannotInventNewStateAndParentCycleRollsBack() {
        var d = service.resolve("SALES_ORDER_STATUS").dictionary();
        assertThatThrownBy(
                        () ->
                                service.createItem(
                                        d.id(),
                                        new DictItemCommand(
                                                d.dictionaryCode(),
                                                null,
                                                "MADE_UP",
                                                "伪造状态",
                                                null,
                                                0,
                                                0,
                                                true)))
                .hasMessageContaining("流程控制");
        var custom = service.create(new DictCommand("TREE_OPTIONS", "树", "COMMON", null, 0));
        var a =
                service.createItem(
                        custom.id(),
                        new DictItemCommand("TREE_OPTIONS", null, "AA", "甲", null, 0, 0, true));
        service.createItem(
                custom.id(),
                new DictItemCommand("TREE_OPTIONS", "AA", "BB", "乙", null, 0, 0, true));
        assertThatThrownBy(
                        () ->
                                service.updateItem(
                                        a.id(),
                                        new DictItemCommand(
                                                "TREE_OPTIONS",
                                                "BB",
                                                "AA",
                                                "甲",
                                                null,
                                                0,
                                                a.revision(),
                                                true)))
                .hasMessageContaining("循环");
        assertThat(service.resolve("TREE_OPTIONS").items().getFirst().parentDictionaryItemCode())
                .isNull();
    }




    @Test
    void mergingBuiltinChoicesPreservesOtherTenantsAndFlattensHistoricalAliases() {
        var original = service.resolve("PRODUCT_UNIT");
        var roots =
                original.items().stream()
                        .filter(
                                i ->
                                        !i.alias()
                                                && i.enabled()
                                                && i.parentDictionaryItemCode() == null)
                        .filter(
                                i ->
                                        original.items().stream()
                                                .noneMatch(
                                                        c ->
                                                                i.dictionaryItemCode()
                                                                        .equals(
                                                                                c
                                                                                        .parentDictionaryItemCode())))
                        .limit(2)
                        .toList();
        var source = roots.get(0);
        var target = roots.get(1);
        var older =
                service.createItem(
                        original.dictionary().id(),
                        new DictItemCommand(
                                "PRODUCT_UNIT",
                                null,
                                "TEST_OLD_ALIAS",
                                "旧单位样例",
                                null,
                                500,
                                0,
                                true));
        service.merge(
                older.id(),
                new DictMergeCommand(source.id(), older.revision(), source.revision(), "第一次归并"));
        var preview = service.previewMerge(source.id(), target.id());
        assertThat(preview.aliasReferences()).isGreaterThanOrEqualTo(1);
        service.merge(
                source.id(),
                new DictMergeCommand(target.id(), source.revision(), target.revision(), "第二次归并"));
        assertThat(service.resolve("PRODUCT_UNIT").items())
                .filteredOn(
                        i ->
                                i.dictionaryItemCode().equals(source.dictionaryItemCode())
                                        || i.dictionaryItemCode().equals("TEST_OLD_ALIAS"))
                .allSatisfy(
                        i -> {
                            assertThat(i.canonicalItemCode())
                                    .isEqualTo(target.dictionaryItemCode());
                            assertThat(i.dictionaryItemName())
                                    .isEqualTo(target.dictionaryItemName());
                        });
        assertThat(service.effective("PRODUCT_UNIT").items())
                .noneMatch(
                        i ->
                                i.dictionaryItemCode().equals(source.dictionaryItemCode())
                                        || i.dictionaryItemCode().equals("TEST_OLD_ALIAS"));
        assertThatThrownBy(
                        () ->
                                service.merge(
                                        source.id(),
                                        new DictMergeCommand(
                                                target.id(),
                                                source.revision(),
                                                target.revision(),
                                                "重复请求")))
                .isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT canonical_item_code FROM data_dictionary_item WHERE id=?",
                                String.class,
                                source.id()))
                .isNull();
        actor(UUID.randomUUID(), "TENANT");
        assertThat(service.resolve("PRODUCT_UNIT").items())
                .anyMatch(
                        i ->
                                i.dictionaryItemCode().equals(source.dictionaryItemCode())
                                        && !i.alias());
        assertThat(service.resolve("PRODUCT_UNIT").items())
                .noneMatch(i -> i.dictionaryItemCode().equals("TEST_OLD_ALIAS"));
        assertThatThrownBy(() -> service.previewMerge(older.id(), target.id()))
                .hasMessageContaining("不存在");
    }

    @Test
    void duplicateNameCannotUpdateAnotherCodeAndWorkflowValuesCannotMerge() {
        var d = service.create(new DictCommand("NAME_GUARD", "名称保护", "COMMON", null, 0));
        var a =
                service.createItem(
                        d.id(),
                        new DictItemCommand("NAME_GUARD", null, "AA", "名称一", null, 0, 0, true));
        var b =
                service.createItem(
                        d.id(),
                        new DictItemCommand("NAME_GUARD", null, "BB", "名称二", null, 0, 0, true));
        assertThatThrownBy(
                        () ->
                                service.updateItem(
                                        b.id(),
                                        new DictItemCommand(
                                                "NAME_GUARD",
                                                null,
                                                "BB",
                                                "名称一",
                                                null,
                                                0,
                                                b.revision(),
                                                true)))
                .hasMessageContaining("同名");
        assertThat(service.resolve("NAME_GUARD").items()).contains(a, b);
        var states =
                service.resolve("SALES_ORDER_STATUS").items().stream()
                        .filter(i -> !i.alias())
                        .limit(2)
                        .toList();
        assertThat(service.previewMerge(states.get(0).id(), states.get(1).id()).blockers())
                .anyMatch(v -> v.contains("流程控制"));
    }
    @Test
    void paymentDictionariesMigrateWithTenantOverridesAndWorkflowGuards() {
        var expected = Map.of(
                "SALES_ORDER_STATUS", List.of("DRAFT", "SUBMITTED", "COMPLETED", "CANCELLED", "DELETED"),
                "ORDER_COLLECTION_STATUS", List.of("UNPAID", "PARTIAL_PAID", "PAID", "NOT_REQUIRED"),
                "PAYMENT_RECEIPT_STATUS", List.of("PENDING", "RECEIVED", "CANCELLED"),
                "PAYMENT_CONFIRMATION_STATUS", List.of("UNCONFIRMED", "CONFIRMED"),
                "PAYMENT_VOUCHER_AUDIT_STATUS", List.of("PENDING_REVIEW", "NORMAL", "ABNORMAL"));
        for (var entry : expected.entrySet()) {
            var view = service.resolve(entry.getKey());
            assertThat(view.items()).extracting(DictItemView::dictionaryItemCode).containsExactlyElementsOf(entry.getValue());
            var first = view.items().getFirst();
            var second = view.items().get(1);
            assertThatThrownBy(() -> service.createItem(view.dictionary().id(), new DictItemCommand(entry.getKey(), null, "INVENTED", "新增状态", null, 99, 0, true))).hasMessageContaining("流程控制");
            assertThatThrownBy(() -> service.updateItem(first.id(), new DictItemCommand(entry.getKey(), null, first.dictionaryItemCode(), first.dictionaryItemName(), null, 10, first.revision(), false))).hasMessageContaining("流程控制");
            assertThat(service.previewMerge(first.id(), second.id()).blockers()).anyMatch(v -> v.contains("流程控制"));
        }
        assertThat(service.resolve("ORDER_COLLECTION_STATUS").items())
                .anyMatch(i -> i.dictionaryItemCode().equals("PAID") && i.dictionaryItemName().equals("已结清"));
        assertThat(service.resolve("SALES_ORDER_STATUS").items())
                .anyMatch(i -> i.dictionaryItemCode().equals("SUBMITTED") && i.dictionaryItemName().equals("进行中"));
        assertThat(service.resolve("SALES_ORDER_STATUS").items())
                .anyMatch(i -> i.dictionaryItemCode().equals("COMPLETED")
                        && i.remark().contains("来源完成") && i.remark().contains("不代表收款结清"));
        var original = service.resolve("ORDER_COLLECTION_STATUS").items().getFirst();
        service.updateItem(original.id(), new DictItemCommand("ORDER_COLLECTION_STATUS", null, "UNPAID", "租户未到账", "租户说明", 99, original.revision(), true));
        assertThat(service.resolve("ORDER_COLLECTION_STATUS").items()).anyMatch(i -> i.dictionaryItemName().equals("租户未到账") && i.ordinal() == 99);
        actor(UUID.randomUUID(), "TENANT");
        assertThat(service.resolve("ORDER_COLLECTION_STATUS").items()).anyMatch(i -> i.dictionaryItemName().equals("未收款"));
        actor(tenant, "TENANT");
        var reasons = service.resolve("PAYMENT_VOUCHER_AUDIT_REASON");
        assertThat(reasons.items()).hasSize(7);
        service.createItem(reasons.dictionary().id(), new DictItemCommand("PAYMENT_VOUCHER_AUDIT_REASON", null, "BANK_FEE", "银行手续费待核", null, 80, 0, true));
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, tenant, user, null, UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("order:read")));
        for (String code : expected.keySet()) assertThat(service.resolve(code).items()).isNotEmpty();
        assertThat(service.resolve("PAYMENT_VOUCHER_AUDIT_REASON").items()).anyMatch(i -> i.dictionaryItemCode().equals("BANK_FEE"));
        assertThatThrownBy(() -> service.createItem(reasons.dictionary().id(), new DictItemCommand("PAYMENT_VOUCHER_AUDIT_REASON", null, "UNAUTHORIZED", "越权", null, 90, 0, true))).isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void legacyOrderStateCleanupPreservesHistoryAndTenantLabels() {
        var draft = service.resolve("SALES_ORDER_STATUS").items().stream()
                .filter(item -> item.dictionaryItemCode().equals("DRAFT")).findFirst().orElseThrow();
        service.updateItem(draft.id(), new DictItemCommand("SALES_ORDER_STATUS", null, "DRAFT",
                "租户草稿", null, 10, draft.revision(), true));
        var names = List.of("已结清", "待回款", "未审核", "已审核", "驳回");
        try {
            for (String key : List.of("GLOBAL", tenant.toString())) {
                for (int n=0; n<names.size(); n++) jdbc.update("""
                        INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
                        VALUES(?,'SALES_ORDER_STATUS',?,?,?,'legacy-sync')
                        """, key, "AUTO_LEGACY_"+n, names.get(n), 100+n);
            }
            assertThat(service.effective("SALES_ORDER_STATUS").items()).hasSize(10);
            var cleanup = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/migration/V53__retire_legacy_order_status_items.sql"));
            cleanup.execute(jdbc.getDataSource());
            assertThat(service.effective("SALES_ORDER_STATUS").items())
                    .extracting(DictItemView::dictionaryItemCode)
                    .containsExactly("DRAFT","SUBMITTED","COMPLETED","CANCELLED");
            assertThat(service.resolve("SALES_ORDER_STATUS").items())
                    .anyMatch(item -> item.dictionaryItemCode().equals("DRAFT") && item.dictionaryItemName().equals("租户草稿"))
                    .anyMatch(item -> item.dictionaryItemName().equals("已结清") && !item.enabled());
            cleanup.execute(jdbc.getDataSource());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM data_dictionary_item WHERE dictionary_code='SALES_ORDER_STATUS' AND dictionary_item_code LIKE 'AUTO_LEGACY_%' AND deleted=0 AND enabled=0 AND revision=2",Integer.class)).isEqualTo(10);
            assertThat(service.effective("ORDER_COLLECTION_STATUS").items())
                    .anyMatch(item -> item.dictionaryItemCode().equals("PAID") && item.dictionaryItemName().equals("已结清"));
        } finally {
            jdbc.update("UPDATE data_dictionary_item SET enabled=1 WHERE dictionary_code='SALES_ORDER_STATUS' AND dictionary_item_code='DELETED'");
            jdbc.update("DELETE FROM data_dictionary_item WHERE dictionary_code='SALES_ORDER_STATUS' AND dictionary_item_code LIKE 'AUTO_LEGACY_%'");
        }
    }

}
