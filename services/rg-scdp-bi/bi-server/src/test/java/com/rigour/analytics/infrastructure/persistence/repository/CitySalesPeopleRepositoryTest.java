package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class CitySalesPeopleRepositoryTest {
    @Test void scopesRosterAndRetainsFormerStaffWithHistoricalReceipts() {
        var ds = new SingleConnectionDataSource("jdbc:h2:mem:city_people_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE bi_employee_dim(tenant_id VARCHAR,employee_code VARCHAR,employee_name VARCHAR,employment_status VARCHAR,department_id BIGINT,department_path VARCHAR)");
            jdbc.execute("CREATE TABLE bi_sales_contact_city_dim(tenant_id VARCHAR,region_code VARCHAR,department_id BIGINT)");
            jdbc.execute("CREATE TABLE bi_sales_order_fact(tenant_id VARCHAR,owner_staff_code VARCHAR,region_code VARCHAR,deleted INT,order_status_code VARCHAR,order_date TIMESTAMP)");
            jdbc.execute("CREATE TABLE bi_sales_payment_fact(tenant_id VARCHAR,owner_staff_code VARCHAR,region_code VARCHAR,deleted INT,payment_time TIMESTAMP)");
            jdbc.update("INSERT INTO bi_sales_contact_city_dim VALUES('T','HZ',7),('T','BJ',8)");
            jdbc.update("INSERT INTO bi_employee_dim VALUES('T','A','在职零业绩','ACTIVE',7,'[7,5]'),('T','L','离职','LEFT',7,'[7,5]'),('T','CHILD','下属部门','ACTIVE',70,'[70,7,5]'),('T','PAID','调离后到账','LEFT',8,'[8,5]'),('T','OUT','其他城市','ACTIVE',8,'[8,5]'),('T','SIMILAR','非同部门','ACTIVE',17,'[17,5]'),('OTHER','X','其他租户','ACTIVE',7,'[7,5]')");
            jdbc.update("INSERT INTO bi_sales_payment_fact VALUES('T','PAID','HZ',0,'2026-08-10 00:00:00'),('T','OUT','HZ',0,'2026-07-10 00:00:00')");
            var config = new Configuration(); config.addMapper(SupplyDashboardQueryMapper.class);
            var args = new HashMap<String,Object>(); args.put("tenantId","T"); args.put("regionCode","HZ"); args.put("ownerStaffCode",null);
            args.put("from", LocalDateTime.parse("2026-08-01T00:00:00")); args.put("to",LocalDateTime.parse("2026-08-31T23:59:59"));
            var rows = query(config,jdbc,args);
            assertThat(rows.stream().map(r -> r.get("ownerstaffcode"))).containsExactly("A","CHILD","L","PAID");
            assertThat(rows.stream().filter(r -> "L".equals(r.get("ownerstaffcode"))).findFirst().orElseThrow().get("employmentstatus")).isEqualTo("LEFT");
            args.put("ownerStaffCode","A"); assertThat(query(config,jdbc,args)).hasSize(1);
            args.put("regionCode",null); assertThat(query(config,jdbc,args)).isEmpty();
        } finally { ds.destroy(); }
    }
    @Test void businessSyncProjectsEmploymentStatusWithoutVisitService() {
        var repository = new MybatisPlusSupplyDashboardRepository(org.mockito.Mockito.mock(SupplyDashboardQueryMapper.class));
        var sources = org.mockito.Mockito.mock(com.rigour.analytics.infrastructure.persistence.scope.BiSourceSnapshotProjector.class);
        var employees = org.mockito.Mockito.mock(com.rigour.analytics.application.port.out.EmployeeAnalyticsStore.class);
        var authority = org.mockito.Mockito.mock(com.rigour.analytics.infrastructure.persistence.scope.BiAuthorityProjector.class);
        var products = org.mockito.Mockito.mock(com.rigour.analytics.infrastructure.persistence.scope.BiDashboardProductProjector.class);
        org.springframework.test.util.ReflectionTestUtils.setField(repository, "sources", sources);
        org.springframework.test.util.ReflectionTestUtils.setField(repository, "employeeAnalytics", employees);
        org.springframework.test.util.ReflectionTestUtils.setField(repository, "authority", authority);
        org.springframework.test.util.ReflectionTestUtils.setField(repository, "dashboardProducts", products);
        var tenant = UUID.randomUUID();
        repository.synchronizeSourceSnapshots(tenant.toString());
        var order = org.mockito.Mockito.inOrder(sources, employees, authority);
        order.verify(sources).refresh(tenant);
        order.verify(employees).refresh(org.mockito.ArgumentMatchers.eq(tenant.toString()), org.mockito.ArgumentMatchers.any());
        order.verify(authority).refresh(tenant);
    }

    private static List<Map<String,Object>> query(Configuration config,JdbcTemplate jdbc,Map<String,Object> parameters) {
        var bound = config.getMappedStatement(SupplyDashboardQueryMapper.class.getName()+".citySalesPeople").getBoundSql(parameters);
        return jdbc.queryForList(bound.getSql(),bound.getParameterMappings().stream().map(p -> parameters.get(p.getProperty())).toArray());
    }
}
