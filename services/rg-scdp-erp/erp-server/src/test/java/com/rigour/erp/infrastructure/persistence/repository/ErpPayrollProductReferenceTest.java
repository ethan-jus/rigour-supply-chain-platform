package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.erp.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

class ErpPayrollProductReferenceTest {
    MybatisPlusErpPayrollProductRepository store;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:products_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute(
                "CREATE TABLE erp_product_category(id BIGINT,tenant_id VARCHAR(64),parent_id"
                    + " BIGINT,category_code VARCHAR(64),category_name VARCHAR(100),category_level"
                    + " INT,ordinal INT,remark VARCHAR(50),revision INT,created_by"
                    + " VARCHAR(50),created_time TIMESTAMP,updated_by VARCHAR(50),updated_time"
                    + " TIMESTAMP,deleted INT)");
        jdbc.execute(
                "CREATE TABLE erp_product(id BIGINT,tenant_id VARCHAR(64),product_code"
                        + " VARCHAR(64),product_name VARCHAR(100),category_id BIGINT,deleted"
                        + " INT,submit_status_code VARCHAR(32))");
        jdbc.execute(
                "CREATE TABLE erp_product_variant(id BIGINT,tenant_id VARCHAR(64),product_id"
                        + " BIGINT,variant_code VARCHAR(64),specification_snapshot"
                        + " VARCHAR(100),unit_code VARCHAR(64),deleted INT)");
        jdbc.update(
                "INSERT INTO"
                    + " erp_product_category(id,tenant_id,parent_id,category_name,ordinal,deleted)"
                    + " VALUES(1,'T1',NULL,'台球周边',0,0),(2,'T1',1,'台呢',1,0),(3,'T1',NULL,'快消品',2,0),(4,'T1',NULL,'已删除',3,1),(5,'T2',NULL,'其他租户',1,0)");
        jdbc.update(
                "INSERT INTO erp_product"
                    + " VALUES(10,'T1','P10','华彩',2,0,'SUBMITTED'),(20,'T1','P20','泡面',3,0,'SUBMITTED'),(30,'T1','P30','草稿',2,0,'DRAFT'),(40,'T2','P40','其他租户',5,0,'SUBMITTED')");
        jdbc.update(
                "INSERT INTO erp_product_variant"
                    + " VALUES(100,'T1',10,'SKU100','A600','SET',0),(101,'T1',10,'SKU101','A300','SET',0),(102,'T1',10,'SKU102','A800','SET',1),(200,'T1',20,'SKU200','桶','EACH',0),(300,'T1',30,'SKU300','草稿','SET',0),(400,'T2',40,'SKU400','A600','SET',0)");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ErpPayrollProductMapper.class);
        config.addMapper(InternalProductCategoryMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        store =
                new MybatisPlusErpPayrollProductRepository(
                        session.getMapper(ErpPayrollProductMapper.class),
                        session.getMapper(InternalProductCategoryMapper.class));
    }

    @Test
    void filtersRealCategoryAndDescendantsBeforeLimitAndSearchesModel() {
        assertThat(store.products("T1", "", null, 1L))
                .extracting(p -> p.productVariantId())
                .containsExactly(100L, 101L);
        assertThat(store.products("T1", "A600", null, 1L))
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.categoryId()).isEqualTo(2L);
                            assertThat(p.categoryName()).isEqualTo("台呢");
                            assertThat(p.unitCode()).isEqualTo("SET");
                        });
        assertThat(store.products("T1", "A600", null, 3L)).isEmpty();
    }

    @Test
    void isolatedTenantsDeletedRowsAndWrongCategoryNeverLeak() {
        assertThat(store.productsByVariants("T1", java.util.List.of(100L, 102L, 400L)))
                .extracting(p -> p.productVariantId())
                .containsExactly(100L);
        assertThat(store.categories("T1")).extracting(c -> c.id()).containsExactly(1L, 2L, 3L);
        assertThat(store.products("T1", "", 400L, null)).isEmpty();
        assertThat(store.products("T1", "", null, 5L)).isEmpty();
        assertThat(store.products("T1", "", 100L, 3L)).isEmpty();
    }
}
