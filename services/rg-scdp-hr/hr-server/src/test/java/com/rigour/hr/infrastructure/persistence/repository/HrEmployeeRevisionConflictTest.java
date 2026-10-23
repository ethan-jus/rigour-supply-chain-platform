package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.HrEmployeeCommand;
import com.rigour.hr.api.v1.model.HrDepartmentCommand;
import com.rigour.hr.application.port.out.HrAuditActorNameResolver;
import com.rigour.hr.infrastructure.persistence.mapper.HrEmployeeMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

class HrEmployeeRevisionConflictTest {
    @Test
    void staleDepartmentUpdateAndDeleteReturnConflictAndRetainDepartment() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:department_revision_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            jdbc.execute("CREATE TABLE hr_organization_state(tenant_id VARCHAR(50) PRIMARY KEY,version BIGINT DEFAULT 0)");
            jdbc.execute("CREATE TABLE hr_employee(tenant_id VARCHAR(50),employee_code VARCHAR(50),employee_name VARCHAR(50),deleted INT)");
            jdbc.execute("CREATE TABLE hr_department(id BIGINT,tenant_id VARCHAR(50),parent_id BIGINT,"
                    + "department_code VARCHAR(50),department_name VARCHAR(50),sort_order INT,status_code VARCHAR(20),"
                    + "revision INT,leader_employee_code VARCHAR(50),contact_phone VARCHAR(50),established_date DATE,"
                    + "created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,created_by VARCHAR(50),created_by_name VARCHAR(50),"
                    + "updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_by VARCHAR(50),updated_by_name VARCHAR(50),deleted INT)");
            jdbc.update("INSERT INTO hr_department(id,tenant_id,department_code,department_name,sort_order,status_code,revision,deleted)"
                    + " VALUES(26,'T','D26','运营部',0,'ACTIVE',3,0)");
            var names = mock(HrAuditActorNameResolver.class);
            when(names.resolve("T", "HR")).thenReturn("李四");
            var store = new JdbcHrOrganizationStore(jdbc, new DataSourceTransactionManager(dataSource),
                    mock(HrDataScope.class), names, mock(HrEmployeeMapper.class));
            assertThatThrownBy(() -> store.saveDepartment("T", 26L,
                    new HrDepartmentCommand(null, "旧窗口部门名", 0, "ACTIVE", 2, null, null, null), "HR"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                    .hasMessage("部门已修改，请刷新");
            assertThatThrownBy(() -> store.deleteDepartment("T", 26L, 2, "HR"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                    .hasMessage("部门已修改，请刷新");
            assertThat(jdbc.queryForObject("SELECT department_name FROM hr_department WHERE id=26", String.class))
                    .isEqualTo("运营部");
            assertThat(jdbc.queryForObject("SELECT revision FROM hr_department WHERE id=26", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT deleted FROM hr_department WHERE id=26", Integer.class)).isZero();
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void staleEmployeeRevisionReturnsBusinessConflictAndLeavesEmployeeUnchanged() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:employee_revision_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            jdbc.execute("CREATE TABLE hr_organization_state(tenant_id VARCHAR(50) PRIMARY KEY,version BIGINT DEFAULT 0)");
            jdbc.execute("CREATE TABLE hr_employee(id BIGINT,tenant_id VARCHAR(50),employee_code VARCHAR(50),"
                    + "employee_name VARCHAR(50),employment_status VARCHAR(20),department_id BIGINT,"
                    + "primary_position_code VARCHAR(50),revision INT,access_version BIGINT,entry_date TIMESTAMP,"
                    + "leave_date TIMESTAMP,deleted INT)");
            jdbc.execute("CREATE TABLE hr_department(id BIGINT,tenant_id VARCHAR(50),department_name VARCHAR(50),status_code VARCHAR(20),deleted INT)");
            jdbc.execute("CREATE TABLE hr_position(tenant_id VARCHAR(50),position_code VARCHAR(50),position_name VARCHAR(50),status_code VARCHAR(20),deleted INT)");
            jdbc.execute("CREATE TABLE hr_department_closure(tenant_id VARCHAR(50),descendant_id BIGINT,ancestor_id BIGINT,depth INT)");
            jdbc.update("INSERT INTO hr_employee VALUES(7,'T','E7','钱七','ACTIVE',26,'HRBP',3,0,'2026-09-01',NULL,0)");
            jdbc.update("INSERT INTO hr_department VALUES(26,'T','运营部','ACTIVE',0)");
            jdbc.update("INSERT INTO hr_position VALUES('T','HRBP','HRBP','ACTIVE',0)");
            jdbc.update("INSERT INTO hr_department_closure VALUES('T',26,26,0)");
            var names = mock(HrAuditActorNameResolver.class);
            when(names.resolve("T", "HR")).thenReturn("李四");
            var mapper = mock(HrEmployeeMapper.class);
            var store = new JdbcHrOrganizationStore(jdbc, new DataSourceTransactionManager(dataSource),
                    mock(HrDataScope.class), names, mapper);
            var stale = new HrEmployeeCommand("旧窗口姓名", 26L, "HRBP", "ACTIVE", null, null,
                    null, null, null, 2, null, null, false, null, "HEADQUARTERS", null);
            assertThatThrownBy(() -> store.saveEmployee("T", 7L, stale, "HR"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.CONFLICT))
                    .hasMessage("员工已修改，请刷新");
            assertThat(jdbc.queryForObject("SELECT employee_name FROM hr_employee WHERE id=7", String.class))
                    .isEqualTo("钱七");
            assertThat(jdbc.queryForObject("SELECT revision FROM hr_employee WHERE id=7", Integer.class)).isEqualTo(3);
            verifyNoInteractions(mapper);
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException(error);
        }
    }
}
