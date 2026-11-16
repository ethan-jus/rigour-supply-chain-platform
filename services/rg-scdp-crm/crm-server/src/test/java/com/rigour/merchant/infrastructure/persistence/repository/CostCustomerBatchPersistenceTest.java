package com.rigour.merchant.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.rigour.merchant.infrastructure.persistence.mapper.InternalCustomerMapper;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.mockito.AdditionalAnswers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.*;

class CostCustomerBatchPersistenceTest {
    SingleConnectionDataSource data;
    SqlSession session;
    InternalCustomerMapper mapper;
    MybatisPlusCostCustomerReferenceRepository repository;

    @BeforeEach
    void setup() {
        data =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        var db = new JdbcTemplate(data);
        // Deliberately omit bulky source/audit columns: the selector must only read contact fields.
        db.execute(
                "CREATE TABLE crm_customer(id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),region_code"
                    + " VARCHAR(64),customer_code VARCHAR(100),customer_name"
                    + " VARCHAR(100),contact_name VARCHAR(100),contact_phone VARCHAR(100),address"
                    + " VARCHAR(100),deleted INT,status_code VARCHAR(20))");
        Object[][] records = {
            {1L, "TENANT", "WH", "C1", 0, "ACTIVE"},
            {2L, "TENANT", "BJ", "C2", 0, "ACTIVE"},
            {3L, "TENANT", "BJ", "C1", 0, "ACTIVE"},
            {4L, "TENANT", "WH", "C2", 0, "ACTIVE"},
            {5L, "OTHER", "WH", "C1", 0, "ACTIVE"},
            {6L, "TENANT", "WH", "C1", 1, "ACTIVE"},
            {7L, "TENANT", "WH", "C1", 0, "INACTIVE"},
            {8L, "TENANT", "CQ", "C3", 0, "ACTIVE"}
        };
        for (var r : records)
            db.update(
                    "INSERT INTO crm_customer VALUES(?,?,?,?,?,?,?,?,?,?)",
                    r[0],
                    r[1],
                    r[2],
                    r[3],
                    "客户" + r[0],
                    "联系人",
                    "13800000000",
                    "收货地址",
                    r[4],
                    r[5]);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), data));
        config.addMapper(InternalCustomerMapper.class);
        session = new MybatisSqlSessionFactoryBuilder().build(config).openSession();
        mapper =
                mock(
                        InternalCustomerMapper.class,
                        AdditionalAnswers.delegatesTo(
                                session.getMapper(InternalCustomerMapper.class)));
        repository = new MybatisPlusCostCustomerReferenceRepository(mapper);
    }

    @AfterEach
    void close() {
        if (session != null) session.close();
        if (data != null) data.destroy();
    }

    @Test
    void oneQueryPreservesTenantAndCityCodePairsAndExcludesUnavailableCustomers() {
        var requested = new LinkedHashMap<String, List<String>>();
        requested.put("WH", List.of("C1"));
        requested.put("BJ", List.of("C2"));
        requested.put("EMPTY", List.of("C9"));
        var result = repository.customersByCities("TENANT", requested);
        assertThat(result.get("WH")).extracting(x -> x.id()).containsExactly("1");
        assertThat(result.get("BJ")).extracting(x -> x.id()).containsExactly("2");
        assertThat(result.get("EMPTY")).isEmpty();
        assertThat(result.get("WH").getFirst().contactName()).isEqualTo("联系人");
        assertThat(result.get("WH").getFirst().phone()).isEqualTo("13800000000");
        assertThat(result.get("WH").getFirst().address()).isEqualTo("收货地址");
        verify(mapper, times(1)).selectList(any(Wrapper.class));
    }

    @Test
    void emptyBatchDoesNotQueryAndQuotedCodesRemainParameters() {
        assertThat(repository.customersByCities("TENANT", Map.of())).isEmpty();
        verifyNoInteractions(mapper);
        var result =
                repository.customersByCities("TENANT", Map.of("WH", List.of("C1') OR 1=1 --")));
        assertThat(result.get("WH")).isEmpty();
        verify(mapper, times(1)).selectList(any(Wrapper.class));
    }
}
