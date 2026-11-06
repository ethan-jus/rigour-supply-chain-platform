package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.*;
import com.rigour.erp.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.util.*;

/** 真实 MyBatis/H2 台账闭环；不连接共享 DEV 或执行库存/单据写入。 */
class ManualCostPersistenceTest {
    final JsonMapper json = JsonMapper.builder().build();
    final String request = UUID.randomUUID().toString();
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    MybatisPlusErpCostRepository store;
    CostReferenceSource refs;
    MybatisPlusCostAttachmentRepository attachments;

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:manual_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        String ddl =
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/migration/V37__erp_cost_management.sql"))
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "")
                        .replace(" JSON ", " LONGTEXT ")
                        .replace("'MARKETING','RENTAL'", "'MARKETING','RENTAL','PURCHASE','LOSS'");
        for (String sql : ddl.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        jdbc.execute("ALTER TABLE erp_cost_entry ADD COLUMN deleted INT NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE erp_cost_entry ADD COLUMN cost_month VARCHAR(7)");
        jdbc.execute("ALTER TABLE erp_cost_entry ADD COLUMN source_system VARCHAR(20)");
        jdbc.execute("ALTER TABLE erp_cost_entry ADD COLUMN premises_type VARCHAR(20)");
        String attachmentDdl =
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/migration/V39__cost_attachments.sql"))
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "");
        jdbc.execute(attachmentDdl);
        jdbc.execute(
                "ALTER TABLE erp_cost_entry_attachment ADD COLUMN deleted INT NOT NULL DEFAULT 0");
        var config = new MybatisConfiguration();
        config.addMapper(CostAttachmentMapper.class);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ErpCostMapper.class);
        config.addMapper(ErpCostAuditMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        attachments =
                new MybatisPlusCostAttachmentRepository(
                        session.getMapper(CostAttachmentMapper.class),
                        session.getMapper(ErpCostMapper.class));
        var scope = mock(ErpWarehouseDataScope.class);
        when(scope.predicate(anyString(), any(String[].class)))
                .thenReturn(new ErpWarehouseDataScope.Predicate("1=0", List.of()));
        when(scope.manualPredicate(anyString(), anyString()))
                .thenReturn(new ErpWarehouseDataScope.Predicate("created_by=?", List.of("U")));
        refs = mock(CostReferenceSource.class);
        store =
                new MybatisPlusErpCostRepository(
                        session.getMapper(ErpCostMapper.class),
                        session.getMapper(ErpCostAuditMapper.class),
                        json,
                        scope,
                        refs,
                        mock(ErpCostReferenceMapper.class));
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    ErpCostCommand command(int revision, String amount, String reason) {
        return json.readValue(
                "{\"requestId\":\""
                        + request
                        + "\",\"revision\":"
                        + revision
                        + ",\"title\":\"人工台账\",\"cityCode\":\"C1\",\"cityName\":\"城市\",\"costDate\":\"2026-10-15\",\"amount\":"
                        + amount
                        + ",\"paymentStatusCode\":\"UNPAID\",\"reason\":\""
                        + reason
                        + "\",\"goods\":{\"productName\":\"手填品\",\"unit\":\"箱\",\"unitPrice\":12.34}}",
                ErpCostCommand.class);
    }

    ErpCostDetailView save(String id, ErpCostCommand c) {
        return store.save("T1", "PURCHASE", id, c, List.of(), null, null, "U", "cost:write");
    }

    @Test
    void manualRecordPersistsWithIdempotencyRevisionAuditAndTransitions() {
        var c = command(1, "null", "");
        var first = save(null, c);
        assertThat(first.data().amount()).isNull();
        assertThat(first.sources()).isEmpty();
        assertThat(first.createdBy()).isEqualTo("U");
        assertThat(save(null, c).id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM erp_cost_entry", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                store.transition(
                                        "T1",
                                        "PURCHASE",
                                        first.id(),
                                        new ErpCostActionCommand(first.revision(), ""),
                                        "U",
                                        "cost:confirm"))
                .hasMessageContaining("金额");
        var filled = save(first.id(), command(first.revision(), "123.45", ""));
        assertThat(filled.revision()).isEqualTo(2);
        var confirmed =
                store.transition(
                        "T1",
                        "PURCHASE",
                        first.id(),
                        new ErpCostActionCommand(2, ""),
                        "U",
                        "cost:confirm");
        assertThat(confirmed.statusCode()).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> save(first.id(), command(3, "124", "任意原因")))
                .hasMessageContaining("撤销");
        assertThatThrownBy(
                        () ->
                                store.lifecycle(
                                        "T1",
                                        "PURCHASE",
                                        first.id(),
                                        new ErpCostActionCommand(3, "不能删除"),
                                        "U",
                                        "cost:void",
                                        true))
                .hasMessageContaining("作废");
        var revoked =
                store.lifecycle(
                        "T1",
                        "PURCHASE",
                        first.id(),
                        new ErpCostActionCommand(3, "补录更正前撤销"),
                        "U",
                        "cost:confirm",
                        false);
        var updated = save(first.id(), command(revoked.revision(), "124", "补录更正"));
        assertThat(updated.statusCode()).isEqualTo("DRAFT");
        assertThatThrownBy(
                        () ->
                                store.transition(
                                        "T1",
                                        "PURCHASE",
                                        first.id(),
                                        new ErpCostActionCommand(2, ""),
                                        "U",
                                        "cost:confirm"))
                .hasMessageContaining("修改");
        var voided =
                store.transition(
                        "T1",
                        "PURCHASE",
                        first.id(),
                        new ErpCostActionCommand(updated.revision(), "重复登记"),
                        "U",
                        "cost:void");
        assertThat(voided.statusCode()).isEqualTo("VOID");
        assertThatThrownBy(() -> save(first.id(), command(voided.revision(), "124", "更正")))
                .hasMessageContaining("待确认");
        assertThatThrownBy(
                        () ->
                                store.lifecycle(
                                        "T1",
                                        "PURCHASE",
                                        first.id(),
                                        new ErpCostActionCommand(voided.revision() - 1, "旧版本"),
                                        "U",
                                        "cost:void",
                                        true))
                .hasMessageContaining("变化");
        store.lifecycle(
                "T1",
                "PURCHASE",
                first.id(),
                new ErpCostActionCommand(voided.revision(), "删除作废单据"),
                "U",
                "cost:void",
                true);
        assertThatThrownBy(() -> store.entry("T1", "PURCHASE", first.id(), "cost:read"))
                .hasMessageContaining("不存在");
        assertThat(store.history("T1", first.id()))
                .extracting(a -> a.action())
                .contains("REVOKE", "DELETE");
        assertThatThrownBy(() -> store.entry("T2", "PURCHASE", first.id(), "cost:read"))
                .hasMessageContaining("不存在");
        verifyNoInteractions(refs);
    }

    @Test
    void attachmentMetadataRevisionAndAuditCommitOrRollbackTogether() {
        var first = save(null, command(1, "10", ""));
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        var file =
                new ErpCostAttachmentView(
                        UUID.randomUUID().toString(),
                        "凭据.pdf",
                        "application/pdf",
                        20,
                        "U",
                        java.time.Instant.now());
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        status -> {
                                            attachments.add("T1", first.id(), 1, file, null);
                                            return null;
                                        }))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.entry("T1", "PURCHASE", first.id(), "cost:read").revision()).isEqualTo(1);
        assertThat(attachments.list("T1", first.id())).isEmpty();
        tx.execute(
                status -> {
                    attachments.add("T1", first.id(), 1, file, "private/key");
                    store.attachmentAdded(
                            "T1", "PURCHASE", first.id(), "U", file.id(), "cost:write");
                    return null;
                });
        assertThat(store.entry("T1", "PURCHASE", first.id(), "cost:read").revision()).isEqualTo(2);
        assertThat(attachments.list("T1", first.id())).hasSize(1);
        assertThat(store.history("T1", first.id())).hasSize(2);
        assertThatThrownBy(() -> attachments.fileKey("T2", first.id(), file.id()))
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> attachments.fileKey("T1", "other", file.id()))
                .hasMessageContaining("不存在");
    }

    @Test
    void duplicateLossRequiresExplanationButIdempotentRetryDoesNot() {
        jdbc.execute(
                "CREATE ALIAS JSON_EXTRACT FOR"
                    + " 'com.rigour.erp.infrastructure.persistence.repository.ErpCostFilterTest.extract'");
        jdbc.execute(
                "CREATE ALIAS JSON_UNQUOTE FOR"
                    + " 'com.rigour.erp.infrastructure.persistence.repository.ErpCostFilterTest.unquote'");
        var firstCommand =
                json.readValue(
                        json.writeValueAsString(command(1, "10", ""))
                                .replace("\"quantity\":null", "\"quantity\":2")
                                .replace("\"location\":null", "\"location\":\"卸货点\""),
                        ErpCostCommand.class);
        var first =
                store.save(
                        "T1", "LOSS", null, firstCommand, List.of(), null, null, "U", "cost:write");
        assertThat(
                        store.save(
                                        "T1",
                                        "LOSS",
                                        null,
                                        firstCommand,
                                        List.of(),
                                        null,
                                        null,
                                        "U",
                                        "cost:write")
                                .id())
                .isEqualTo(first.id());
        var duplicate =
                json.readValue(
                        json.writeValueAsString(firstCommand)
                                .replace(request, UUID.randomUUID().toString()),
                        ErpCostCommand.class);
        assertThatThrownBy(
                        () ->
                                store.save(
                                        "T1",
                                        "LOSS",
                                        null,
                                        duplicate,
                                        List.of(),
                                        null,
                                        null,
                                        "U",
                                        "cost:write"))
                .hasMessageContaining("已有损耗");
        var explained =
                json.readValue(
                        json.writeValueAsString(duplicate)
                                .replace("\"reason\":\"\"", "\"reason\":\"另一车次\""),
                        ErpCostCommand.class);
        store.save("T1", "LOSS", null, explained, List.of(), null, null, "U", "cost:write");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM erp_cost_entry", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void replacingAndRemovingRetainFilesAndRollbackAsOneMutation() {
        var first = save(null, command(1, "10", ""));
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        var old =
                new ErpCostAttachmentView(
                        "old", "原运费凭证.pdf", "application/pdf", 20, "U", java.time.Instant.now());
        var replacement =
                new ErpCostAttachmentView(
                        "new", "更正运费凭证.pdf", "application/pdf", 20, "U", java.time.Instant.now());
        tx.execute(
                status -> {
                    attachments.add("T1", first.id(), 1, old, "private/old");
                    return null;
                });
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        status -> {
                                            attachments.retire("T1", first.id(), old.id());
                                            attachments.add("T1", first.id(), 2, replacement, null);
                                            return null;
                                        }))
                .isInstanceOf(RuntimeException.class);
        assertThat(attachments.list("T1", first.id()))
                .extracting(f -> f.id())
                .containsExactly("old");
        tx.execute(
                status -> {
                    attachments.retire("T1", first.id(), old.id());
                    attachments.add("T1", first.id(), 2, replacement, "private/new");
                    store.attachmentChanged(
                            "T1",
                            "PURCHASE",
                            first.id(),
                            "U",
                            "ATTACHMENT_REPLACED",
                            "原运费凭证 → 更正运费凭证",
                            "cost:write");
                    return null;
                });
        assertThat(attachments.list("T1", first.id()))
                .extracting(f -> f.id())
                .containsExactly("new");
        assertThatThrownBy(() -> attachments.fileKey("T1", first.id(), old.id()))
                .hasMessageContaining("不存在");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT file_key FROM erp_cost_entry_attachment WHERE id='old'",
                                String.class))
                .isEqualTo("private/old");
        assertThatThrownBy(() -> attachments.retire("T2", first.id(), replacement.id()))
                .hasMessageContaining("变化");
        tx.execute(
                status -> {
                    attachments.touch("T1", first.id(), 3, "U");
                    attachments.retire("T1", first.id(), replacement.id());
                    store.attachmentChanged(
                            "T1",
                            "PURCHASE",
                            first.id(),
                            "U",
                            "ATTACHMENT_REMOVED",
                            "更正运费凭证",
                            "cost:write");
                    return null;
                });
        assertThat(attachments.list("T1", first.id())).isEmpty();
        assertThat(store.entry("T1", "PURCHASE", first.id(), "cost:read").revision()).isEqualTo(4);
        assertThat(store.history("T1", first.id()))
                .extracting(a -> a.action())
                .contains("ATTACHMENT_REPLACED", "ATTACHMENT_REMOVED");
    }
}
