package com.rigour.merchant.infrastructure.persistence.repository;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.containers.MySQLContainer;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
class CustomerPaymentOwnerHistoryTest {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 JdbcTemplate jdbc; JdbcCustomerPaymentOwnerStore store;
 @BeforeEach void setup() {
  jdbc=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));
  jdbc.execute("DROP TABLE IF EXISTS crm_customer_responsibility_history");
  jdbc.execute("DROP TABLE IF EXISTS crm_responsibility_source_conflict");
  jdbc.execute("CREATE TABLE crm_responsibility_source_conflict(tenant_id VARCHAR(36),customer_id BIGINT,status VARCHAR(16),created_at DATETIME(6))");
  jdbc.execute("CREATE TABLE crm_customer_responsibility_history(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(36),customer_id BIGINT,new_employee_code VARCHAR(50),new_employee_name VARCHAR(128),effective_at DATETIME(6),recorded_at DATETIME(6),effective_kind VARCHAR(16),change_type VARCHAR(32),new_department_id BIGINT,new_department_name VARCHAR(200),new_region_code VARCHAR(128))");
  store=new JdbcCustomerPaymentOwnerStore(jdbc);
 }
 void add(String code,String effective,String recorded,String kind) {jdbc.update("INSERT INTO crm_customer_responsibility_history(tenant_id,customer_id,new_employee_code,effective_at,recorded_at,effective_kind,change_type) VALUES('T',1,?,?,?,?,'OWNER')",code,effective,recorded,kind);}
 @Test void transferBoundaryAndUnknownHistoryAreNotInferredFromToday() {
  add("ZHANG","2026-10-01","2026-10-01","CONFIRMED");add("LI","2026-11-01","2026-11-05","CONFIRMED");
  assertThat(store.at("T",1,Instant.parse("2026-10-20T00:00:00Z")).employeeCode()).isEqualTo("ZHANG");
  assertThat(store.at("T",1,Instant.parse("2026-11-01T00:00:00Z")).employeeCode()).isEqualTo("LI");
  assertThat(store.at("T",1,Instant.parse("2026-09-20T00:00:00Z")).usable()).isFalse();
  assertThat(store.at("OTHER",1,Instant.parse("2026-11-02T00:00:00Z")).usable()).isFalse();
 }
 @Test void observedChangeCannotPretendToBeAConfirmedHandover() {
  add("ZHANG","2026-10-01","2026-10-01","CONFIRMED");add("LI","2026-11-05","2026-11-05","OBSERVED");
  assertThat(store.at("T",1,Instant.parse("2026-11-06T00:00:00Z")).usable()).isFalse();
  add("LI","2026-11-01","2026-11-09","CONFIRMED");
  assertThat(store.at("T",1,Instant.parse("2026-11-06T00:00:00Z")).employeeCode()).isEqualTo("LI");
 }
 @Test void sameEffectiveTimeIsAmbiguous() {
  add("ZHANG","2026-11-01","2026-11-01","CONFIRMED");add("LI","2026-11-01","2026-11-02","CONFIRMED");
  assertThat(store.at("T",1,Instant.parse("2026-11-06T00:00:00Z")).usable()).isFalse();
 }
 @Test void unresolvedSourceConflictCannotContinueAssigningNewPaymentsToOldOwner() {
  add("ZHANG","2026-10-01","2026-10-01","CONFIRMED");
  jdbc.update("INSERT INTO crm_responsibility_source_conflict VALUES('T',1,'PENDING','2026-11-01')");
  assertThat(store.at("T",1,Instant.parse("2026-10-20T00:00:00Z")).employeeCode()).isEqualTo("ZHANG");
  assertThat(store.at("T",1,Instant.parse("2026-11-06T00:00:00Z")).usable()).isFalse();
  jdbc.update("UPDATE crm_responsibility_source_conflict SET status='KEEP_LOCAL'");
  assertThat(store.at("T",1,Instant.parse("2026-11-06T00:00:00Z")).employeeCode()).isEqualTo("ZHANG");
 }
}
