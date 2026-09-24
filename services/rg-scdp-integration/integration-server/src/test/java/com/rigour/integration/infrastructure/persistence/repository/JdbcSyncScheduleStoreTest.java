package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.scheduling.*;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
class JdbcSyncScheduleStoreTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    static JdbcSyncScheduleStore store;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    String tenant = UUID.randomUUID().toString(),
            key = UUID.randomUUID().toString(),
            actor = UUID.randomUUID().toString();
    Instant now = Instant.parse("2026-09-23T08:00:00Z");

    @BeforeAll
    static void setup() {
        var ds =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V27__sync_schedule.sql"))
                .execute(ds);
        jdbc = new JdbcTemplate(ds);
        store = new JdbcSyncScheduleStore(jdbc);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    ScheduleView save(boolean enabled, int minutes, long version) {
        return tx.execute(
                s ->
                        store.save(
                                tenant,
                                key,
                                new SaveScheduleCommand(
                                        new ScheduleSettings(enabled, "FIXED_DELAY", minutes, null),
                                        version),
                                actor,
                                now));
    }

    void finish(String token, String status, Instant at) {
        tx.executeWithoutResult(s -> store.finish(tenant, key, token, status, "test", at));
    }

    @Test
    void draftDoesNotRunAndTenantCannotReadOrOverwriteAnotherPlan() {
        assertThat(save(false, 60, 0).nextRunAt()).isNull();
        assertThat(store.find(UUID.randomUUID().toString(), key)).isEmpty();
        assertThat(
                        store.claim(
                                UUID.randomUUID().toString(),
                                key,
                                1,
                                UUID.randomUUID().toString(),
                                now.plusSeconds(3600)))
                .isFalse();
        assertThat(store.due(now.plusSeconds(3600))).noneMatch(e -> e.tenant().equals(tenant));
    }

    @Test
    void versionConflictPreservesConfigurationAndAudit() {
        save(true, 60, 0);
        save(true, 30, 1);
        assertThatThrownBy(() -> save(false, 5, 1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> save(false, 5, 0)).isInstanceOf(BusinessException.class);
        assertThat(store.find(tenant, key).orElseThrow().settings().intervalMinutes())
                .isEqualTo(30);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM integration_sync_schedule_audit WHERE"
                                    + " tenant_id=?",
                                Long.class,
                                tenant))
                .isEqualTo(2);
    }

    @Test
    void auditFailureRollsBackConfiguration() {
        save(true, 60, 0);
        jdbc.update(
                "INSERT INTO"
                    + " integration_sync_schedule_audit(tenant_id,scope_key,version,actor_id,changed_at,enabled,mode,interval_minutes)"
                    + " VALUES(?,?,2,?,UTC_TIMESTAMP(),1,'FIXED_DELAY',30)",
                tenant,
                key,
                actor);
        assertThatThrownBy(() -> save(false, 30, 1)).isInstanceOf(RuntimeException.class);
        assertThat(store.find(tenant, key).orElseThrow().version()).isEqualTo(1);
        assertThat(store.find(tenant, key).orElseThrow().settings().enabled()).isTrue();
    }

    @Test
    void concurrentWorkersClaimOnlyOnce() throws Exception {
        save(true, 5, 0);
        assertThat(store.claim(tenant, key, 1, UUID.randomUUID().toString(), now)).isFalse();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var work = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 6; i++)
                work.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return store.claim(
                                            tenant,
                                            key,
                                            1,
                                            UUID.randomUUID().toString(),
                                            now.plusSeconds(300));
                                }));
            gate.countDown();
            int claimed = 0;
            for (var w : work) if (w.get(10, TimeUnit.SECONDS)) claimed++;
            assertThat(claimed).isEqualTo(1);
        }
    }

    @Test
    void changesDuringExecutionApplyAfterCompletionAndWrongTokenCannotFinish() {
        save(true, 5, 0);
        String token = UUID.randomUUID().toString();
        store.claim(tenant, key, 1, token, now.plusSeconds(300));
        assertThat(save(true, 30, 1).nextRunAt()).isNull();
        finish(UUID.randomUUID().toString(), "SUCCEEDED", now.plusSeconds(600));
        assertThat(store.find(tenant, key).orElseThrow().runningJobId()).isEqualTo(token);
        finish(token, "SUCCEEDED", now.plusSeconds(600));
        var plan = store.find(tenant, key).orElseThrow();
        assertThat(plan.nextRunAt()).isEqualTo(now.plusSeconds(2400));
        assertThat(plan.runningJobId()).isNull();
    }

    @Test
    void unknownNeverReleasesSlotAndLateReceiptMaySafelyFinishIt() {
        save(true, 5, 0);
        String token = UUID.randomUUID().toString();
        store.claim(tenant, key, 1, token, now.plusSeconds(300));
        finish(token, "UNKNOWN", now.plusSeconds(500));
        var plan = store.find(tenant, key).orElseThrow();
        assertThat(plan.runningJobId()).isEqualTo(token);
        assertThat(plan.nextRunAt()).isNull();
        assertThat(plan.lastFinishedAt()).isNull();
        assertThat(store.claim(tenant, key, 1, UUID.randomUUID().toString(), now.plusSeconds(1000)))
                .isFalse();
        save(false, 5, 1);
        finish(token, "SUCCEEDED", now.plusSeconds(600));
        assertThat(store.find(tenant, key).orElseThrow().nextRunAt()).isNull();
    }

    @Test
    void utcDatabaseRoundTripIgnoresJvmTimezone() {
        var original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            assertThat(save(true, 60, 0).nextRunAt()).isEqualTo(now.plusSeconds(3600));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void dailyUsesBeijingAndNeverImmediatelyRerunsSameMinute() {
        var daily = new ScheduleSettings(true, "DAILY", null, "02:00");
        assertThat(daily.nextAfter(Instant.parse("2026-09-23T17:59:59Z")))
                .isEqualTo(Instant.parse("2026-09-23T18:00:00Z"));
        assertThat(daily.nextAfter(Instant.parse("2026-09-23T18:00:00Z")))
                .isEqualTo(Instant.parse("2026-09-24T18:00:00Z"));
        assertThat(new ScheduleSettings(false, "DAILY", null, "02:00").nextAfter(now)).isNull();
    }

    @Test
    void invalidSchedulesCannotBePersisted() {
        assertThatThrownBy(() -> new ScheduleSettings(true, "FIXED_DELAY", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleSettings(true, "FIXED_DELAY", 1441, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleSettings(true, "DAILY", null, "24:01"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SaveScheduleCommand(
                                        new ScheduleSettings(false, "DAILY", null, "01:00"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
