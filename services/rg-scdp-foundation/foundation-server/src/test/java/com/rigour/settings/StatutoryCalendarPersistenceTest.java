package com.rigour.settings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.settings.application.port.out.*;
import com.rigour.settings.application.service.StatutoryCalendarService;
import com.rigour.settings.domain.calendar.*;
import com.rigour.settings.infrastructure.persistence.mapper.*;
import com.rigour.settings.infrastructure.persistence.repository.MybatisPlusStatutoryCalendarRepository;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

class StatutoryCalendarPersistenceTest {
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    MybatisPlusStatutoryCalendarRepository store;
    final StatutoryCalendarSource source = mock(StatutoryCalendarSource.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
    final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:calendar_" + UUID.randomUUID() + ";MODE=MySQL",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        String migration =
                new String(
                                getClass()
                                        .getResourceAsStream(
                                                "/db/migration/V43__statutory_calendar_snapshots.sql")
                                        .readAllBytes(),
                                StandardCharsets.UTF_8)
                        .replaceAll("(?m)^--.*$", "")
                        .replace("JSON", "CLOB")
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "");
        for (String statement : migration.split(";"))
            if (!statement.isBlank()) jdbc.execute(statement);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(StatutoryCalendarYearMapper.class);
        config.addMapper(StatutoryCalendarHistoryMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var raw =
                new MybatisPlusStatutoryCalendarRepository(
                        session.getMapper(StatutoryCalendarYearMapper.class),
                        session.getMapper(StatutoryCalendarHistoryMapper.class),
                        json);
        var proxy = new ProxyFactory(raw);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new AnnotationTransactionAttributeSource()));
        store = (MybatisPlusStatutoryCalendarRepository) proxy.getProxy();
        when(source.fetch(2025)).thenReturn(fixture(2025));
        when(source.fetch(2026)).thenReturn(fixture(2026));
        when(source.fetch(2027)).thenReturn(fixture(2027));
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    StatutorySourceYear fixture(int year) throws Exception {
        return json.readValue(
                getClass().getResourceAsStream("/calendar/" + year + ".json"),
                StatutorySourceYear.class);
    }

    StatutoryCalendarService service(Clock clock) {
        return new StatutoryCalendarService(store, source, clock, json);
    }

    @Test
    void historicalAndCurrentYearsArePersistedAndMakeupDaysAreWorking() {
        var current = service(clock).year(2026);
        assertThat(current.status()).isEqualTo("AVAILABLE");
        assertThat(current.days()).hasSize(365);
        assertThat(
                        current.days().stream()
                                .filter(
                                        d ->
                                                d.date().toString().startsWith("2026-10")
                                                        && d.workday()))
                .hasSize(18);
        assertThat(
                        current.days().stream()
                                .filter(d -> d.date().equals(LocalDate.of(2026, 10, 10)))
                                .findFirst()
                                .orElseThrow()
                                .type())
                .isEqualTo("MAKEUP");
        var old = service(clock).year(2025);
        assertThat(
                        old.days().stream()
                                .filter(d -> d.date().equals(LocalDate.of(2025, 10, 8)))
                                .findFirst()
                                .orElseThrow()
                                .workday())
                .isFalse();
        assertThat(service(clock).year(2027).status()).isEqualTo("NOT_PUBLISHED");
        assertThat(service(clock).year(2027).days()).isEmpty();
        verify(source, times(1)).fetch(2026);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM settings_statutory_calendar_history",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void failedRefreshRetainsTheLastSuccessfulDatabaseSnapshot() {
        var before = service(clock).year(2026);
        when(source.fetch(2026)).thenThrow(new IllegalStateException("offline"));
        var after = service(Clock.offset(clock, Duration.ofHours(7))).year(2026);
        assertThat(after.status()).isEqualTo("AVAILABLE");
        assertThat(after.stale()).isTrue();
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.days()).isEqualTo(before.days());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM settings_statutory_calendar_history",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void futurePublicationAndItsPreviousDecemberDatesBecomeAvailableWithoutCodeChanges()
            throws Exception {
        var initial = service(clock).year(2026);
        assertThat(service(clock).year(2027).days()).isEmpty();
        var days = new ArrayList<StatutorySourceDay>();
        for (var day : fixture(2026).days())
            days.add(new StatutorySourceDay(day.name(), day.date().plusYears(1), day.isOffDay()));
        days.add(new StatutorySourceDay("测试跨年安排", LocalDate.of(2026, 12, 31), true));
        when(source.fetch(2027))
                .thenReturn(
                        new StatutorySourceYear(
                                2027, List.of("https://www.gov.cn/test/notice-2027"), days));
        var updated = service(Clock.offset(clock, Duration.ofHours(7)));
        updated.refreshTrackedYears();
        assertThat(updated.year(2027).status()).isEqualTo("AVAILABLE");
        var annual = updated.year(2026);
        assertThat(
                        annual.days().stream()
                                .filter(d -> d.date().equals(LocalDate.of(2026, 12, 31)))
                                .findFirst()
                                .orElseThrow()
                                .workday())
                .isFalse();
        assertThat(annual.version()).isNotEqualTo(initial.version());
        when(source.fetch(2027)).thenThrow(new IllegalStateException("next year mirror offline"));
        var retained = service(Clock.offset(clock, Duration.ofHours(14))).year(2026);
        assertThat(retained.stale()).isTrue();
        assertThat(retained.version()).isEqualTo(annual.version());
        assertThat(retained.days()).isEqualTo(annual.days());
    }

    @Test
    void malformedOrUntrustedDataNeverReplacesPublishedData() throws Exception {
        var good = fixture(2026);
        assertThatThrownBy(() -> StatutoryCalendarService.validate(2025, good))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                StatutoryCalendarService.validate(
                                        2026,
                                        new StatutorySourceYear(
                                                2026,
                                                List.of("https://gov.cn.evil.example/notice"),
                                                good.days())))
                .isInstanceOf(IllegalArgumentException.class);
        var duplicate = new ArrayList<>(good.days());
        duplicate.add(duplicate.getFirst());
        assertThatThrownBy(
                        () ->
                                StatutoryCalendarService.validate(
                                        2026,
                                        new StatutorySourceYear(2026, good.papers(), duplicate)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                StatutoryCalendarService.validate(
                                        2026,
                                        new StatutorySourceYear(
                                                2026, good.papers(), good.days().subList(0, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyOneInstanceCanPublishEachLeaseAndStaleWorkersCannotOverwrite() throws Exception {
        assertThat(store.acquire(2026, "first", clock.instant())).isTrue();
        assertThat(store.acquire(2026, "second", clock.instant())).isFalse();
        store.complete(2026, "wrong-token", fixture(2026), "wrong", clock.instant());
        assertThat(store.find(2026).orElseThrow().content()).isNull();
        store.complete(2026, "first", fixture(2026), "verified", clock.instant());
        assertThat(store.find(2026).orElseThrow().version()).isEqualTo("verified");
    }
}
