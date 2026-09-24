package com.rigour.tenant.iam.infrastructure.persistence.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec.encode;

import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.tenant.iam.application.port.out.PasswordHasher;
import com.rigour.tenant.iam.application.service.auth.SelfPasswordService;
import com.rigour.tenant.iam.infrastructure.security.session.PasswordAuthenticationProperties;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcSelfPasswordStoreTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    JdbcTemplate jdbc;
    SelfPasswordService service;
    PasswordHasher passwords;
    UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), other = UUID.randomUUID();

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        for (String table : new String[]{"iam_auth_session","iam_user_credential","iam_user","iam_tenant"})
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute("CREATE TABLE iam_tenant(id BINARY(16) PRIMARY KEY,status VARCHAR(20),deleted_at DATETIME(6))");
        jdbc.execute("CREATE TABLE iam_user(id BINARY(16) PRIMARY KEY,tenant_id BINARY(16),status VARCHAR(20),deleted_at DATETIME(6),security_version BIGINT DEFAULT 0,version BIGINT DEFAULT 0,updated_at DATETIME(6),updated_by BINARY(16))");
        jdbc.execute("CREATE TABLE iam_user_credential(tenant_id BINARY(16),user_id BINARY(16),credential_type VARCHAR(20),status VARCHAR(20),password_hash VARCHAR(255),algorithm VARCHAR(20),algorithm_version INT,failed_attempts INT DEFAULT 0,last_failed_at DATETIME(6),locked_until DATETIME(6),password_changed_at DATETIME(6),updated_at DATETIME(6),version BIGINT DEFAULT 0,PRIMARY KEY(tenant_id,user_id,credential_type))");
        jdbc.execute("CREATE TABLE iam_auth_session(id BINARY(16) PRIMARY KEY,tenant_id BINARY(16),principal_id BINARY(16),principal_scope VARCHAR(20),status VARCHAR(20),revoked_at DATETIME(6),revoke_reason VARCHAR(50),version BIGINT DEFAULT 0)");
        jdbc.update("INSERT INTO iam_tenant(id,status) VALUES(?,'ACTIVE')",encode(tenant));
        for (UUID id : new UUID[]{user,other}) {
            jdbc.update("INSERT INTO iam_user(id,tenant_id,status) VALUES(?,?,'ACTIVE')",encode(id),encode(tenant));
            jdbc.update("INSERT INTO iam_user_credential(tenant_id,user_id,credential_type,status,password_hash) VALUES(?,?,'PASSWORD','ACTIVE','old-hash')",encode(tenant),encode(id));
            jdbc.update("INSERT INTO iam_auth_session(id,tenant_id,principal_id,principal_scope,status) VALUES(?,?,?,'TENANT','ACTIVE')",encode(UUID.randomUUID()),encode(tenant),encode(id));
        }
        passwords=mock(PasswordHasher.class);
        when(passwords.matches("Old!1234","old-hash")).thenReturn(true);
        when(passwords.hash("New!5678")).thenReturn("new-hash");
        service=new SelfPasswordService(new JdbcSelfPasswordStore(jdbc,new DataSourceTransactionManager(ds),passwords,new PasswordAuthenticationProperties()));
    }

    @Test void changesOnlyOwnPasswordAndRevokesOwnSessions() {
        service.change("TENANT",tenant,user,"Old!1234","New!5678");
        assertThat(hash(user)).isEqualTo("new-hash");
        assertThat(hash(other)).isEqualTo("old-hash");
        assertThat(jdbc.queryForObject("SELECT security_version FROM iam_user WHERE id=?",Long.class,encode(user))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM iam_auth_session WHERE principal_id=?",String.class,encode(user))).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("SELECT status FROM iam_auth_session WHERE principal_id=?",String.class,encode(other))).isEqualTo("ACTIVE");
    }

    @Test void wrongPasswordCountsPersistAndLockAfterRepeatedFailures() {
        for(int i=0;i<5;i++) assertThatThrownBy(() -> service.change("TENANT",tenant,user,"wrong","New!5678")).isInstanceOf(RequestValidationException.class);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM iam_user_credential WHERE user_id=?",Integer.class,encode(user))).isEqualTo(5);
        assertThatThrownBy(() -> service.change("TENANT",tenant,user,"Old!1234","New!5678")).hasMessageContaining("锁定");
        assertThat(hash(user)).isEqualTo("old-hash");
        verify(passwords,never()).hash(any());
    }

    @Test void wrongTenantAndDisabledUserCannotChangePassword() {
        assertThatThrownBy(() -> service.change("TENANT",UUID.randomUUID(),user,"Old!1234","New!5678")).isInstanceOf(RequestValidationException.class);
        jdbc.update("UPDATE iam_user SET status='DISABLED' WHERE id=?",encode(user));
        assertThatThrownBy(() -> service.change("TENANT",tenant,user,"Old!1234","New!5678")).isInstanceOf(RequestValidationException.class);
        assertThat(hash(user)).isEqualTo("old-hash");
    }

    @Test void weakSameAndNonTenantRequestsNeverReachPersistence() {
        assertThatThrownBy(() -> service.change("TENANT",tenant,user,"Old!1234","12345678")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(() -> service.change("TENANT",tenant,user,"Old!1234","Old!1234")).hasMessageContaining("相同");
        assertThatThrownBy(() -> service.change("PLATFORM",null,user,"Old!1234","New!5678")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(passwords);
    }

    @Test void failureDuringSessionRevocationRollsBackPasswordChange() {
        jdbc.execute("DROP TABLE iam_auth_session");
        assertThatThrownBy(() -> service.change("TENANT",tenant,user,"Old!1234","New!5678")).isInstanceOf(RuntimeException.class);
        assertThat(hash(user)).isEqualTo("old-hash");
        assertThat(jdbc.queryForObject("SELECT security_version FROM iam_user WHERE id=?",Long.class,encode(user))).isZero();
    }

    private String hash(UUID id) { return jdbc.queryForObject("SELECT password_hash FROM iam_user_credential WHERE user_id=?",String.class,encode(id)); }
}
