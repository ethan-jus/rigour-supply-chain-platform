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
        jdbc.execute("ALTER TABLE erp_cost_entry ADD COLUMN cost_month VARCHAR(7)");

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
        jdbc.execute("ALTER TABLE erp_cost_entry ADD COLUMN source_system VARCHAR(20)");
        jdbc.execute("ALTER TABLE erp_cost_entry ADD COLUMN premises_type VARCHAR(20)");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ErpCostMapper.class);
        config.addMapper(ErpCostReferenceMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var scope = mock(ErpWarehouseDataScope.class);
        when(scope.predicate(anyString(), any(String[].class)))
                .thenReturn(new ErpWarehouseDataScope.Predicate("warehouse_id=?", List.of(7L)));
        when(scope.manualPredicate(anyString(), anyString()))
                .thenReturn(new ErpWarehouseDataScope.Predicate("created_by=?", List.of("U")));
        store =
                new MybatisPlusErpCostRepository(
                        session.getMapper(ErpCostMapper.class),
                        mock(ErpCostAuditMapper.class),
                        JSON,
                        scope,
                        mock(com.rigour.erp.application.port.out.CostReferenceSource.class),
                        session.getMapper(ErpCostReferenceMapper.class));
        insert("good", "T1", "DOC-A", 7L);
        insert("other", "T2", "DOC-A", 7L);
        insert("hidden", "T1", "DOC-H", 8L);
        jdbc.update("UPDATE erp_cost_entry SET source_warehouse_id=warehouse_id");
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
    void variantSearchMatchesSpecificationWithinTheSelectedProductAndTenant() {
        jdbc.execute(
                "CREATE TABLE erp_product(id BIGINT, tenant_id VARCHAR(40), product_name"
                        + " VARCHAR(200), deleted INT)");
        jdbc.execute(
                "CREATE TABLE erp_product_variant(id BIGINT, tenant_id VARCHAR(40), product_id"
                        + " BIGINT, variant_code VARCHAR(40), specification_snapshot VARCHAR(500),"
                        + " unit_code VARCHAR(40), deleted INT)");
        jdbc.update(
                "INSERT INTO erp_product VALUES (1,'T1','皮头',0),(2,'T1','其他商品',0),"
                        + "(1,'T2','其他租户商品',0),(3,'T1','已删商品',1)");
        jdbc.update(
                "INSERT INTO erp_product_variant VALUES"
                        + " (10,'T1',1,'SKU-10','H','PIECE',0),"
                        + " (11,'T1',1,'SKU-11','M','BOX',0),"
                        + " (12,'T1',1,'SKU-12',NULL,'PIECE',0),"
                        + " (13,'T1',2,'SKU-13','H','PIECE',0),"
                        + " (14,'T2',1,'SKU-14','H','PIECE',0),"
                        + " (15,'T1',1,'SKU-15','H','PIECE',1),"
                        + " (16,'T1',3,'SKU-16','H','PIECE',0)");
        assertThat(store.references("T1", "variants", "H", null, 1L, 0, "cost:read"))
                .extracting(com.rigour.erp.api.v1.model.ErpCostReferenceView::id)
                .containsExactly("10");
        assertThat(store.references("T1", "variants", "SKU-11", null, 1L, 0, "cost:read"))
                .extracting(com.rigour.erp.api.v1.model.ErpCostReferenceView::specification)
                .containsExactly("M");
        assertThat(store.references("T1", "variants", "皮头", null, 1L, 0, "cost:read"))
                .extracting(com.rigour.erp.api.v1.model.ErpCostReferenceView::id)
                .containsExactly("10", "11", "12");
        assertThat(store.references("T1", "variants", "", null, 1L, 1, "cost:read"))
                .extracting(com.rigour.erp.api.v1.model.ErpCostReferenceView::id)
                .containsExactly("11", "12");
        assertThat(store.references("T1", "variants", "H", null, 3L, 0, "cost:read")).isEmpty();
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

    @Test
    void manualRowsStayCreatorScopedAndMissingAmountsAreCounted() {
        insert("manual", "T1", "MANUAL", null);
        insert("notmine", "T1", "NOTMINE", null);
        jdbc.update("UPDATE erp_cost_entry SET created_by='ANOTHER' WHERE id='notmine'");
        jdbc.update("UPDATE erp_cost_entry SET amount=NULL,status_code='DRAFT' WHERE id='manual'");
        var result =
                store.entries(
                        "T1",
                        "RENTAL",
                        new ErpCostQuery(
                                0, 20, null, null, null, null, null, null, null, null, null, null,
                                null, null),
                        "cost:read");
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.missingAmountCount()).isEqualTo(1);
        assertThat(result.registeredAmount()).isEqualByComparingTo("100");
    }

    @Test
    void sourceNumberSearchIncludesLinkedOriginalNumberAndManualEvidence() {
        jdbc.update(
                "UPDATE erp_cost_entry SET sources_json=? WHERE id='good'",
                "[{\"documentNo\":\"LOCAL-1\",\"originalNo\":\"0000123\"}]");
        var query =
                JSON.readValue(
                        "{\"begin\":0,\"step\":20,\"sourceDocumentNo\":\"0000123\"}",
                        ErpCostQuery.class);
        assertThat(store.entries("T1", "RENTAL", query, "cost:read").total()).isEqualTo(1);
        jdbc.update(
                "UPDATE erp_cost_entry SET sources_json='[]',data_json=? WHERE id='good'",
                "{\"externalDocuments\":[{\"documentNo\":\"0000123\",\"sourceSystem\":\"DHB\"}]}");
        assertThat(store.entries("T1", "RENTAL", query, "cost:read").total()).isEqualTo(1);
    }

    @Test
    void businessPartyAndLossFiltersStayIndependentAndIntersect() {
        jdbc.update(
                "UPDATE erp_cost_entry SET data_json=? WHERE id='good'",
                "{\"goods\":{\"supplierName\":\"供货甲\",\"lossReason\":\"卸货破损\"},\"ownerName\":\"经办乙\",\"payeeName\":\"合作丙100%\",\"rental\":{\"landlordName\":\"出租丁\"}}");
        for (var filter :
                Map.of(
                                "supplierName",
                                "供货甲",
                                "lossReason",
                                "卸货破损",
                                "ownerName",
                                "经办乙",
                                "payeeName",
                                "100%",
                                "landlordName",
                                "出租丁")
                        .entrySet()) {
            var query =
                    JSON.readValue(
                            JSON.writeValueAsString(
                                    Map.of(
                                            "begin",
                                            0,
                                            "step",
                                            20,
                                            filter.getKey(),
                                            filter.getValue())),
                            ErpCostQuery.class);
            assertThat(store.entries("T1", "RENTAL", query, "cost:read").total()).isEqualTo(1);
        }
        var wrongField =
                JSON.readValue(
                        "{\"begin\":0,\"step\":20,\"payeeName\":\"供货甲\"}", ErpCostQuery.class);
        assertThat(store.entries("T1", "RENTAL", wrongField, "cost:read").total()).isZero();
        var intersection =
                JSON.readValue(
                        "{\"begin\":0,\"step\":20,\"supplierName\":\"供货甲\",\"ownerName\":\"不匹配\"}",
                        ErpCostQuery.class);
        assertThat(store.entries("T1", "RENTAL", intersection, "cost:read").total()).isZero();
    }

    @Test
    void premisesNameFilterDoesNotMatchTheLandlordAndKeepsLegacyWarehouseNames() {
        jdbc.update(
                "UPDATE erp_cost_entry SET data_json=?, warehouse_name=? WHERE id='good'",
                "{\"rental\":{\"premisesName\":\"中心仓甲\",\"landlordName\":\"出租方乙\"}}",
                "历史仓甲");
        for (String name : List.of("中心仓甲", "历史仓甲")) {
            var query =
                    JSON.readValue(
                            JSON.writeValueAsString(
                                    Map.of("begin", 0, "step", 20, "objectName", name)),
                            ErpCostQuery.class);
            assertThat(store.entries("T1", "RENTAL", query, "cost:read").total()).isEqualTo(1);
        }
        var query =
                JSON.readValue(
                        "{\"begin\":0,\"step\":20,\"objectName\":\"出租方乙\"}", ErpCostQuery.class);
        assertThat(store.entries("T1", "RENTAL", query, "cost:read").total()).isZero();
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

    /** H2 仅补足 MySQL JSON_SEARCH 使用的路径子集；不是生产查询实现。 */
    public static String search(
            String text, String mode, String like, String escape, String... paths) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < like.length(); i++) {
            char c = like.charAt(i);
            if (c == '\\' && i + 1 < like.length())
                regex.append(Pattern.quote(String.valueOf(like.charAt(++i))));
            else regex.append(c == '%' ? ".*" : c == '_' ? "." : Pattern.quote(String.valueOf(c)));
        }
        if (text == null) return null;
        Object root = JSON.readValue(text, Object.class);
        for (String path : paths) {
            List<Object> values = new ArrayList<>();
            values.add(root);
            for (String part : path.substring(1).replaceFirst("^\\.", "").split("\\.")) {
                List<Object> next = new ArrayList<>();
                String key = part.replace("[*]", "");
                for (Object value : values) {
                    Object field =
                            key.isEmpty()
                                    ? value
                                    : value instanceof Map<?, ?> map ? map.get(key) : null;
                    if (part.endsWith("[*]") && field instanceof List<?> list) next.addAll(list);
                    else if (field != null) next.add(field);
                }
                values = next;
            }
            if (values.stream()
                    .anyMatch(v -> v instanceof String && v.toString().matches(regex.toString())))
                return "match";
        }
        return null;
    }
}
