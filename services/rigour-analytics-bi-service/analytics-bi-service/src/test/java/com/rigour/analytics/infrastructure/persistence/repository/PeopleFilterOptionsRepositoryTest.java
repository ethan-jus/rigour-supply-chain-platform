package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.infrastructure.persistence.mapper.BiDataScopeMapper;
import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import java.util.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 无订单 HR 员工必须可筛选，授权城市和本人范围不能泄漏其他档案。 */
class PeopleFilterOptionsRepositoryTest {
    @Test void includesZeroOrderEmployeesWithinTenantCityAndSelf() {
        var ds = new SingleConnectionDataSource("jdbc:h2:mem:people_filters_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE bi_sales_order_fact (tenant_id VARCHAR, deleted INT, order_status_code VARCHAR, region_code VARCHAR, region_name VARCHAR, owner_staff_code VARCHAR, owner_staff_name VARCHAR, customer_type_code VARCHAR, customer_type_name VARCHAR, source_system_code VARCHAR)");
            jdbc.execute("CREATE TABLE bi_customer_dim AS SELECT *, CAST(NULL AS VARCHAR) status_code FROM bi_sales_order_fact");
            jdbc.execute("CREATE TABLE bi_sales_order_line_fact (tenant_id VARCHAR, deleted INT, order_status_code VARCHAR, region_code VARCHAR, owner_staff_code VARCHAR, product_category_id BIGINT, product_category_name VARCHAR, product_category_code VARCHAR)");
            jdbc.execute("CREATE TABLE bi_employee_dim (tenant_id VARCHAR, employee_code VARCHAR, employee_name VARCHAR, region_code VARCHAR, city_name VARCHAR)");
            jdbc.execute("CREATE TABLE bi_sales_contact_fact (tenant_id VARCHAR, owner_staff_code VARCHAR, region_code VARCHAR, city_name VARCHAR)");
            jdbc.execute("CREATE TABLE bi_city_cost_record (tenant_id VARCHAR, region_code VARCHAR, region_name VARCHAR, deleted INT)");
            jdbc.update("INSERT INTO bi_employee_dim VALUES ('T','E1','员工一','BJ','北京'),('T','E2','员工二','SH','上海'),('OTHER','E3','其他租户','BJ','北京')");
            jdbc.update("INSERT INTO bi_sales_contact_fact VALUES ('T','E4','BJ','北京')");
            var config = new Configuration();config.addMapper(BiDataScopeMapper.class);config.addMapper(SupplyDashboardQueryMapper.class);
            var args = new HashMap<String,Object>(); args.put("tenantId","T");args.put("regions",List.of("BJ"));args.put("ownerStaffCode",null);
            var tenant = query(config,jdbc,SupplyDashboardQueryMapper.class,"salesOwnerOptions",args);
            assertThat(tenant.stream().map(row -> row.get("optionvalue"))).containsExactlyInAnyOrder("E1","E2","E4");
            assertThat(query(config,jdbc,SupplyDashboardQueryMapper.class,"regionOptions",args)).hasSize(2);
            var city = query(config,jdbc,BiDataScopeMapper.class,"filterOptions",args);
            assertThat(city.stream().filter(row -> "SALES_OWNER".equals(row.get("optiontype"))).map(row -> row.get("optionvalue"))).containsExactlyInAnyOrder("E1","E4");
            args.put("ownerStaffCode","E1");
            var self = query(config,jdbc,BiDataScopeMapper.class,"filterOptions",args);
            assertThat(self.stream().filter(row -> "SALES_OWNER".equals(row.get("optiontype"))).map(row -> row.get("optionvalue"))).containsExactly("E1");
        } finally { ds.destroy(); }
    }
    private static List<Map<String,Object>> query(Configuration config,JdbcTemplate jdbc,Class<?> mapper,String method,Map<String,Object> parameters) {
        var bound = config.getMappedStatement(mapper.getName()+"."+method).getBoundSql(parameters);
        var args = bound.getParameterMappings().stream().map(p -> bound.hasAdditionalParameter(p.getProperty()) ? bound.getAdditionalParameter(p.getProperty()) : parameters.get(p.getProperty())).toArray();
        return jdbc.queryForList(bound.getSql(),args);
    }
}
