package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.erp.api.v1.model.ErpCostQuery;
import com.rigour.erp.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** 执行真实 MyBatis 查询，验证字段交集及权限；H2 仅补足查询使用的 JSON 函数。 */
public class ErpCostFilterTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    MybatisPlusErpCostRepository store;

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:cost_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        String ddl =
                Files.readString(
                        Path.of("src/main/resources/db/migration/V37__erp_cost_management.sql"));
        ddl = ddl.substring(ddl.indexOf("CREATE TABLE erp_cost_entry ("));
        ddl =
                ddl.substring(0, ddl.indexOf(';') + 1)
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "")
                        .replace(" JSON ", " LONGTEXT ");
        jdbc.execute(ddl);
        for (String name : List.of("extract", "unquote", "search")) {
            String function =
                    name.equals("extract")
                            ? "JSON_EXTRACT"
                            : name.equals("unquote") ? "JSON_UNQUOTE" : "JSON_SEARCH";
            jdbc.execute(
                    "CREATE ALIAS "
                            + function
                            + " FOR 'com.rigour.erp.infrastructure.persistence.repository.ErpCostFilterTest."
                            + name
                            + "'");
        }
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ErpCostMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var scope = mock(ErpWarehouseDataScope.class);
        when(scope.predicate(anyString(), any(String[].class)))
                .thenReturn(new ErpWarehouseDataScope.Predicate("warehouse_id=?", List.of(7L)));
        store =
                new MybatisPlusErpCostRepository(
                        session.getMapper(ErpCostMapper.class),
                        mock(ErpCostAuditMapper.class),
                        JSON,
                        scope,
                        mock(com.rigour.erp.application.port.out.CostReferenceSource.class),
                        mock(ErpCostReferenceMapper.class));
        insert("good", "T1", "DOC-A", 7L);
        insert("other", "T2", "DOC-A", 7L);
        insert("hidden", "T1", "DOC-H", 8L);
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    void insert(String id, String tenant, String document, Object owner) {
        jdbc.update(
                "INSERT INTO"
                    + " erp_cost_entry(id,tenant_id,request_id,document_no,title,type_code,city_code,cost_date,amount,payment_status_code,status_code,data_json,created_by,updated_by,category_code,warehouse_id,sources_json)"
                    + " VALUES(?,?,?,?,'差旅住宿','TRAVEL','CITY','2026-10-01',100,'PAID','CONFIRMED',?,'U','U','RENTAL',?,"
                    + " '[{\"documentNo\":\"SRC-A\",\"senderName\":\"SENDER-ONLY\"}]')",
                id,
                tenant,
                id,
                document,
                JSON.writeValueAsString(
                        Map.of(
                                "remark",
                                "发票100%",
                                "title",
                                "差旅住宿",
                                "rental",
                                Map.of("contractNo", "LEASE-10"))),
                owner);
    }

    long count(String document, String title, String remark) {
        return count(document, title, remark, null);
    }

    long count(String document, String title, String remark, String contractNo) {
        var filter =
                new ErpCostQuery(
                        0,
                        20,
                        document,
                        title,
                        remark,
                        contractNo,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        var result = store.entries("T1", "RENTAL", filter, "cost:read");
        assertThat(result.items()).hasSize((int) result.total());
        return result.total();
    }

    @Test
    void independentFieldsIntersectWithinTenantAndScope() {
        assertThat(count(null, null, null)).isEqualTo(1);
        assertThat(count(" DOC- ", "住宿", "发票")).isEqualTo(1);
        assertThat(count("住宿", null, null)).isZero();
        assertThat(count(null, "DOC-", null)).isZero();
        assertThat(count("DOC-A", "不匹配", null)).isZero();
    }

    @Test
    void remarksSearchTheirOwnJsonFieldAndTreatWildcardCharactersLiterally() {
        assertThat(count(null, null, "100%")).isEqualTo(1);
        assertThat(count(null, null, "住宿")).isZero();
        assertThat(count("%", null, null)).isZero();
    }

    @Test
    void rentalContractSearchDoesNotMatchOtherFields() {
        assertThat(count(null, null, null, "LEASE-")).isEqualTo(1);
        assertThat(count(null, null, null, "住宿")).isZero();
        assertThat(count(null, "不匹配", null, "LEASE-")).isZero();
    }

    public static String extract(String text, String path) {
        Object value = JSON.readValue(text, Object.class);
        for (String key : path.substring(2).split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) return null;
            value = map.get(key);
        }
        return value == null ? null : JSON.writeValueAsString(value);
    }

    public static String unquote(String value) {
        return value == null ? null : JSON.readValue(value, String.class);
    }

    public static String search(String text, String mode, String like, String escape, String path) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < like.length(); i++) {
            char c = like.charAt(i);
            if (c == '\\' && i + 1 < like.length())
                regex.append(Pattern.quote(String.valueOf(like.charAt(++i))));
            else regex.append(c == '%' ? ".*" : c == '_' ? "." : Pattern.quote(String.valueOf(c)));
        }
        for (Object item : JSON.readValue(text, List.class)) {
            if (item instanceof Map<?, ?> map
                    && String.valueOf(map.get("documentNo")).matches(regex.toString()))
                return "match";
        }
        return null;
    }
}
