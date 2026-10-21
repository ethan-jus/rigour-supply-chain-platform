package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.order.api.v1.model.*;
import com.rigour.order.application.port.out.*;
import com.rigour.order.infrastructure.persistence.mapper.*;

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
    MybatisPlusOrderLogisticsCostRepository store;
    OrderLogisticsReferences refs;
    LogisticsWarehouseDataScope scope;
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
                                        "src/main/resources/db/migration/V54__order_logistics_cost.sql"))
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "")
                        .replace(" JSON ", " LONGTEXT ")
                        .replace("'MARKETING','RENTAL'", "'MARKETING','RENTAL','PURCHASE','LOSS'");
        for (String sql : ddl.split(";")) if (!sql.isBlank()) jdbc.execute(sql);

        jdbc.execute("ALTER TABLE order_logistics_cost ADD COLUMN source_system VARCHAR(20)");
        String attachmentDdl =
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/migration/V56__cost_attachments.sql"))
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "");
        jdbc.execute(attachmentDdl);
        var config = new MybatisConfiguration();
        config.addMapper(CostAttachmentMapper.class);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(OrderLogisticsCostMapper.class);
        config.addMapper(OrderLogisticsCostAuditMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        attachments =
                new MybatisPlusCostAttachmentRepository(
                        session.getMapper(CostAttachmentMapper.class),
                        session.getMapper(OrderLogisticsCostMapper.class));
        scope = mock(LogisticsWarehouseDataScope.class);
        when(scope.predicate(anyString(), any(String[].class)))
                .thenReturn(new LogisticsWarehouseDataScope.Predicate("1=0", List.of()));
        when(scope.manualPredicate(anyString(), anyString()))
                .thenReturn(
                        new LogisticsWarehouseDataScope.Predicate("created_by=?", List.of("U")));
        refs = mock(OrderLogisticsReferences.class);
        store =
                new MybatisPlusOrderLogisticsCostRepository(
                        session.getMapper(OrderLogisticsCostMapper.class),
                        session.getMapper(OrderLogisticsCostAuditMapper.class),
                        json,
                        scope,
                        refs);
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    LogisticsCostCommand command(int revision, String amount, String reason) {
        return json.readValue(
                "{\"requestId\":\""
                        + request
                        + "\",\"revision\":"
                        + revision
                        + ",\"title\":\"人工台账\",\"cityCode\":\"C1\",\"cityName\":\"城市\",\"costDate\":\"2026-10-15\",\"amount\":"
                        + amount
                        + ",\"paymentStatusCode\":\"UNPAID\",\"reason\":\""
                        + reason
                        + "\",\"logistics\":{\"entryMode\":\"MANUAL\",\"legCode\":\"TRANSFER\",\"sourceIds\":[],\"payerCode\":\"COMPANY\",\"senderName\":\"中心仓\",\"receiverName\":\"城市仓\",\"externalDocuments\":[{\"sourceSystem\":\"DHB\",\"documentType\":\"TRANSFER\",\"documentNo\":\"DHB-1\"}]}}",
                LogisticsCostCommand.class);
    }

    LogisticsCostDetailView save(String id, LogisticsCostCommand c) {
        return store.save("T1", "LOGISTICS", id, c, List.of(), null, null, "U", "cost:write");
    }

    @Test
    void supplementalDeliveryWarehouseIsPersistedWhileSourceSnapshotRemainsUnassigned() {
        var c =
                json.readValue(
                        json.writeValueAsString(command(1, "1", ""))
                                .replace("TRANSFER", "DELIVERY")
                                .replace("\"sourceIds\":[]", "\"sourceIds\":[1]")
                                .replace("\"sourceWarehouseId\":null", "\"sourceWarehouseId\":7"),
                        LogisticsCostCommand.class);
        var source =
                json.readValue(
                        "{\"id\":1,\"warehouseId\":null,\"sourceSystem\":\"DHB\"}",
                        LogisticsCostSourceView.class);
        when(scope.predicate(anyString(), any(String[].class)))
                .thenReturn(new LogisticsWarehouseDataScope.Predicate("1=1", List.of()));
        var saved =
                store.save(
                        "T1", "LOGISTICS", null, c, List.of(source), null, null, "U", "cost:write");
        assertThat(saved.sources().getFirst().warehouseId()).isNull();
        verify(scope).requirePair(7L, 7L, "cost:write");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_warehouse_id FROM order_logistics_cost", Long.class))
                .isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT warehouse_id FROM order_logistics_cost", Long.class))
                .isEqualTo(7L);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sources_json FROM order_logistics_cost", String.class))
                .contains("\"warehouseId\":null");
    }

    @Test
    void manualRecordPersistsWithIdempotencyRevisionAuditAndTransitions() {
        var c = command(1, "null", "");
        var first = save(null, c);
        assertThat(first.data().amount()).isNull();
        assertThat(first.sources()).isEmpty();
        assertThat(first.createdBy()).isEqualTo("U");
        assertThat(save(null, c).id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                store.transition(
                                        "T1",
                                        "LOGISTICS",
                                        first.id(),
                                        new LogisticsCostActionCommand(first.revision(), ""),
                                        "U",
                                        "cost:confirm"))
                .hasMessageContaining("金额");
        var filled = save(first.id(), command(first.revision(), "123.45", ""));
        assertThat(filled.revision()).isEqualTo(2);
        var confirmed =
                store.transition(
                        "T1",
                        "LOGISTICS",
                        first.id(),
                        new LogisticsCostActionCommand(2, ""),
                        "U",
                        "cost:confirm");
        assertThat(confirmed.statusCode()).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> save(first.id(), command(3, "124", "")))
                .hasMessageContaining("原因");
        var updated = save(first.id(), command(3, "124", "补录更正"));
        assertThat(updated.statusCode()).isEqualTo("DRAFT");
        assertThatThrownBy(
                        () ->
                                store.transition(
                                        "T1",
                                        "LOGISTICS",
                                        first.id(),
                                        new LogisticsCostActionCommand(2, ""),
                                        "U",
                                        "cost:confirm"))
                .hasMessageContaining("修改");
        var voided =
                store.transition(
                        "T1",
                        "LOGISTICS",
                        first.id(),
                        new LogisticsCostActionCommand(updated.revision(), "重复登记"),
                        "U",
                        "cost:void");
        assertThat(voided.statusCode()).isEqualTo("VOID");
        assertThatThrownBy(() -> save(first.id(), command(voided.revision(), "124", "更正")))
                .hasMessageContaining("作废");
        assertThat(store.history("T1", first.id())).hasSize(7);
        assertThatThrownBy(() -> store.entry("T2", "LOGISTICS", first.id(), "cost:read"))
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
                new LogisticsCostAttachmentView(
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
        assertThat(store.entry("T1", "LOGISTICS", first.id(), "cost:read").revision()).isEqualTo(1);
        assertThat(attachments.list("T1", first.id())).isEmpty();
        tx.execute(
                status -> {
                    attachments.add("T1", first.id(), 1, file, "private/key");
                    store.attachmentAdded(
                            "T1", "LOGISTICS", first.id(), "U", file.id(), "cost:write");
                    return null;
                });
        assertThat(store.entry("T1", "LOGISTICS", first.id(), "cost:read").revision()).isEqualTo(2);
        assertThat(attachments.list("T1", first.id())).hasSize(1);
        assertThat(store.history("T1", first.id())).hasSize(2);
        assertThatThrownBy(() -> attachments.fileKey("T2", first.id(), file.id()))
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> attachments.fileKey("T1", "other", file.id()))
                .hasMessageContaining("不存在");
    }

    @Test
    void laterAssociationPreservesDhbOriginalNumberAndDoesNotCreateSecondCost() {
        var first = save(null, command(1, "10", ""));
        String body = json.writeValueAsString(command(1, "10", ""));
        var linked =
                json.readValue(
                        body.replace("\"sourceIds\":[]", "\"sourceIds\":[1]")
                                .replace(
                                        "\"documentNo\":\"DHB-1\"",
                                        "\"documentNo\":\"DHB-1\",\"linkedId\":1"),
                        LogisticsCostCommand.class);
        var saved = save(first.id(), linked);
        assertThat(saved.id()).isEqualTo(first.id());
        assertThat(saved.data().amount()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_logistics_cost", Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_system FROM order_logistics_cost", String.class))
                .isEqualTo("DHB");
        var tampered =
                json.readValue(
                        json.writeValueAsString(linked).replace("DHB-1", "REPLACED"),
                        LogisticsCostCommand.class);
        assertThatThrownBy(() -> save(first.id(), tampered)).hasMessageContaining("原始来源");
        assertThat(store.entry("T1", "LOGISTICS", first.id(), "cost:read").revision()).isEqualTo(2);
    }
}
