package com.rigour.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rigour.merchant.api.v1.model.ExternalCrmCustomerRowCommand;
import com.rigour.merchant.application.port.out.CrmCustomerQueryStore;
import com.rigour.merchant.application.port.out.CrmInternalCustomerStore;
import com.rigour.merchant.application.port.out.CrmMasterDataStore;
import com.rigour.merchant.application.port.out.CrmMasterDataStore.ImportResult;
import com.rigour.merchant.application.port.out.CrmMasterDataStore.RunStatistics;
import com.rigour.merchant.application.port.out.DhbCrmMasterDataClient.SourceRecord;
import com.rigour.merchant.domain.model.CrmMasterDataObjectType;
import com.rigour.merchant.infrastructure.persistence.CrmUuidCodec;
import com.rigour.shared.core.code.BusinessCodeGenerator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CrmApplicationTests {

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("rigour_crm")
                    .withUsername("rigour_crm_test")
                    .withPassword("rigour_crm_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private com.rigour.merchant.application.port.out.CustomerShippingAddressStore shippingStore;

    @Autowired private CrmMasterDataStore store;

    @Autowired private CrmCustomerQueryStore queryStore;

    @Autowired private CrmInternalCustomerStore internalCustomerStore;

    @Autowired
    private com.rigour.merchant.application.port.out.CustomerAuthorityProjectionStore
            authorityProjection;

    @Autowired
    private com.rigour.merchant.application.port.out.AnalyticsSourceSnapshotStore sourceSnapshot;

    @Test
    void areaMaintenancePreservesCustomerReferencesAndExposesSortAndAudit() {
        UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID();
        var root = queryStore.createCustomerArea(tenant, "ROOT", new com.rigour.merchant.api.v1.model.CrmCustomerAreaCommand("全国", null, "ACTIVE", 0, 1), actor);
        var city = queryStore.createCustomerArea(tenant, "CITY", new com.rigour.merchant.api.v1.model.CrmCustomerAreaCommand("杭州", "ROOT", "ACTIVE", 0, 20), actor);
        jdbcTemplate.update("INSERT INTO crm_customer(tenant_id,customer_code,customer_name,region_code,status_code,revision,created_time,updated_time,deleted) VALUES(?, 'KEEP', '已有客户', 'CITY', 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0)", tenant.toString());
        var edited = queryStore.updateCustomerArea(tenant, city.id(), new com.rigour.merchant.api.v1.model.CrmCustomerAreaCommand("杭州市", "ROOT", "ACTIVE", 1, 2), actor);
        assertThat(edited.code()).isEqualTo("CITY");
        assertThat(edited.sortOrder()).isEqualTo(2);
        assertThat(edited.createdBy()).isEqualTo(actor.toString());
        assertThat(edited.updatedBy()).isEqualTo(actor.toString());
        assertThat(edited.createdTime()).isNotNull();
        assertThat(edited.updatedTime()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT region_code FROM crm_customer WHERE tenant_id=? AND customer_code='KEEP'", String.class, tenant.toString())).isEqualTo("CITY");
        var page = queryStore.customerAreas(tenant, 0, 200, null);
        assertThat(page.items()).extracting(com.rigour.merchant.api.v1.model.DictionaryView::code).containsExactly("ROOT", "CITY");
        assertThat(page.items().get(1).sortOrder()).isEqualTo(2);
        assertThat(page.items().get(1).createdTime()).isNotNull();
        assertThatThrownBy(() -> queryStore.updateCustomerArea(tenant, root.id(), new com.rigour.merchant.api.v1.model.CrmCustomerAreaCommand("全国", "CITY", "ACTIVE", 1, 1), actor)).hasMessageContaining("循环");
        assertThatThrownBy(() -> queryStore.deleteCustomerArea(tenant, city.id(), 2, actor)).hasMessageContaining("引用");
        // A getArea response omits hierarchy: a previously bound local area must retain its identity and parent.
        UUID connector = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO crm_source_binding(id,tenant_id,connector_id,source_system,source_object_type,source_object_id,target_type,target_id,binding_status,source_fields_json,source_payload_hash,synced_at,created_time,updated_time) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'DINGHUOBAO','CUSTOMER_AREA','44077','CUSTOMER_AREA',UUID_TO_BIN(?),'RESOLVED',JSON_OBJECT(),REPEAT('0',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", UUID.randomUUID().toString(), tenant.toString(), connector.toString(), city.id().toString());
        UUID run = start(tenant, connector, actor, CrmMasterDataObjectType.CUSTOMER_AREA);
        var imported = store.importRecord(tenant, connector, run, CrmMasterDataObjectType.CUSTOMER_AREA,
                new SourceRecord("44077", null, "杭州市", null, null, null, Map.of("AreaID", "44077", "AreaName", "杭州市")));
        finish(tenant, connector, run, CrmMasterDataObjectType.CUSTOMER_AREA, imported);
        var unchanged = queryStore.customerAreas(tenant, 0, 200, null);
        assertThat(unchanged.total()).isEqualTo(2);
        var cityAfter = unchanged.items().stream().filter(r -> r.code().equals("CITY")).findFirst().orElseThrow();
        assertThat(cityAfter.id()).isEqualTo(city.id());
        assertThat(cityAfter.parentCode()).isEqualTo("ROOT");
        assertThat(cityAfter.sortOrder()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT region_code FROM crm_customer WHERE tenant_id=? AND customer_code='KEEP'", String.class, tenant.toString())).isEqualTo("CITY");
        // 地区映射以内部编码发布给Integration：没有数字内部ID，订单同步按来源ID或名称解析。
        var areaMappings =
                store.externalObjectMappings(
                        tenant, connector, run, CrmMasterDataObjectType.CUSTOMER_AREA);
        assertThat(areaMappings).hasSize(1);
        assertThat(areaMappings.getFirst().sourceObjectId()).isEqualTo("44077");
        assertThat(areaMappings.getFirst().sourceObjectNo()).isEqualTo("杭州市");
        assertThat(areaMappings.getFirst().internalDomain()).isEqualTo("CRM");
        assertThat(areaMappings.getFirst().internalObjectType()).isEqualTo("CUSTOMER_AREA");
        assertThat(areaMappings.getFirst().internalObjectId()).isNull();
        assertThat(areaMappings.getFirst().internalObjectNo()).isEqualTo("CITY");
    }

    @Test
    void sourceSnapshotContractUsesOnlyKnownTenantDatasets() {
        String emptyTenant = java.util.UUID.randomUUID().toString();
        for (String dataset :
                java.util.List.of(
                        "CRM_CONTACT",
                        "CRM_CUSTOMER",
                        "CRM_CUSTOMER_AREA",
                        "CRM_CUSTOMER_POLICY",
                        "CRM_CUSTOMER_TYPE")) {
            var page = sourceSnapshot.page(emptyTenant, dataset, "");
            assertThat(page.items()).isEmpty();
            assertThat(page.version()).isEqualTo(sourceSnapshot.version(emptyTenant, dataset));
        }
        assertThatThrownBy(() -> sourceSnapshot.page(emptyTenant, "iam_user", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authorityExportReadsOnlyCurrentTenantAndDetectsRegionChanges() {
        String tenant = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO"
                    + " crm_customer(tenant_id,customer_code,customer_name,owner_employee_code,region_code)"
                    + " VALUES(?,'BI-C','客户','EMP-A','HZ'),(?,'BI-X','其他','EMP-B','NB')",
                tenant,
                other);
        jdbcTemplate.update(
                "INSERT INTO"
                    + " crm_customer_area(id,tenant_id,area_code,area_name,parent_area_code,created_time,updated_time)"
                    + " VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'ZJ','浙江',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),(UUID_TO_BIN(?),UUID_TO_BIN(?),'HZ','杭州','ZJ',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                UUID.randomUUID().toString(),
                tenant,
                UUID.randomUUID().toString(),
                tenant);
        var page = authorityProjection.page(tenant, 0, 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().get("employeeCode")).isEqualTo("EMP-A");
        assertThat(page.items().getFirst().get("regionPath")).isEqualTo(List.of("ZJ", "HZ"));
        long id = ((Number) page.items().getFirst().get("id")).longValue();
        assertThat(authorityProjection.page(tenant, id, 1).items()).isEmpty();
        jdbcTemplate.update(
                "UPDATE crm_customer_area SET revision=revision+1,parent_area_code=NULL WHERE"
                    + " tenant_id=UUID_TO_BIN(?) AND area_code='HZ'",
                tenant);
        assertThat(authorityProjection.version(tenant)).isNotEqualTo(page.version());
        assertThat(authorityProjection.page(tenant, 0, 1).items().getFirst().get("regionPath"))
                .isEqualTo(List.of("HZ"));
    }

    @Test
    void contextLoadsAndMigratesAllCrmTables() {
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT COUNT(*) FROM information_schema.tables
                                 WHERE table_schema = DATABASE() AND table_name LIKE 'crm\\_%'
                                """,
                                Integer.class))
                .isEqualTo(25);
        assertThat(
                        jdbcTemplate.queryForList(
                                """
                                SELECT table_name FROM information_schema.tables
                                 WHERE table_schema = DATABASE() AND table_name LIKE 'crm\\_%'
                                """,
                                String.class))
                .contains(
                        "crm_party",
                        "crm_customer_profile",
                        "crm_contact",
                        "crm_address",
                        "crm_source_binding",
                        "crm_source_identity_alias",
                        "crm_sync_run",
                        "crm_sync_checkpoint",
                        "crm_customer_identity_guard",
                        "crm_sync_lock");
        // V9 仅解除旧员工表关联；物理清理属于备份切流后的 DBA 流程，不在 Flyway 测试中执行。
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT COUNT(*) FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name = 'crm_external_staff'
""",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT COUNT(*) FROM information_schema.columns
                                 WHERE table_schema=DATABASE() AND table_name='crm_sales_assignment'
                                   AND column_name='external_staff_id'
                                """,
                                Integer.class))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT COUNT(*) FROM information_schema.columns
                                 WHERE table_schema=DATABASE() AND table_name='crm_sync_run'
                                   AND column_name='source_task_id'
                                """,
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT COUNT(*) FROM information_schema.table_constraints
                                 WHERE constraint_schema=DATABASE() AND table_name='crm_sync_run'
                                   AND constraint_name='chk_crm_sync_run_skipped_terminal'
                                """,
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void persistsFinishedSkipAuditAndRecoversOnlyExpiredUnownedRun() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID sourceTaskId = UUID.randomUUID();
        String longCode = "x".repeat(80);
        String longReason = "first\r\nsecond" + "x".repeat(2100);

        UUID skippedRun =
                store.recordSkippedRun(
                        tenantId,
                        connectorId,
                        sourceTaskId,
                        CrmMasterDataObjectType.CUSTOMER,
                        100,
                        longCode,
                        longReason);
        Map<String, Object> skipped =
                jdbcTemplate.queryForMap(
                        """
                        SELECT status,source_task_id,error_code,error_message,finished_at
                          FROM crm_sync_run WHERE tenant_id=? AND id=?
                        """,
                        CrmUuidCodec.encode(tenantId),
                        CrmUuidCodec.encode(skippedRun));
        assertThat(skipped).containsEntry("status", "SKIPPED");
        assertThat(CrmUuidCodec.decode((byte[]) skipped.get("source_task_id")))
                .isEqualTo(sourceTaskId);
        assertThat(String.valueOf(skipped.get("error_code"))).hasSize(64);
        assertThat(String.valueOf(skipped.get("error_message")))
                .hasSize(2000)
                .doesNotContain("\r", "\n");
        assertThat(skipped.get("finished_at")).isNotNull();
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        """
INSERT INTO crm_sync_run(
    id,tenant_id,connector_id,source_system,object_type,trigger_type,
    sync_mode,status,page_size,max_pages,fetched_count,created_count,
    changed_count,repaired_count,duplicate_count,absent_count,rejected_count,
    started_at,created_time,updated_time)
VALUES (?,?,?,'DINGHUOBAO','CUSTOMER','SCHEDULED','FULL','SKIPPED',
        500,100,0,0,0,0,0,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
""",
                                        CrmUuidCodec.encode(UUID.randomUUID()),
                                        CrmUuidCodec.encode(tenantId),
                                        CrmUuidCodec.encode(connectorId)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("chk_crm_sync_run_skipped_terminal");

        UUID staleRun =
                store.startRun(
                        tenantId,
                        connectorId,
                        null,
                        sourceTaskId,
                        CrmMasterDataObjectType.ADDRESS,
                        100,
                        "SCHEDULED");
        jdbcTemplate.update(
                """
                UPDATE crm_sync_run SET updated_time=UTC_TIMESTAMP(6)-INTERVAL 3 HOUR
                 WHERE tenant_id=? AND id=?
                """,
                CrmUuidCodec.encode(tenantId),
                CrmUuidCodec.encode(staleRun));
        jdbcTemplate.update(
                """
                UPDATE crm_sync_lock SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND
                 WHERE tenant_id=? AND run_id=?
                """,
                CrmUuidCodec.encode(tenantId),
                CrmUuidCodec.encode(staleRun));

        UUID replacement =
                store.startRun(
                        tenantId,
                        connectorId,
                        null,
                        UUID.randomUUID(),
                        CrmMasterDataObjectType.ADDRESS,
                        100,
                        "SCHEDULED");

        assertThat(
                        jdbcTemplate.queryForMap(
                                """
                                SELECT status,error_code,finished_at FROM crm_sync_run
                                 WHERE tenant_id=? AND id=?
                                """,
                                CrmUuidCodec.encode(tenantId),
                                CrmUuidCodec.encode(staleRun)))
                .containsEntry("status", "FAILED")
                .containsEntry("error_code", "STALE_RUN_RECOVERED")
                .satisfies(row -> assertThat(row.get("finished_at")).isNotNull());
        store.failRun(
                tenantId,
                connectorId,
                replacement,
                new RunStatistics(0, 0, 0, 0, 0, 0, 0, 0),
                new IllegalStateException("test cleanup"));

        UUID ownedRun =
                store.startRun(
                        tenantId,
                        connectorId,
                        null,
                        sourceTaskId,
                        CrmMasterDataObjectType.ADDRESS,
                        100,
                        "SCHEDULED");
        jdbcTemplate.update(
                """
                UPDATE crm_sync_run SET updated_time=UTC_TIMESTAMP(6)-INTERVAL 3 HOUR
                 WHERE tenant_id=? AND id=?
                """,
                CrmUuidCodec.encode(tenantId),
                CrmUuidCodec.encode(ownedRun));

        assertThatThrownBy(
                        () ->
                                store.startRun(
                                        tenantId,
                                        connectorId,
                                        null,
                                        UUID.randomUUID(),
                                        CrmMasterDataObjectType.ADDRESS,
                                        100,
                                        "SCHEDULED"))
                .hasMessageContaining("已有同步任务运行");
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT status FROM crm_sync_run WHERE tenant_id=? AND id=?
                                """,
                                String.class,
                                CrmUuidCodec.encode(tenantId),
                                CrmUuidCodec.encode(ownedRun)))
                .isEqualTo("RUNNING");
        store.failRun(
                tenantId,
                connectorId,
                ownedRun,
                new RunStatistics(0, 0, 0, 0, 0, 0, 0, 0),
                new IllegalStateException("test cleanup"));
    }

    @Test
    void createsChangesSkipsAndRepairsCustomerProjection() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRecord initial = customerRecord("示例客户");

        UUID firstRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult created =
                store.importRecord(
                        tenantId, connectorId, firstRun, CrmMasterDataObjectType.CUSTOMER, initial);
        finish(tenantId, connectorId, firstRun, CrmMasterDataObjectType.CUSTOMER, created);
        byte[] partyId = customerTargetId(tenantId, connectorId);

        assertThat(created.created()).isEqualTo(1);
        assertThat(partyVersion(tenantId, partyId)).isZero();
        assertThat(queryStore.customers(tenantId, 0, 20, "C-001", null).items())
                .singleElement()
                .satisfies(
                        customer -> {
                            assertThat(customer.name()).isEqualTo("示例客户");
                            assertThat(customer.phone()).isEqualTo("13800000000");
                            assertThat(customer.sourceStatus()).isEqualTo("T");
                        });

        UUID duplicateRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult duplicate =
                store.importRecord(
                        tenantId,
                        connectorId,
                        duplicateRun,
                        CrmMasterDataObjectType.CUSTOMER,
                        initial);
        finish(tenantId, connectorId, duplicateRun, CrmMasterDataObjectType.CUSTOMER, duplicate);

        assertThat(duplicate.duplicates()).isEqualTo(1);
        assertThat(partyVersion(tenantId, partyId)).isZero();

        SourceRecord changedRecord = customerRecord("示例客户（已更新）");
        UUID changedRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult changed =
                store.importRecord(
                        tenantId,
                        connectorId,
                        changedRun,
                        CrmMasterDataObjectType.CUSTOMER,
                        changedRecord);
        finish(tenantId, connectorId, changedRun, CrmMasterDataObjectType.CUSTOMER, changed);

        assertThat(changed.changed()).isEqualTo(1);
        assertThat(partyVersion(tenantId, partyId)).isEqualTo(1);
        assertThat(queryStore.customer(tenantId, CrmUuidCodec.decode(partyId)).name())
                .isEqualTo("示例客户（已更新）");

        SourceRecord partial =
                new SourceRecord(
                        "CLIENT-GUID-1",
                        null,
                        null,
                        null,
                        null,
                        null,
                        mapOf("clientGUID", "CLIENT-GUID-1", "futureField", 43, "_employeeBySourceId", initial.sourceFields().get("_employeeBySourceId")));
        UUID partialRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult partialChanged =
                store.importRecord(
                        tenantId,
                        connectorId,
                        partialRun,
                        CrmMasterDataObjectType.CUSTOMER,
                        partial);
        finish(tenantId, connectorId, partialRun, CrmMasterDataObjectType.CUSTOMER, partialChanged);
        assertThat(queryStore.customer(tenantId, CrmUuidCodec.decode(partyId)))
                .satisfies(
                        customer -> {
                            assertThat(customer.name()).isEqualTo("示例客户（已更新）");
                            assertThat(customer.phone()).isEqualTo("13800000000");
                            assertThat(customer.sourceFields())
                                    .containsEntry("clientPhone", "13800000000")
                                    .containsEntry("futureField", 43);
                            assertThat(customer.source().clientGuid()).isEqualTo("CLIENT-GUID-1");
                            assertThat(customer.source().statusCode()).isEqualTo("T");
                        });

        jdbcTemplate.update(
                "DELETE FROM crm_customer_profile WHERE tenant_id=? AND party_id=?",
                CrmUuidCodec.encode(tenantId),
                partyId);
        UUID repairRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult repaired =
                store.importRecord(
                        tenantId,
                        connectorId,
                        repairRun,
                        CrmMasterDataObjectType.CUSTOMER,
                        partial);
        finish(tenantId, connectorId, repairRun, CrmMasterDataObjectType.CUSTOMER, repaired);

        assertThat(repaired.repaired()).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT COUNT(*) FROM crm_customer_profile WHERE tenant_id=? AND party_id=?
""",
                                Integer.class,
                                CrmUuidCodec.encode(tenantId),
                                partyId))
                .isEqualTo(1);
        assertThat(partyVersion(tenantId, partyId)).as("修复缺失投影不应重复更新未变更的客户主表").isEqualTo(2);
    }

    @Test
    void keepsPrimaryAndSecondarySalesAssignmentsAndSourceStaffIds() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        UUID runId = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult result =
                store.importRecord(
                        tenantId,
                        connectorId,
                        runId,
                        CrmMasterDataObjectType.CUSTOMER,
                        customerRecordWithAssignments());
        finish(tenantId, connectorId, runId, CrmMasterDataObjectType.CUSTOMER, result);
        byte[] partyId = customerTargetId(tenantId, connectorId);

        assertThat(
                        jdbcTemplate.queryForList(
                                """
SELECT assignment_type,source_staff_id,employee_code,employee_name_snapshot,source_name_snapshot
  FROM crm_sales_assignment
 WHERE tenant_id=? AND party_id=? AND status='ACTIVE'
 ORDER BY assignment_type,source_staff_id
""",
                                CrmUuidCodec.encode(tenantId),
                                partyId))
                .extracting(
                        row -> row.get("assignment_type"),
                        row -> row.get("source_staff_id"),
                        row -> row.get("employee_code"),
                        row -> row.get("employee_name_snapshot"),
                        row -> row.get("source_name_snapshot"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "PRIMARY", "STAFF-PRIMARY", "RY202608220001", "张三", "张三"),
                        org.assertj.core.groups.Tuple.tuple(
                                "SECONDARY", "STAFF-SECONDARY", "RY202608220002", "李四", "李四"));
        assertThat(queryStore.customer(tenantId, CrmUuidCodec.decode(partyId)).salesAssignments())
                .extracting(
                        assignment ->
                                assignment.assignmentType()
                                        + ":"
                                        + assignment.employeeCode()
                                        + ":"
                                        + assignment.employeeName())
                .containsExactly("PRIMARY:RY202608220001:张三", "SECONDARY:RY202608220002:李四");
    }

    @Test
    void syncExternalStoreRowCreatesCustomerTypeProfileAndShippingAddress() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant sourceCreatedAt = Instant.parse("2026-10-28T02:00:00Z");
        ExternalCrmCustomerRowCommand row =
                new ExternalCrmCustomerRowCommand(
                        null,
                        "FEISHU_STORE",
                        "SP1909",
                        "SP1909",
                        "BF台球俱乐部",
                        "樊明亚",
                        "17608432425",
                        "🏪门店信息库",
                        "商业球房",
                        null,
                        "长沙",
                        "芙蓉区万家丽中路一段3号建安新商汇",
                        null,
                        "樊明亚",
                        null,
                        "营业",
                        sourceCreatedAt,
                        sourceCreatedAt,
                        "hash-feishu-store-1",
                        "{\"门店属性\":\"商业球房\"}");

        var created =
                internalCustomerStore.syncExternalCustomers(
                        tenantId.toString(),
                        "FEISHU",
                        List.of(row),
                        actorId.toString(),
                        new BusinessCodeGenerator());

        assertThat(created.created()).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT customer_code FROM crm_customer
 WHERE tenant_id=? AND source_system_code='FEISHU'
   AND source_tenant_key='FEISHU_STORE' AND source_customer_id='SP1909'
""",
                                String.class,
                                tenantId.toString()))
                .startsWith("CUS20261028");
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT COUNT(*) FROM crm_customer_type
 WHERE tenant_id=? AND type_name='商业球房' AND record_origin='FEISHU' AND deleted=0
""",
                                Integer.class,
                                CrmUuidCodec.encode(tenantId)))
                .isEqualTo(1);
        byte[] partyId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT party_id FROM crm_customer
                         WHERE tenant_id=? AND source_system_code='FEISHU'
                           AND source_tenant_key='FEISHU_STORE' AND source_customer_id='SP1909'
                        """,
                        byte[].class,
                        tenantId.toString());
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT COUNT(*)
  FROM crm_customer_profile cp
  JOIN crm_customer_type ct ON ct.tenant_id=cp.tenant_id AND ct.id=cp.customer_type_id
 WHERE cp.tenant_id=? AND cp.party_id=? AND ct.type_name='商业球房'
   AND cp.city_text='长沙' AND cp.deleted=0
""",
                                Integer.class,
                                CrmUuidCodec.encode(tenantId),
                                partyId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT COUNT(*)
  FROM crm_address a
  JOIN crm_contact c ON c.tenant_id=a.tenant_id AND c.id=a.contact_id
 WHERE a.tenant_id=? AND a.party_id=? AND a.address_type='SHIPPING'
   AND a.record_origin='FEISHU' AND a.full_address='芙蓉区万家丽中路一段3号建安新商汇'
   AND c.phone='17608432425' AND a.deleted=0
""",
                                Integer.class,
                                CrmUuidCodec.encode(tenantId),
                                partyId))
                .isEqualTo(1);

        var unchanged =
                internalCustomerStore.syncExternalCustomers(
                        tenantId.toString(),
                        "FEISHU",
                        List.of(row),
                        actorId.toString(),
                        new BusinessCodeGenerator());

        assertThat(unchanged.unchanged()).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT COUNT(*) FROM crm_address
 WHERE tenant_id=? AND party_id=? AND address_type='SHIPPING' AND record_origin='FEISHU'
""",
                                Integer.class,
                                CrmUuidCodec.encode(tenantId),
                                partyId))
                .isEqualTo(1);
    }

    @Test
    void repairsInternalCustomerTypeAndRegionWhenSourcePayloadUnchanged() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        SourceRecord type =
                new SourceRecord(
                        "TYPE-1", "TYPE-1", "VIP", "T", null, null, mapOf("typeName", "VIP"));
        UUID typeRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER_TYPE);
        ImportResult typeResult =
                store.importRecord(
                        tenantId,
                        connectorId,
                        typeRun,
                        CrmMasterDataObjectType.CUSTOMER_TYPE,
                        type);
        finish(tenantId, connectorId, typeRun, CrmMasterDataObjectType.CUSTOMER_TYPE, typeResult);

        SourceRecord area =
                new SourceRecord(
                        "AREA-1", "AREA-1", "华东", "T", null, null, mapOf("AreaName", "华东"));
        UUID areaRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER_AREA);
        ImportResult areaResult =
                store.importRecord(
                        tenantId,
                        connectorId,
                        areaRun,
                        CrmMasterDataObjectType.CUSTOMER_AREA,
                        area);
        finish(tenantId, connectorId, areaRun, CrmMasterDataObjectType.CUSTOMER_AREA, areaResult);

        SourceRecord customer = customerRecordWithTypeArea("TYPE-1", "AREA-1");
        UUID firstRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult created =
                store.importRecord(
                        tenantId,
                        connectorId,
                        firstRun,
                        CrmMasterDataObjectType.CUSTOMER,
                        customer);
        finish(tenantId, connectorId, firstRun, CrmMasterDataObjectType.CUSTOMER, created);
        byte[] partyId = customerTargetId(tenantId, connectorId);

        Map<String, Object> createdRow = internalCustomerClassification(tenantId, partyId);
        assertThat(createdRow.get("customer_type_code")).isNotNull();
        assertThat(createdRow.get("region_code")).isNotNull();

        jdbcTemplate.update(
                """
                UPDATE crm_customer
                   SET customer_type_code=NULL, region_code=NULL
                 WHERE tenant_id=? AND party_id=?
                """,
                tenantId.toString(),
                partyId);

        UUID repairRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult repaired =
                store.importRecord(
                        tenantId,
                        connectorId,
                        repairRun,
                        CrmMasterDataObjectType.CUSTOMER,
                        customer);
        finish(tenantId, connectorId, repairRun, CrmMasterDataObjectType.CUSTOMER, repaired);

        assertThat(repaired.repaired()).isEqualTo(1);
        assertThat(internalCustomerClassification(tenantId, partyId))
                .containsEntry("customer_type_code", createdRow.get("customer_type_code"))
                .containsEntry("region_code", createdRow.get("region_code"));
    }

    @Test
    void keepsUnresolvedAddressSourceFieldsAndConfirmsAbsenceOnlyTwice() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        SourceRecord address =
                new SourceRecord(
                        "ADDR-GUID-1",
                        "ADDR-1",
                        "上海仓",
                        "T",
                        null,
                        Instant.parse("2026-08-01T00:30:00Z"),
                        mapOf(
                                "addressId",
                                "ADDR-1",
                                "addressGuid",
                                "ADDR-GUID-1",
                                "clientGuid",
                                "UNKNOWN-CUSTOMER",
                                "clientNum",
                                "C-404",
                                "consignee",
                                "上海仓",
                                "contact",
                                "张三",
                                "phone",
                                "13800000000",
                                "address",
                                "上海市浦东新区",
                                "addressDetail",
                                "世纪大道1号",
                                "isDefault",
                                "T",
                                "futureField",
                                Map.of("provider", "kept")));

        UUID firstRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.ADDRESS);
        ImportResult created =
                store.importRecord(
                        tenantId, connectorId, firstRun, CrmMasterDataObjectType.ADDRESS, address);
        finish(tenantId, connectorId, firstRun, CrmMasterDataObjectType.ADDRESS, created);

        Map<String, Object> binding =
                jdbcTemplate.queryForMap(
                        """
SELECT binding_status, resolution_error_code,
       JSON_UNQUOTE(JSON_EXTRACT(source_fields_json, '$.futureField.provider')) AS future_value
  FROM crm_source_binding
 WHERE tenant_id=? AND connector_id=? AND source_object_type='ADDRESS'
   AND source_object_id='ADDR-GUID-1'
""",
                        CrmUuidCodec.encode(tenantId),
                        CrmUuidCodec.encode(connectorId));
        assertThat(binding)
                .containsEntry("binding_status", "UNRESOLVED")
                .containsEntry("resolution_error_code", "CUSTOMER_NOT_RESOLVED")
                .containsEntry("future_value", "kept");

        UUID absentRun1 = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.ADDRESS);
        finish(
                tenantId,
                connectorId,
                absentRun1,
                CrmMasterDataObjectType.ADDRESS,
                new ImportResult(0, 0, 0, 0, 0, 0));
        assertThat(sourcePresence(tenantId, connectorId)).isEqualTo("ABSENT_CANDIDATE");

        UUID absentRun2 = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.ADDRESS);
        finish(
                tenantId,
                connectorId,
                absentRun2,
                CrmMasterDataObjectType.ADDRESS,
                new ImportResult(0, 0, 0, 0, 0, 0));
        assertThat(sourcePresence(tenantId, connectorId)).isEqualTo("ABSENT");
    }

    @Test
    void batchImportsDuplicateAddressWithPrefetchedProjectionSnapshot() {
        UUID tenantId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        UUID customerRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER);
        ImportResult customer =
                store.importRecord(
                        tenantId,
                        connectorId,
                        customerRun,
                        CrmMasterDataObjectType.CUSTOMER,
                        customerRecord("地址所属客户"));
        finish(tenantId, connectorId, customerRun, CrmMasterDataObjectType.CUSTOMER, customer);

        SourceRecord address = addressRecord("ADDR-GUID-2", "ADDR-2");
        UUID firstRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.ADDRESS);
        ImportResult created =
                store.importRecords(
                                tenantId,
                                connectorId,
                                firstRun,
                                CrmMasterDataObjectType.ADDRESS,
                                List.of(address))
                        .get(0);
        finish(tenantId, connectorId, firstRun, CrmMasterDataObjectType.ADDRESS, created);
        byte[] addressId =
                jdbcTemplate.queryForObject(
                        """
                        SELECT target_id FROM crm_source_binding
                         WHERE tenant_id=? AND connector_id=? AND source_object_type='ADDRESS'
                           AND source_object_id='ADDR-GUID-2'
                        """,
                        byte[].class,
                        CrmUuidCodec.encode(tenantId),
                        CrmUuidCodec.encode(connectorId));

        UUID duplicateRun = start(tenantId, connectorId, actorId, CrmMasterDataObjectType.ADDRESS);
        ImportResult duplicate =
                store.importRecords(
                                tenantId,
                                connectorId,
                                duplicateRun,
                                CrmMasterDataObjectType.ADDRESS,
                                List.of(address))
                        .get(0);
        finish(tenantId, connectorId, duplicateRun, CrmMasterDataObjectType.ADDRESS, duplicate);

        assertThat(created.created()).isEqualTo(1);
        assertThat(duplicate.duplicates()).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT revision FROM crm_address WHERE tenant_id=? AND id=?
                                """,
                                Long.class,
                                CrmUuidCodec.encode(tenantId),
                                addressId))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT COUNT(*) FROM crm_contact c
  JOIN crm_address a ON a.tenant_id=c.tenant_id AND a.contact_id=c.id
 WHERE a.tenant_id=? AND a.id=?
""",
                                Integer.class,
                                CrmUuidCodec.encode(tenantId),
                                addressId))
                .isEqualTo(1);
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.rigour.tenant.iam.client.SupplyAuthorizationClient authorizations;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.rigour.merchant.application.port.out.CrmEmployeeClient employeeClient;

    @Test
    void currentCustomerScopeUsesPrimaryOwnerAndRegionBeforePaging() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        String tenantKey = tenant.toString();
        area(tenant, "HZ", null);
        area(tenant, "HZ_CHILD", "HZ");
        area(tenant, "NB", null);
        org.mockito.Mockito.when(
                        employeeClient.owner(
                                org.mockito.ArgumentMatchers.eq(tenantKey),
                                org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(
                        invocation ->
                                new com.rigour.merchant.application.port.out.CrmEmployeeClient
                                        .Owner(invocation.getArgument(1), "HR员工", true, null, 1));
        org.mockito.Mockito.when(assignmentTargets.byEmployee(org.mockito.ArgumentMatchers.eq(tenantKey),org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> new com.rigour.tenant.iam.api.v1.model.CustomerAssignmentTargetView(
                    tenant,null,invocation.getArgument(1),"HR员工",null,true,null,0,
                    new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit("ALL",List.of())));
        internalCustomerStore.create(
                tenantKey, "C-A", customer("杭州一", "HZ", "EMP-A", 0), user.toString());
        internalCustomerStore.create(
                tenantKey, "C-B", customer("杭州二", "HZ_CHILD", "EMP-B", 0), user.toString());
        internalCustomerStore.create(
                tenantKey, "C-C", customer("杭州待分配", "HZ", null, 0), user.toString());
        var hidden =
                internalCustomerStore.create(
                        tenantKey, "C-D", customer("宁波一", "NB", "EMP-A", 0), user.toString());
        java.util.Map<Long, UUID> parties = new java.util.HashMap<>();
        for (Long id :
                jdbcTemplate.queryForList(
                        "SELECT id FROM crm_customer WHERE tenant_id=?", Long.class, tenantKey))
            parties.put(id, linkDirectory(tenant, id));
        var caller =
                new com.rigour.shared.context.CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        java.util.Set.of("TENANT_SUPER_ADMIN"),
                        java.util.Set.of("*:*:*"));
        org.mockito.Mockito.when(authorizations.authorization(caller, "crm:customer:read"))
                .thenReturn(customerPolicy(caller, "SELF"));
        var criteria =
                new com.rigour.merchant.application.port.out.CrmInternalCustomerStore
                        .CustomerSearchCriteria(null, null, null, null, null, null, null, null);
        com.rigour.shared.context.TestAuthorizationContext.set(caller);
        try {
            assertThat(internalCustomerStore.customers(tenantKey, 0, 1, criteria).total())
                    .isEqualTo(1);
            assertThat(internalCustomerStore.customer(tenantKey, hidden.id())).isEmpty();
            assertThat(queryStore.customers(tenant, 0, 1, null, null).total()).isEqualTo(1);
            var displayed=queryStore.customers(tenant,0,1,null,null).items().getFirst();
            assertThat(displayed.areaName()).isEqualTo("HZ");
            assertThat(displayed.salesAssignments()).filteredOn(a->"PRIMARY".equals(a.assignmentType())).extracting(com.rigour.merchant.api.v1.model.SalesAssignmentView::employeeCode).containsExactly("EMP-A");
            assertThat(queryStore.customer(tenant,displayed.id()).salesAssignments()).filteredOn(a->"PRIMARY".equals(a.assignmentType())).extracting(com.rigour.merchant.api.v1.model.SalesAssignmentView::employeeCode).containsExactly("EMP-A");
            assertThat(queryStore.shippingAddresses(tenant, 0, 1, null).total()).isEqualTo(1);
            assertThatThrownBy(() -> queryStore.customer(tenant, parties.get(hidden.id())))
                    .hasMessageContaining("不存在");
            org.mockito.Mockito.when(authorizations.authorization(caller, "crm:customer:read"))
                    .thenReturn(customerPolicy(caller, "REGION"));
            var city = internalCustomerStore.customers(tenantKey, 0, 1, criteria);
            assertThat(city.total()).isEqualTo(3);
            assertThat(city.items()).hasSize(1);
            assertThat(queryStore.customers(tenant, 0, 1, null, null).total()).isEqualTo(3);
            assertThat(queryStore.shippingAddresses(tenant, 0, 1, null).total()).isEqualTo(3);
        } finally {
            com.rigour.shared.context.TestAuthorizationContext.clear();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.rigour.merchant.application.port.out.CustomerResponsibilityStore responsibilityStore;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.rigour.merchant.application.port.out.CustomerAssignmentTargetClient assignmentTargets;

    @Test
    void locallyTransferredOwnerSurvivesExternalSyncAndRecordsConflict() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        String tenantKey = tenant.toString();
        Instant time = Instant.parse("2026-08-01T00:00:00Z");
        var source =
                new ExternalCrmCustomerRowCommand(
                        null,
                        "TEST_SOURCE",
                        "SOURCE-1",
                        "SOURCE-1",
                        "历史客户",
                        null,
                        null,
                        "测试来源",
                        null,
                        null,
                        "杭州",
                        null,
                        "EMP-OLD",
                        "旧员工",
                        null,
                        "营业",
                        time,
                        time,
                        "source-hash-1",
                        "{}");
        var sync =
                internalCustomerStore.syncExternalCustomers(
                        tenantKey,
                        "FEISHU",
                        List.of(source),
                        user.toString(),
                        new BusinessCodeGenerator());
        assertThat(sync.created()).isEqualTo(1);
        long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM crm_customer WHERE tenant_id=?", Long.class, tenantKey);
        var old = internalCustomerStore.customer(tenantKey, id).orElseThrow();
        org.mockito.Mockito.when(employeeClient.owner(tenantKey, "EMP-NEW"))
                .thenReturn(
                        new com.rigour.merchant.application.port.out.CrmEmployeeClient.Owner(
                                "EMP-NEW", "HR真实姓名", true, null, 1));
        org.mockito.Mockito.when(assignmentTargets.byEmployee(tenantKey,"EMP-NEW"))
                .thenReturn(new com.rigour.tenant.iam.api.v1.model.CustomerAssignmentTargetView(
                    tenant,null,"EMP-NEW","HR真实姓名",null,true,null,0,
                    new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit("ALL",List.of())));
        var changed =
                responsibilityStore.transfer(
                        tenantKey,
                        id,
                        new com.rigour.merchant.api.v1.CustomerResponsibilityApi.Change(
                                "EMP-NEW", old.regionCode(), old.revision(), "城市业务移交"),
                        user.toString());
        assertThat(changed.employeeName()).isEqualTo("HR真实姓名");
        internalCustomerStore.syncExternalCustomers(
                tenantKey, "FEISHU", List.of(source), user.toString(), new BusinessCodeGenerator());
        assertThat(internalCustomerStore.customer(tenantKey, id).orElseThrow().ownerEmployeeCode())
                .isEqualTo("EMP-NEW");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM crm_customer_responsibility_history WHERE"
                                    + " tenant_id=? AND customer_id=?",
                                Integer.class,
                                tenantKey,
                                id))
                .isEqualTo(2);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM crm_responsibility_source_conflict WHERE"
                                    + " tenant_id=? AND customer_id=? AND status='PENDING'",
                                Integer.class,
                                tenantKey,
                                id))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                responsibilityStore.transfer(
                                        tenantKey,
                                        id,
                                        new com.rigour.merchant.api.v1.CustomerResponsibilityApi
                                                .Change(
                                                "EMP-NEW",
                                                old.regionCode(),
                                                old.revision(),
                                                "过期移交"),
                                        user.toString()))
                .hasMessageContaining("修改");
        var overview = responsibilityStore.overview(tenantKey, id);
        long pending = overview.conflicts().getFirst().id();
        var kept =
                responsibilityStore.resolve(
                        tenantKey,
                        id,
                        pending,
                        new com.rigour.merchant.api.v1.CustomerResponsibilityApi.Resolution(
                                "KEEP_LOCAL", overview.revision(), "保留本次人工移交"),
                        user.toString());
        assertThat(kept.pendingCount()).isZero();
        internalCustomerStore.syncExternalCustomers(
                tenantKey, "FEISHU", List.of(source), user.toString(), new BusinessCodeGenerator());
        assertThat(responsibilityStore.overview(tenantKey, id).pendingCount()).isZero();
        var current = internalCustomerStore.customer(tenantKey, id).orElseThrow();
        assertThatThrownBy(
                        () ->
                                internalCustomerStore.update(
                                        tenantKey,
                                        id,
                                        customer(
                                                current.customerName(),
                                                current.regionCode(),
                                                "EMP-OTHER",
                                                current.revision()),
                                        user.toString()))
                .hasMessageContaining("客户归属管理");
    }

    private UUID linkDirectory(UUID tenant, long customer) {
        UUID party = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO"
                    + " crm_party(id,tenant_id,party_code,display_name,created_time,updated_time)"
                    + " VALUES(?,?,?,'目录客户',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                CrmUuidCodec.encode(party),
                CrmUuidCodec.encode(tenant),
                "DIR-" + customer);
        jdbcTemplate.update(
                "INSERT INTO crm_customer_profile(party_id,tenant_id,created_time,updated_time)"
                    + " VALUES(?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                CrmUuidCodec.encode(party),
                CrmUuidCodec.encode(tenant));
        jdbcTemplate.update(
                "INSERT INTO"
                    + " crm_address(id,tenant_id,party_id,address_type,created_time,updated_time)"
                    + " VALUES(?,?,?,'SHIPPING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                CrmUuidCodec.encode(UUID.randomUUID()),
                CrmUuidCodec.encode(tenant),
                CrmUuidCodec.encode(party));
        jdbcTemplate.update(
                "UPDATE crm_customer SET party_id=? WHERE tenant_id=? AND id=?",
                CrmUuidCodec.encode(party),
                tenant.toString(),
                customer);
        return party;
    }

    private static com.rigour.merchant.api.v1.model.InternalCustomerCommand customer(
            String name, String region, String employee, int revision) {
        return new com.rigour.merchant.api.v1.model.InternalCustomerCommand(
                name,
                null,
                null,
                null,
                region,
                null,
                null,
                employee,
                "客户端不可信姓名",
                null,
                null,
                "ACTIVE",
                null,
                revision);
    }

    private void area(UUID tenant, String code, String parent) {
        jdbcTemplate.update(
                "INSERT INTO"
                    + " crm_customer_area(id,tenant_id,area_code,area_name,parent_area_code,status,created_time,updated_time)"
                    + " VALUES(?,?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                CrmUuidCodec.encode(UUID.randomUUID()),
                CrmUuidCodec.encode(tenant),
                code,
                code,
                parent);
    }

    private static com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView customerPolicy(
            com.rigour.shared.context.CallerIdentity c, String mode) {
        var none =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit(
                        "NONE", List.of());
        var region =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit(
                        "SPECIFIED", List.of("HZ"));
        var clause =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Clause(
                        UUID.randomUUID(), "CUSTOMER", mode, none, region, none, true);
        return new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView(
                "ACTIVE",
                c.tenantId(),
                c.userId(),
                "EMP-A",
                1,
                1,
                1,
                1,
                java.util.Set.of("crm:customer:read"),
                "crm:customer:read",
                true,
                List.of(clause),
                region,
                none);
    }

    @Test
    void businessCreationPreservesFeishuFactsAcrossDhbUpdatesAndCursorNeverRewinds() {
        UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID(), actor = UUID.randomUUID();
        var type = CrmMasterDataObjectType.CUSTOMER;
        UUID run = start(tenant, connector, actor, type);
        var original = customerRecord("历史客户");
        var imported = store.importRecord(tenant, connector, run, type, original);
        finish(tenant, connector, run, type, imported);
        var row = jdbcTemplate.queryForMap("SELECT business_created_at, created_time, business_created_by_name FROM crm_customer WHERE tenant_id=?", tenant.toString());
        assertThat(row.get("business_created_at").toString()).startsWith("2026-07-01");
        assertThat(row.get("business_created_by_name")).isNull(); // Inviter/业务员都不是创建人
        jdbcTemplate.update("UPDATE crm_customer SET business_created_at='2026-06-01 01:02:03', business_created_by_id='ou-original', business_created_by_name='原创建人', business_creation_source='FEISHU' WHERE tenant_id=?", tenant.toString());
        run = start(tenant, connector, actor, type);
        imported = store.importRecord(tenant, connector, run, type, customerRecord("历史客户改名"));
        var watermark = Instant.parse("2026-09-15T16:00:00Z");
        store.completeCustomerWindow(tenant, connector, run, new RunStatistics(1,0,1,0,0,0,0,1), watermark);
        row = jdbcTemplate.queryForMap("SELECT business_created_at, business_created_by_name, business_creation_source FROM crm_customer WHERE tenant_id=?", tenant.toString());
        assertThat(row.get("business_created_at").toString()).startsWith("2026-06-01T01:02:03");
        assertThat(row.get("business_created_by_name")).isEqualTo("原创建人");
        assertThat(row.get("business_creation_source")).isEqualTo("FEISHU");
        assertThat(store.customerSyncCursor(tenant, connector)).isEqualTo(watermark);
        jdbcTemplate.update("UPDATE crm_customer SET source_system_code='FEISHU', source_tenant_key='FEISHU_STORE', source_customer_id='SP-ORIGINAL' WHERE tenant_id=?", tenant.toString());
        var replay = new ExternalCrmCustomerRowCommand(null, "FEISHU_STORE", "SP-ORIGINAL", "SP-ORIGINAL",
                "过期的订单门店名称", null, null, null, null, null, null, null, null, "旧业务员", null, "营业",
                Instant.parse("2026-08-25T00:00:00Z"), Instant.parse("2026-08-25T00:00:00Z"), "old-order-hash", "{}");
        var replayed = internalCustomerStore.syncExternalCustomers(tenant.toString(), "FEISHU", List.of(replay), actor.toString(), new BusinessCodeGenerator());
        assertThat(replayed.unchanged()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT customer_name FROM crm_customer WHERE tenant_id=?", String.class, tenant.toString())).isEqualTo("历史客户改名");
        run = start(tenant, connector, actor, type);
        store.completeCustomerWindow(tenant, connector, run, new RunStatistics(0,0,0,0,0,0,0,1), watermark.minusSeconds(60));
        assertThat(store.customerSyncCursor(tenant, connector)).isEqualTo(watermark);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=?", Integer.class, tenant.toString())).isEqualTo(1);
    }

    @Test
    void mergedCustomerAliasCannotOverwriteChosenOwnerOrOriginalCreation() {
        UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID(), actor = UUID.randomUUID();
        var type = CrmMasterDataObjectType.CUSTOMER;
        UUID run = start(tenant, connector, actor, type);
        var primaryRecord = customerRecord("已确认主客户");
        finish(tenant, connector, run, type, store.importRecord(tenant, connector, run, type, primaryRecord));
        byte[] party = customerTargetId(tenant, connector);
        jdbcTemplate.update("UPDATE crm_customer SET owner_employee_code='EMP-CHOSEN', owner_employee_name_snapshot='曹健', business_created_at='2026-06-01 01:02:03', business_created_by_name='飞书创建人', business_creation_source='FEISHU' WHERE tenant_id=?", tenant.toString());
        jdbcTemplate.update("INSERT INTO crm_source_binding(id,tenant_id,connector_id,source_system,source_object_type,source_object_id,target_type,target_id,binding_status,primary_customer_source_id,source_fields_json,source_payload_hash,synced_at,created_time,updated_time) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'DINGHUOBAO','CUSTOMER','SECONDARY','PARTY',?,'UNRESOLVED','CLIENT-GUID-1',JSON_OBJECT(),REPEAT('0',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", UUID.randomUUID().toString(), tenant.toString(), connector.toString(), party);
        Map<String,Object> fields = new LinkedHashMap<>(primaryRecord.sourceFields());
        fields.put("clientGUID", "SECONDARY");
        fields.put("clientNO", "C-SECONDARY");
        fields.put("clientCompanyName", "不能覆盖主客户名称");
        fields.put("staffID", "OTHER-STAFF"); // Deliberately unmapped: secondary ownership is not authoritative.
        fields.put("staffName", "李苏阳");
        var secondary = new SourceRecord("SECONDARY", "C-SECONDARY", "不能覆盖主客户名称", "T",
                Instant.parse("2026-08-25T00:00:00Z"), Instant.parse("2026-09-16T00:00:00Z"), fields);
        run = start(tenant, connector, actor, type);
        assertThat(store.importRecord(tenant, connector, run, type, secondary).repaired()).isEqualTo(1);
        assertThat(store.importRecord(tenant, connector, run, type, secondary).duplicates()).isEqualTo(1);
        var row = jdbcTemplate.queryForMap("SELECT customer_name, owner_employee_code, business_created_at, business_created_by_name FROM crm_customer WHERE tenant_id=?", tenant.toString());
        assertThat(row.get("customer_name")).isEqualTo("已确认主客户");
        assertThat(row.get("owner_employee_code")).isEqualTo("EMP-CHOSEN");
        assertThat(row.get("business_created_at").toString()).startsWith("2026-06-01T01:02:03");
        assertThat(row.get("business_created_by_name")).isEqualTo("飞书创建人");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=?", Integer.class, tenant.toString())).isEqualTo(1);
        long customerId = jdbcTemplate.queryForObject("SELECT id FROM crm_customer WHERE tenant_id=?", Long.class, tenant.toString());
        jdbcTemplate.update("INSERT INTO crm_source_binding(id,tenant_id,connector_id,source_system,source_object_type,source_object_id,target_type,target_id,binding_status,primary_customer_source_id,source_fields_json,source_payload_hash,synced_at,created_time,updated_time) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'DINGHUOBAO','CUSTOMER','THIRD','PARTY',?,'UNRESOLVED','CLIENT-GUID-1',JSON_OBJECT(),REPEAT('0',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", UUID.randomUUID().toString(), tenant.toString(), connector.toString(), party);
        var thirdFields = new LinkedHashMap<String,Object>(fields);
        thirdFields.put("clientGUID", "THIRD"); thirdFields.put("clientNO", "C-THIRD");
        var third = new SourceRecord("THIRD", "C-THIRD", "已确认主客户", "T", secondary.sourceCreatedAt(), secondary.sourceUpdatedAt(), thirdFields);
        assertThat(store.importRecord(tenant, connector, run, type, third).repaired()).isEqualTo(1);
        assertThat(internalCustomerStore.customer(tenant.toString(), customerId).orElseThrow().dhbCustomerCodes())
                .containsExactly("C-001", "C-SECONDARY", "C-THIRD");
        var aliasFilter = new CrmInternalCustomerStore.CustomerSearchCriteria(null,null,null,null,null,null,null,null,null,null,null,null,null,null,"C-THIRD","LINKED");
        var matches = internalCustomerStore.customers(tenant.toString(),0,1,aliasFilter);
        assertThat(matches.total()).isEqualTo(1);
        assertThat(matches.items().getFirst().id()).isEqualTo(customerId);
        assertThat(matches.items().getFirst().dhbCustomerCodes()).containsExactly("C-001", "C-SECONDARY", "C-THIRD");
        assertThat(internalCustomerStore.customers(UUID.randomUUID().toString(),0,10,aliasFilter).total()).isZero();
        var mappings = store.externalObjectMappings(tenant, connector, run, type);
        assertThat(mappings).hasSize(3);
        assertThat(mappings).extracting(m -> m.sourceObjectNo()).containsExactlyInAnyOrder("C-001", "C-SECONDARY", "C-THIRD");
        assertThat(mappings).allSatisfy(m -> assertThat(m.internalObjectId()).isEqualTo(customerId));
        jdbcTemplate.update("UPDATE crm_source_binding SET primary_customer_source_id='MISSING' WHERE tenant_id=UUID_TO_BIN(?) AND source_object_id='SECONDARY'", tenant.toString());
        assertThat(store.importRecord(tenant, connector, run, type, secondary).unmapped()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT target_id FROM crm_source_binding WHERE tenant_id=UUID_TO_BIN(?) AND source_object_id='SECONDARY'", byte[].class, tenant.toString())).isEqualTo(party);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=?", Integer.class, tenant.toString())).isEqualTo(1);
        store.completeRun(tenant, connector, run, type, new RunStatistics(3,0,0,1,1,0,0,1), false);
    }

    @Test
    void customerSyncRequiresLocalRegionAndEmployeeBeforeAnyProjectionOrDuplicateShortcut() {
        UUID tenant = UUID.randomUUID(), connector = UUID.randomUUID(), actor = UUID.randomUUID();
        var type = CrmMasterDataObjectType.CUSTOMER;
        UUID run = start(tenant, connector, actor, type);
        var good = customerRecord("本系统关联客户");
        var fields = new LinkedHashMap<String,Object>(good.sourceFields());
        fields.put("_employeeBySourceId", Map.of());
        var unresolvedEmployee = new SourceRecord(good.sourceId(), good.sourceCode(), good.sourceName(), good.sourceStatus(), good.sourceCreatedAt(), good.sourceUpdatedAt(), fields);
        assertThat(store.importRecord(tenant, connector, run, type, unresolvedEmployee).unmapped()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=?", Integer.class, tenant.toString())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_party WHERE tenant_id=UUID_TO_BIN(?)", Integer.class, tenant.toString())).isZero();
        assertThat(store.importRecord(tenant, connector, run, type, good).created()).isEqualTo(1);
        var before = jdbcTemplate.queryForMap("SELECT * FROM crm_customer WHERE tenant_id=?", tenant.toString());
        assertThat(before.get("owner_employee_code")).isEqualTo("EMP-PRIMARY");
        assertThat(before.get("owner_employee_name_snapshot")).isEqualTo("本系统员工");
        // Same source payload, but HR no longer resolves it: cannot take the duplicate fast path.
        assertThat(store.importRecord(tenant, connector, run, type, unresolvedEmployee).unmapped()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM crm_customer WHERE tenant_id=?", tenant.toString())).usingRecursiveComparison().isEqualTo(before);
        for (String areaId : List.of("UNKNOWN-AREA", "")) {
            fields = new LinkedHashMap<>(good.sourceFields()); fields.put("clientArea", areaId);
            fields.put("clientAreaName", "订货宝地区名称不能直接写入");
            var badArea = new SourceRecord(good.sourceId(), good.sourceCode(), good.sourceName(), good.sourceStatus(), good.sourceCreatedAt(), good.sourceUpdatedAt(), fields);
            assertThat(store.importRecord(tenant, connector, run, type, badArea).unmapped()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForMap("SELECT * FROM crm_customer WHERE tenant_id=?", tenant.toString())).usingRecursiveComparison().isEqualTo(before);
            assertThat(jdbcTemplate.queryForObject("SELECT resolution_error_code FROM crm_source_binding WHERE tenant_id=UUID_TO_BIN(?) AND source_object_type='CUSTOMER'", String.class, tenant.toString())).isEqualTo("CUSTOMER_AREA_MAPPING_REQUIRED");
        }
        jdbcTemplate.update("UPDATE crm_customer_area SET deleted=1 WHERE tenant_id=UUID_TO_BIN(?)", tenant.toString());
        assertThat(store.importRecord(tenant, connector, run, type, good).unmapped()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM crm_customer WHERE tenant_id=?", tenant.toString())).usingRecursiveComparison().isEqualTo(before);
        store.completeRun(tenant, connector, run, type, new RunStatistics(6,0,0,1,0,0,0,1), false);
    }

    @Test
    void customerProfileSyncExposesLoginCodeAndActorWithoutChangingCreation() {
        UUID tenant=UUID.randomUUID(), connector=UUID.randomUUID(), actor=UUID.randomUUID();
        var type=CrmMasterDataObjectType.CUSTOMER;UUID run=start(tenant,connector,actor,type);
        var record=customerRecord("账号同步客户");
        finish(tenant,connector,run,type,store.importRecord(tenant,connector,run,type,record));
        var row=jdbcTemplate.queryForMap("SELECT * FROM crm_customer WHERE tenant_id=?",tenant.toString());
        assertThat(row.get("login_account")).isEqualTo("customer001");
        assertThat(row.get("dhb_customer_code")).isEqualTo("C-001");
        assertThat(row.get("settlement_type_code")).isEqualTo("prepaid");
        assertThat(row.get("synced_by")).isEqualTo(actor.toString());
        Object creation=row.get("business_created_at"), modified=row.get("updated_time");
        UUID nextActor=UUID.randomUUID();run=start(tenant,connector,nextActor,type);
        var result=store.importRecord(tenant,connector,run,type,record);
        assertThat(result.duplicates()).isEqualTo(1);
        finish(tenant,connector,run,type,result);
        var next=jdbcTemplate.queryForMap("SELECT * FROM crm_customer WHERE tenant_id=?",tenant.toString());
        assertThat(next.get("synced_by")).isEqualTo(nextActor.toString());
        assertThat(next.get("business_created_at")).isEqualTo(creation);
        assertThat(next.get("updated_time")).isEqualTo(modified);
        var view=internalCustomerStore.customer(tenant.toString(),((Number)row.get("id")).longValue()).orElseThrow();
        assertThat(view.loginAccount()).isEqualTo("customer001");assertThat(view.dhbCustomerCode()).isEqualTo("C-001");assertThat(view.syncedAt()).isNotNull();
    }

    @Test
    void externalAddressSyncKeepsLocallySelectedDefault() {
        UUID tenant=UUID.randomUUID(), connector=UUID.randomUUID(), actor=UUID.randomUUID();
        var type=CrmMasterDataObjectType.CUSTOMER;UUID run=start(tenant,connector,actor,type);
        finish(tenant,connector,run,type,store.importRecord(tenant,connector,run,type,customerRecord("地址同步客户")));
        long customer=jdbcTemplate.queryForObject("SELECT id FROM crm_customer WHERE tenant_id=?",Long.class,tenant.toString());
        shippingStore.save(tenant.toString(),customer,null,new com.rigour.merchant.api.v1.model.CustomerShippingAddressCommand(null,"本地收货人","13800000001","浙江省杭州市","本地详细地址",true,null),actor.toString());
        type=CrmMasterDataObjectType.ADDRESS;run=start(tenant,connector,actor,type);
        finish(tenant,connector,run,type,store.importRecord(tenant,connector,run,type,addressRecord("NEW-SHIPPING", "N-1")));
        var addresses=shippingStore.addresses(tenant.toString(),customer);
        assertThat(addresses).hasSize(2);
        assertThat(addresses.stream().filter(com.rigour.merchant.api.v1.model.CustomerShippingAddressView::defaultAddress).count()).isEqualTo(1);
        assertThat(internalCustomerStore.customer(tenant.toString(),customer).orElseThrow().contactName()).isEqualTo("本地收货人");
    }

    @Test
    void mergedCustomerAddressesRetainBothSourcesButOnlyPrimaryDefault() {
        UUID tenant=UUID.randomUUID(), connector=UUID.randomUUID(), actor=UUID.randomUUID();
        var type=CrmMasterDataObjectType.CUSTOMER; UUID run=start(tenant,connector,actor,type);
        var primary=customerRecord("合并地址客户");
        finish(tenant,connector,run,type,store.importRecord(tenant,connector,run,type,primary));
        byte[] party=customerTargetId(tenant,connector);
        jdbcTemplate.update("INSERT INTO crm_source_binding(id,tenant_id,connector_id,source_system,source_object_type,source_object_id,target_type,target_id,binding_status,primary_customer_source_id,source_fields_json,source_payload_hash,synced_at,created_time,updated_time) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'DINGHUOBAO','CUSTOMER','SECONDARY','PARTY',?,'UNRESOLVED','CLIENT-GUID-1',JSON_OBJECT(),REPEAT('0',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",UUID.randomUUID().toString(),tenant.toString(),connector.toString(),party);
        var fields=new LinkedHashMap<String,Object>(primary.sourceFields());
        fields.put("clientGUID","SECONDARY"); fields.put("clientNO","C-SECONDARY");
        run=start(tenant,connector,actor,type);
        finish(tenant,connector,run,type,store.importRecord(tenant,connector,run,type,new SourceRecord("SECONDARY","C-SECONDARY","合并地址客户","T",primary.sourceCreatedAt(),primary.sourceUpdatedAt(),fields)));
        type=CrmMasterDataObjectType.ADDRESS; run=start(tenant,connector,actor,type);
        var mainAddress=addressRecord("MAIN-ADDRESS","MAIN-A");
        var secondaryFields=new LinkedHashMap<String,Object>(mainAddress.sourceFields());
        secondaryFields.put("clientGuid","SECONDARY"); secondaryFields.put("clientNum","C-SECONDARY");
        var secondaryAddress=new SourceRecord("SECONDARY-ADDRESS","SECONDARY-A","副地址","T",null,mainAddress.sourceUpdatedAt(),secondaryFields);
        store.importRecord(tenant,connector,run,type,secondaryAddress);
        store.importRecord(tenant,connector,run,type,mainAddress);
        // Repair an already-imported duplicate default even when the source payload is unchanged.
        jdbcTemplate.update("UPDATE crm_address SET is_default=1 WHERE tenant_id=UUID_TO_BIN(?) AND address_type='SHIPPING'",tenant.toString());
        assertThat(store.importRecord(tenant,connector,run,type,secondaryAddress).duplicates()).isEqualTo(1);
        long customer=jdbcTemplate.queryForObject("SELECT id FROM crm_customer WHERE tenant_id=?",Long.class,tenant.toString());
        var addresses=shippingStore.addresses(tenant.toString(),customer);
        assertThat(addresses).hasSize(2);
        assertThat(addresses.stream().filter(com.rigour.merchant.api.v1.model.CustomerShippingAddressView::defaultAddress).count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT a.is_default FROM crm_address a JOIN crm_source_binding b ON b.target_id=a.id AND b.tenant_id=a.tenant_id WHERE b.tenant_id=UUID_TO_BIN(?) AND b.source_object_id='MAIN-ADDRESS'",Boolean.class,tenant.toString())).isTrue();
        store.completeRun(tenant,connector,run,type,new RunStatistics(3,2,0,0,1,0,0,1),false);
    }

    @Test
    void customerTimeSortingAppliesBeforePagination() {
        String tenant=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString();
        for (int i=1;i<=3;i++) {
            var command=new com.rigour.merchant.api.v1.model.InternalCustomerCommand("排序客户"+i,null,null,null,null,null,null,null,null,"postpaid",null,"ACTIVE",null,null,"sort-"+i);
            var created=internalCustomerStore.create(tenant,"SORT-"+i,command,actor);
            jdbcTemplate.update("UPDATE crm_customer SET business_created_at=?,synced_at=?,updated_time=? WHERE id=? AND tenant_id=?","2026-09-0"+i+" 00:00:00","2026-09-0"+(4-i)+" 00:00:00","2026-09-01 00:00:00",created.id(),tenant);
        }
        var defaults=new CrmInternalCustomerStore.CustomerSearchCriteria(null,null,null,null,null,null,null,null);
        assertThat(internalCustomerStore.customers(tenant,0,1,defaults).items().getFirst().customerName()).isEqualTo("排序客户3");
        var synced=new CrmInternalCustomerStore.CustomerSearchCriteria(null,null,null,null,null,null,null,null,"syncedAt","desc");
        assertThat(internalCustomerStore.customers(tenant,0,1,synced).items().getFirst().customerName()).isEqualTo("排序客户1");
        assertThat(internalCustomerStore.customers(tenant,1,1,synced).items().getFirst().customerName()).isEqualTo("排序客户2");
        var ascending=new CrmInternalCustomerStore.CustomerSearchCriteria(null,null,null,null,null,null,null,null,"businessCreatedAt","asc");
        assertThat(internalCustomerStore.customers(tenant,0,1,ascending).items().getFirst().customerName()).isEqualTo("排序客户1");
    }

    private com.rigour.merchant.api.v1.model.InternalCustomerCommand identityCommand(String name, String region, String account, Integer revision) {
        return new com.rigour.merchant.api.v1.model.InternalCustomerCommand(name,null,null,null,region,null,null,null,null,"postpaid",null,"ACTIVE",null,revision,account);
    }

    @Test
    void customerIdentityProtectsCityAndAccountsWithoutBlockingLegacyMaintenance() {
        UUID t=UUID.randomUUID(), a=UUID.randomUUID(); String tenant=t.toString(), actor=a.toString();
        for (String[] area : List.of(new String[]{"HZ","杭州"},new String[]{"HZ2","杭州市"},new String[]{"NB","宁波地区"}))
            queryStore.createCustomerArea(t,area[0],new com.rigour.merchant.api.v1.model.CrmCustomerAreaCommand(area[1],null,"ACTIVE",0,1),a);
        var original=internalCustomerStore.create(tenant,"ONE",identityCommand("同城门店","HZ","Login-One",null),actor);
        assertThatThrownBy(() -> internalCustomerStore.create(tenant,"TWO",identityCommand(" 同城门店 ","HZ2","different",null),actor)).hasMessageContaining("同一城市");
        var other=internalCustomerStore.create(tenant,"OTHER",identityCommand("同城门店","NB","login-two",null),actor);
        assertThatThrownBy(() -> internalCustomerStore.create(tenant,"THREE",identityCommand("另一门店","NB"," login-one ",null),actor)).hasMessageContaining("客户账号");
        assertThatThrownBy(() -> internalCustomerStore.update(tenant,other.id(),identityCommand("同城门店","NB","LOGIN-ONE",other.revision()),actor)).hasMessageContaining("客户账号");
        var legacy=internalCustomerStore.create(tenant,"LEGACY",identityCommand("历史旧名","HZ2","legacy",null),actor);
        jdbcTemplate.update("UPDATE crm_customer SET customer_name='同城门店' WHERE id=? AND tenant_id=?",legacy.id(),tenant);
        assertThat(internalCustomerStore.update(tenant,legacy.id(),identityCommand("同城门店","HZ2","legacy",legacy.revision()),actor).customerName()).isEqualTo("同城门店");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_customer SET login_account='LOGIN-ONE' WHERE id=? AND tenant_id=?",other.id(),tenant)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(internalCustomerStore.customer(tenant,original.id())).isPresent();
        assertThat(internalCustomerStore.create(UUID.randomUUID().toString(),"OTHER-TENANT",identityCommand("同城门店",null,"Login-One",null),actor)).isNotNull();
    }

    @Test
    void concurrentCreatesCannotBypassSameCityValidation() throws Exception {
        UUID t=UUID.randomUUID(), a=UUID.randomUUID();
        queryStore.createCustomerArea(t,"HZ",new com.rigour.merchant.api.v1.model.CrmCustomerAreaCommand("杭州地区",null,"ACTIVE",0,1),a);
        var start=new java.util.concurrent.CountDownLatch(1);
        try (var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i=0;i<2;i++) { final int n=i; tasks.add(pool.submit(() -> {
                start.await();
                try { internalCustomerStore.create(t.toString(),"CONCURRENT-"+n,identityCommand("并发门店","HZ","concurrent-"+n,null),a.toString()); return true; }
                catch(com.rigour.shared.core.exception.BusinessException ex) { assertThat(ex.getMessage()).contains("同一城市"); return false; }
            })); }
            start.countDown();
            int succeeded=0; for(var task:tasks) if(task.get(20,java.util.concurrent.TimeUnit.SECONDS)) succeeded++;
            assertThat(succeeded).isEqualTo(1);
        }
    }

    @Test
    void customerFuzzyAccountAndBusinessDateRangeUseChinaCalendarDays() {
        String tenant=UUID.randomUUID().toString(),actor=UUID.randomUUID().toString();
        String[] times={"2026-09-03 15:59:59","2026-09-03 16:00:00","2026-09-04 15:59:59","2026-09-04 16:00:00"};
        for (int i=0;i<times.length;i++) {
            var c=internalCustomerStore.create(tenant,"DATE-"+i,identityCommand("日期门店"+i,null,"prefix-account-"+i,null),actor);
            jdbcTemplate.update("UPDATE crm_customer SET business_created_at=? WHERE id=? AND tenant_id=?",times[i],c.id(),tenant);
        }
        var day=java.time.LocalDate.of(2026,9,4);
        var filter=new CrmInternalCustomerStore.CustomerSearchCriteria(null,"日期",null,null,null,null,null,null,"businessCreatedAt","desc","account",day,day);
        var result=internalCustomerStore.customers(tenant,0,10,filter);
        assertThat(result.items()).extracting(item -> item.customerName()).containsExactly("日期门店2","日期门店1");
        assertThat(internalCustomerStore.customers(tenant,1,1,filter).items().getFirst().customerName()).isEqualTo("日期门店1");
        jdbcTemplate.update("UPDATE crm_customer SET business_created_by_name=CASE WHEN customer_code='DATE-2' THEN '张三' ELSE '李四' END WHERE tenant_id=?",tenant);
        var byCreator = new CrmInternalCustomerStore.CustomerSearchCriteria(null,"日期",null,null,null,null,null,null,"businessCreatedAt","desc","account",day,day,"张三");
        assertThat(internalCustomerStore.customers(tenant,0,10,byCreator).items()).extracting(item -> item.customerName()).containsExactly("日期门店2");
        assertThat(internalCustomerStore.creators(tenant)).containsExactlyInAnyOrder("张三","李四");
        assertThat(internalCustomerStore.creators(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void sourceDuplicateAccountIsPendingWithoutCreatingAnotherCustomer() {
        UUID tenant=UUID.randomUUID(),connector=UUID.randomUUID(),actor=UUID.randomUUID();
        var type=CrmMasterDataObjectType.CUSTOMER; var run=start(tenant,connector,actor,type);
        var original=customerRecord("原门店");
        assertThat(store.importRecord(tenant,connector,run,type,original).created()).isEqualTo(1);
        var f=new LinkedHashMap<String,Object>(original.sourceFields()); f.put("clientGUID","SECOND-IDENTITY"); f.put("clientNO","SECOND-CODE"); f.put("clientCompanyName","不同名门店");
        var conflict=store.importRecord(tenant,connector,run,type,new SourceRecord("SECOND-IDENTITY","SECOND-CODE","不同名门店","T",original.sourceCreatedAt(),original.sourceUpdatedAt(),f));
        assertThat(conflict.unmapped()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=? AND deleted=0",Integer.class,tenant.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT binding_status FROM crm_source_binding WHERE tenant_id=UUID_TO_BIN(?) AND source_object_id='SECOND-IDENTITY'",String.class,tenant.toString())).isEqualTo("UNRESOLVED");
    }

    @Test
    void dhbCodeFiltersNaturalSortAndAreaSubtreeApplyBeforePagination() {
        UUID t=UUID.randomUUID(), a=UUID.randomUUID(); String tenant=t.toString();
        queryStore.createCustomerArea(t,"ZJ",new com.rigour.merchant.api.v1.model.CrmCustomerAreaCommand("浙江省",null,"ACTIVE",0,1),a);
        queryStore.createCustomerArea(t,"HZ",new com.rigour.merchant.api.v1.model.CrmCustomerAreaCommand("杭州市","ZJ","ACTIVE",0,1),a);
        queryStore.createCustomerArea(t,"HZ_CHILD",new com.rigour.merchant.api.v1.model.CrmCustomerAreaCommand("余杭区","HZ","ACTIVE",0,1),a);
        String[] codes={"2","10","100",null,""};
        for (int i=0;i<codes.length;i++) {
            var c=internalCustomerStore.create(tenant,"DHB-SORT-"+i,identityCommand("编码测试"+i,i==0?"HZ":"HZ_CHILD","dhb-sort-"+i,null),a.toString());
            jdbcTemplate.update("UPDATE crm_customer SET dhb_customer_code=? WHERE tenant_id=? AND id=?",codes[i],tenant,c.id());
        }
        var asc=new CrmInternalCustomerStore.CustomerSearchCriteria(null,null,null,null,"ZJ",null,null,null,"dhbCustomerCode","asc",null,null,null,null,null,null);
        assertThat(internalCustomerStore.customers(tenant,0,3,asc).items()).extracting(item -> item.dhbCustomerCode()).containsExactly("2","10","100");
        assertThat(internalCustomerStore.customers(tenant,1,1,asc).items().getFirst().dhbCustomerCode()).isEqualTo("10");
        var desc=new CrmInternalCustomerStore.CustomerSearchCriteria(null,null,null,null,"ZJ",null,null,null,"dhbCustomerCode","desc",null,null,null,null,null,"LINKED");
        assertThat(internalCustomerStore.customers(tenant,0,10,desc).items()).extracting(item -> item.dhbCustomerCode()).containsExactly("100","10","2");
        var missing=new CrmInternalCustomerStore.CustomerSearchCriteria(null,null,null,null,"HZ",null,null,null,"dhbCustomerCode","asc",null,null,null,null,null,"UNLINKED");
        assertThat(internalCustomerStore.customers(tenant,0,10,missing).total()).isEqualTo(2);
        var code=new CrmInternalCustomerStore.CustomerSearchCriteria(null,null,null,null,"ZJ",null,null,null,"dhbCustomerCode","asc",null,null,null,null,"10","LINKED");
        assertThat(internalCustomerStore.customers(tenant,0,10,code).items()).extracting(item -> item.dhbCustomerCode()).containsExactly("10","100");
        assertThat(internalCustomerStore.customers(UUID.randomUUID().toString(),0,10,asc).items()).isEmpty();
    }

    @Test
    void nativeShippingAddressesAreSeparateScopedAndTransactional() {
        String tenant=UUID.randomUUID().toString(), actor=UUID.randomUUID().toString();
        var shipping=new com.rigour.merchant.api.v1.model.CustomerShippingAddressCommand("单位","收货人甲","13800000001","浙江省 杭州市 西湖区","一号路1号",true,null);
        var command=new com.rigour.merchant.api.v1.model.InternalCustomerCommand("独立地址客户",null,null,null,null,null,null,null,null,"postpaid",null,"ACTIVE",null,null,"login-shipping",shipping);
        var customer=internalCustomerStore.create(tenant,"SHIP-CUSTOMER",command,actor);
        assertThat(customer.loginAccount()).isEqualTo("login-shipping");
        assertThat(customer.contactName()).isEqualTo("收货人甲");
        var first=shippingStore.addresses(tenant,customer.id()).getFirst();
        var second=shippingStore.save(tenant,customer.id(),null,new com.rigour.merchant.api.v1.model.CustomerShippingAddressCommand(null,"收货人乙","13800000002","浙江省 杭州市 西湖区","二号路2号",true,null),actor);
        var addresses=shippingStore.addresses(tenant,customer.id());
        assertThat(addresses).hasSize(2);assertThat(addresses.stream().filter(com.rigour.merchant.api.v1.model.CustomerShippingAddressView::defaultAddress).count()).isEqualTo(1);
        assertThat(internalCustomerStore.customer(tenant,customer.id()).orElseThrow().contactPhone()).isEqualTo("13800000002");
        assertThatThrownBy(()->shippingStore.save(tenant,customer.id(),first.id(),new com.rigour.merchant.api.v1.model.CustomerShippingAddressCommand(null,"失效修改","13800000003","浙江省 杭州市","三号路",false,first.revision()),actor)).hasMessageContaining("已被修改");
        assertThatThrownBy(()->shippingStore.addresses(UUID.randomUUID().toString(),customer.id())).isInstanceOf(RuntimeException.class);
        shippingStore.delete(tenant,customer.id(),second.id(),second.revision(),actor);
        assertThat(shippingStore.addresses(tenant,customer.id()).getFirst().defaultAddress()).isTrue();
        assertThat(internalCustomerStore.customer(tenant,customer.id()).orElseThrow().contactName()).isEqualTo("收货人甲");
        var bad=new com.rigour.merchant.api.v1.model.InternalCustomerCommand("回滚客户",null,null,null,null,null,null,null,null,"postpaid",null,"ACTIVE",null,null,"login-bad",new com.rigour.merchant.api.v1.model.CustomerShippingAddressCommand(null,"",null,null,null,true,null));
        assertThatThrownBy(()->internalCustomerStore.create(tenant,"SHIP-BAD",bad,actor)).hasMessageContaining("收货人不能为空");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=? AND customer_code='SHIP-BAD'",Integer.class,tenant)).isZero();
    }

    private UUID start(
            UUID tenantId, UUID connectorId, UUID actorId, CrmMasterDataObjectType type) {
        if (type == CrmMasterDataObjectType.CUSTOMER && jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_source_binding WHERE tenant_id=UUID_TO_BIN(?) AND connector_id=UUID_TO_BIN(?) AND source_object_type='CUSTOMER_AREA' AND source_object_id='DEFAULT-AREA'", Integer.class, tenantId.toString(), connectorId.toString()) == 0) {
            UUID areaRun = store.startRun(tenantId, connectorId, actorId, CrmMasterDataObjectType.CUSTOMER_AREA, 100, "TEST");
            var areaResult = store.importRecord(tenantId, connectorId, areaRun, CrmMasterDataObjectType.CUSTOMER_AREA,
                    new SourceRecord("DEFAULT-AREA", "DEFAULT-AREA", "上海市", "T", null, null, Map.of("AreaName", "上海市")));
            finish(tenantId, connectorId, areaRun, CrmMasterDataObjectType.CUSTOMER_AREA, areaResult);
        }
        return store.startRun(tenantId, connectorId, actorId, type, 100, "TEST");
    }

    private void finish(
            UUID tenantId,
            UUID connectorId,
            UUID runId,
            CrmMasterDataObjectType type,
            ImportResult result) {
        long fetched =
                result.created()
                        + result.changed()
                        + result.repaired()
                        + result.duplicates()
                        + result.rejected()
                        + result.unmapped();
        store.completeRun(
                tenantId,
                connectorId,
                runId,
                type,
                new RunStatistics(
                        fetched,
                        result.created(),
                        result.changed(),
                        result.repaired(),
                        result.duplicates(),
                        0,
                        result.rejected(),
                        1),
                true);
    }

    private byte[] customerTargetId(UUID tenantId, UUID connectorId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT target_id FROM crm_source_binding
                 WHERE tenant_id=? AND connector_id=? AND source_object_type='CUSTOMER'
                   AND source_object_id='CLIENT-GUID-1'
                """,
                byte[].class,
                CrmUuidCodec.encode(tenantId),
                CrmUuidCodec.encode(connectorId));
    }

    private long partyVersion(UUID tenantId, byte[] partyId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT revision FROM crm_party WHERE tenant_id=? AND id=?
                """,
                Long.class,
                CrmUuidCodec.encode(tenantId),
                partyId);
    }

    private Map<String, Object> internalCustomerClassification(UUID tenantId, byte[] partyId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT customer_type_code, region_code
                  FROM crm_customer
                 WHERE tenant_id=? AND party_id=? AND deleted=0
                """,
                tenantId.toString(),
                partyId);
    }

    private String sourcePresence(UUID tenantId, UUID connectorId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT source_presence FROM crm_source_binding
                 WHERE tenant_id=? AND connector_id=? AND source_object_type='ADDRESS'
                   AND source_object_id='ADDR-GUID-1'
                """,
                String.class,
                CrmUuidCodec.encode(tenantId),
                CrmUuidCodec.encode(connectorId));
    }

    private static SourceRecord customerRecord(String name) {
        return new SourceRecord(
                "CLIENT-GUID-1",
                "C-001",
                name,
                "T",
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                mapOf(
                        "clientGUID",
                        "CLIENT-GUID-1",
                        "clientNO",
                        "C-001",
                        "clientAccount",
                        "customer001",
                        "clientCompanyName",
                        name,
                        "clientStatus",
                        "T",
                        "clientTrueName",
                        "张三",
                        "clientPhone",
                        "13800000000",
                        "clientEmail",
                        "customer@example.com",
                        "clientAdd",
                        "上海市浦东新区世纪大道1号",
                        "clientClearingForm",
                        "PREPAID",
                        "clientTypeName",
                        "VIP",
                        "clientAreaName",
                        "华东",
                        "clientCity",
                        "上海",
                        "Inviter",
                        "李四",
                        "clientAbout",
                        "来源字段完整保留",
                        "futureField", 42,
                        "clientArea", "DEFAULT-AREA",
                        "staffID", "STAFF-PRIMARY",
                        "staffName", "订货宝来源名称",
                        "_employeeBySourceId", Map.of("STAFF-PRIMARY", Map.of("employeeCode", "EMP-PRIMARY", "employeeName", "本系统员工"))));
    }

    private static SourceRecord customerRecordWithTypeArea(
            String typeSourceId, String areaSourceId) {
        SourceRecord base = customerRecord("示例客户");
        Map<String, Object> fields = new LinkedHashMap<>(base.sourceFields());
        fields.put("clientType", typeSourceId);
        fields.put("clientArea", areaSourceId);
        return new SourceRecord(
                base.sourceId(),
                base.sourceCode(),
                base.sourceName(),
                base.sourceStatus(),
                base.sourceCreatedAt(),
                base.sourceUpdatedAt(),
                fields);
    }

    private static SourceRecord customerRecordWithAssignments() {
        SourceRecord base = customerRecord("含主辅业务员客户");
        Map<String, Object> fields = new LinkedHashMap<>(base.sourceFields());
        fields.put("staffID", "STAFF-PRIMARY,STAFF-SECONDARY");
        fields.put("staffName", "张三,李四");
        fields.put(
                "_employeeBySourceId",
                Map.of(
                        "STAFF-PRIMARY",
                                Map.of("employeeCode", "RY202608220001", "employeeName", "张三"),
                        "STAFF-SECONDARY",
                                Map.of("employeeCode", "RY202608220002", "employeeName", "李四")));
        return new SourceRecord(
                base.sourceId(),
                base.sourceCode(),
                base.sourceName(),
                base.sourceStatus(),
                base.sourceCreatedAt(),
                base.sourceUpdatedAt(),
                fields);
    }

    private static SourceRecord addressRecord(String sourceId, String addressId) {
        return new SourceRecord(
                sourceId,
                addressId,
                "上海仓",
                "T",
                null,
                Instant.parse("2026-08-01T00:30:00Z"),
                mapOf(
                        "addressId",
                        addressId,
                        "addressGuid",
                        sourceId,
                        "clientGuid",
                        "CLIENT-GUID-1",
                        "clientNum",
                        "C-001",
                        "consignee",
                        "上海仓",
                        "contact",
                        "张三",
                        "phone",
                        "13800000000",
                        "address",
                        "上海市浦东新区",
                        "addressDetail",
                        "世纪大道1号",
                        "isDefault",
                        "T"));
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
    @org.springframework.beans.factory.annotation.Autowired private com.rigour.merchant.application.port.out.SupplyReadinessStore supplyReadiness;
    @org.junit.jupiter.api.Test
    void readinessChecksRunAgainstTheMigratedTenantSchema() {
      var report=supplyReadiness.inspect(java.util.UUID.randomUUID().toString());
      org.assertj.core.api.Assertions.assertThat(report.contractVersion()).isEqualTo(1);
      org.assertj.core.api.Assertions.assertThat(report.version()).isNotBlank();
      org.assertj.core.api.Assertions.assertThat(report.checks()).allMatch(c->c.count()==0);
    }
}
