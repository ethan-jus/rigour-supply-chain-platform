package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.order.api.v1.model.LogisticsCostAttachmentView;
import com.rigour.order.infrastructure.persistence.mapper.*;

import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.UUID;

/** 实际执行生产Mapper SQL，覆盖保存状态变化对附件保存及幂等回执的影响。 */
class TransportAttachmentPersistenceTest {
    SingleConnectionDataSource ds;
    JdbcTemplate db;
    SqlSession session;
    MybatisPlusCostAttachmentRepository files;

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        db = new JdbcTemplate(ds);
        db.execute(
                "CREATE TABLE order_logistics_cost(id VARCHAR(36),tenant_id VARCHAR(36),status_code"
                    + " VARCHAR(20),deleted INT DEFAULT 0,revision INT,updated_by"
                    + " VARCHAR(36),updated_time TIMESTAMP,attachment_save_id"
                    + " VARCHAR(36),attachment_save_hash VARCHAR(64),attachment_save_revision"
                    + " INT,PRIMARY KEY(tenant_id,id))");
        db.execute(
                "CREATE TABLE order_logistics_cost_attachment(id VARCHAR(36),tenant_id"
                    + " VARCHAR(36),entry_id VARCHAR(36),file_key VARCHAR(500),file_name"
                    + " VARCHAR(200),content_type VARCHAR(100),file_size BIGINT,category_code"
                    + " VARCHAR(32),created_by VARCHAR(36),created_time TIMESTAMP,deleted INT"
                    + " DEFAULT 0)");
        var config = new MybatisConfiguration();
        config.addMapper(OrderLogisticsCostMapper.class);
        config.addMapper(CostAttachmentMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        session = factory.getObject().openSession(true);
        files =
                new MybatisPlusCostAttachmentRepository(
                        session.getMapper(CostAttachmentMapper.class),
                        session.getMapper(OrderLogisticsCostMapper.class));
        db.update(
                "INSERT INTO order_logistics_cost(id,tenant_id,status_code,revision)"
                    + " VALUES('C1','T','ACTIVE',1),('C1','OTHER','ACTIVE',1),('VOID','T','VOID',1)");
    }

    @AfterEach
    void close() {
        if (session != null) session.close();
        if (ds != null) ds.destroy();
    }

    @Test
    void activeTransportCanSaveAttachmentAndReceiptWithoutAnyConfirmation() {
        files.add(
                "T",
                "C1",
                1,
                new LogisticsCostAttachmentView(
                        "F1",
                        "QA.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        864,
                        "U",
                        Instant.EPOCH,
                        "EXPENSE_VOUCHER"),
                "T/costs/C1/F1.docx");
        files.completed("T", "C1", "SUBMIT1", "hash", 2);
        assertThat(files.list("T", "C1")).hasSize(1);
        assertThat(files.submission("T", "SUBMIT1").revision()).isEqualTo(2);
        assertThat(files.submission("OTHER", "SUBMIT1")).isNull();
        files.completed("T", "C1", "SUBMIT1", "hash", 2);
        assertThat(
                        db.queryForObject(
                                "SELECT revision FROM order_logistics_cost WHERE tenant_id='T' AND"
                                    + " id='C1'",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT revision FROM order_logistics_cost WHERE tenant_id='OTHER'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void staleRevisionVoidAndDeletedRecordsCannotAcceptFilesOrSaveReceipt() {
        assertThatThrownBy(() -> files.touch("T", "C1", 0, "U")).hasMessageContaining("已被修改");
        assertThatThrownBy(() -> files.touch("T", "VOID", 1, "U")).hasMessageContaining("已被修改");
        assertThatThrownBy(() -> files.completed("OTHER", "VOID", "S", "hash", 1))
                .hasMessageContaining("已变化");
        db.update("UPDATE order_logistics_cost SET deleted=1 WHERE tenant_id='T' AND id='C1'");
        assertThatThrownBy(() -> files.completed("T", "C1", "S", "hash", 1))
                .hasMessageContaining("已变化");
        assertThat(files.list("T", "C1")).isEmpty();
    }
}
