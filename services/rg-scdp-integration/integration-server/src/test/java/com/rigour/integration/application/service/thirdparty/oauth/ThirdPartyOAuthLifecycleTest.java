package com.rigour.integration.application.service.thirdparty.oauth;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.application.port.out.thirdparty.oauth.*;
import com.rigour.integration.domain.thirdparty.oauth.*;
import com.rigour.integration.infrastructure.thirdparty.oauth.*;
import com.rigour.shared.context.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 实际SQL和事务验证，覆盖公共平台复用、一次性回调和刷新竞争。 */
class ThirdPartyOAuthLifecycleTest {
    UUID tenant = UUID.randomUUID(), actor = UUID.randomUUID(), connector = UUID.randomUUID();
    ObjectMapper json = new ObjectMapper();
    JdbcTemplate jdbc;
    JdbcThirdPartyTokenStore tokens;
    JdbcThirdPartyAuthorizationSessionStore sessions;
    DataSourceTransactionManager manager;
    ThirdPartyAuthorizationService auth;
    ThirdPartyTokenService tokenService;
    Fake first = new Fake("HUOLALA"), second = new Fake("SECOND");

    class Fake implements ThirdPartyOAuthProvider {
        String platform, app = "fixture-app", fingerprint = "f";
        volatile boolean proof = true;
        RuntimeException exchangeFailure, verifyFailure, refreshFailure;
        java.util.concurrent.atomic.AtomicInteger
                exchanges = new java.util.concurrent.atomic.AtomicInteger(),
                refreshes = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch entered, release;
        OAuthToken response =
                new OAuthToken(
                        " access+raw/ ",
                        " refresh+raw/ ",
                        Instant.now().plusSeconds(3600),
                        "bearer",
                        null,
                        Map.of("expires_in", 3600));

        Fake(String platform) {
            this.platform = platform;
        }

        public String platformCode() {
            return platform;
        }

        public String permissionPrefix() {
            return "integration:huolala";
        }

        public OAuthConfiguration configuration(UUID t, UUID c) {
            return new OAuthConfiguration(
                    t,
                    platform,
                    c,
                    app,
                    URI.create("http://localhost:26882/api/v1/integration/huolala/oauth/callback"),
                    fingerprint);
        }

        public URI authorizationUri(OAuthConfiguration c) {
            return URI.create("https://open.huolala.cn/#/oauth/authorize?response_type=code");
        }

        public OAuthToken exchange(OAuthConfiguration c, String code) {
            exchanges.incrementAndGet();
            if (exchangeFailure != null) throw exchangeFailure;
            return response;
        }

        public OAuthToken refresh(OAuthConfiguration c, OAuthToken token) {
            refreshes.incrementAndGet();
            if (entered != null) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            if (refreshFailure != null) throw refreshFailure;
            return response;
        }

        public boolean verifySubject(OAuthConfiguration c, OAuthToken token) {
            if (verifyFailure != null) throw verifyFailure;
            return proof;
        }
    }

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        String sql =
                new String(
                        getClass()
                                .getResourceAsStream(
                                        "/db/migration/V31__thirdparty_backend_oauth.sql")
                                .readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        for (String name :
                List.of("integration_oauth_token", "integration_oauth_authorization_session")) {
            int start = sql.indexOf("CREATE TABLE " + name + " (");
            int end = sql.indexOf(") ENGINE=", start);
            jdbc.execute(sql.substring(start, end + 1).replaceAll("(?i)\\bJSON\\b", "LONGTEXT"));
        }
        tokens = new JdbcThirdPartyTokenStore(jdbc, json);
        sessions = new JdbcThirdPartyAuthorizationSessionStore(jdbc, json);
        var registry = new ThirdPartyProviderRegistry(List.of(first, second));
        auth = new ThirdPartyAuthorizationService(registry, tokens, sessions, manager);
        tokenService = new ThirdPartyTokenService(registry, tokens, sessions, manager);
        setActor(tenant);
    }

    @AfterEach
    void cleanup() throws Exception {
        setIdentity(null);
    }

    void setActor(UUID tenant) throws Exception {
        setIdentity(
                new CallerIdentity(
                        "TENANT",
                        actor,
                        tenant,
                        actor,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of(),
                        Set.of("integration:huolala:read", "integration:huolala:write")));
    }

    void setIdentity(CallerIdentity a) throws Exception {
        var m =
                AuthorizationContext.class.getDeclaredMethod(
                        a == null ? "clear" : "set",
                        a == null ? new Class<?>[] {} : new Class<?>[] {CallerIdentity.class});
        m.setAccessible(true);
        if (a == null) m.invoke(null);
        else m.invoke(null, a);
    }

    record Browser(ThirdPartyAuthorizationService.Start start, String cookie) {}

    Browser browser(String platform) {
        var start = auth.create(platform, connector);
        String ticket = start.url().substring(start.url().indexOf("ticket=") + 7);
        return new Browser(start, auth.open(platform, ticket).cookieValue());
    }

    ThirdPartyAuthorizationService.Status finish(Browser b) {
        return auth.callback("HUOLALA", b.cookie(), "fixture-code");
    }

    ThirdPartyTokenStore.Stored current() {
        return tokens.find(tenant, "HUOLALA", connector).orElseThrow();
    }

    void makeDue() {
        jdbc.update(
                "UPDATE integration_oauth_token SET next_refresh_at=?,access_token_expires_at=?",
                java.time.LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
                java.time.LocalDateTime.now(ZoneOffset.UTC).plusSeconds(120));
    }

    @Test
    void callbackSavesExactValuesInPublicTableAndReplayDoesNotExchangeAgain() {
        var b = browser("HUOLALA");
        assertThat(finish(b).status()).isEqualTo("SUCCEEDED");
        assertThat(current().token()).isEqualTo(first.response);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT access_token FROM integration_oauth_token", String.class))
                .isEqualTo(" access+raw/ ");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT refresh_token FROM integration_oauth_token", String.class))
                .isEqualTo(" refresh+raw/ ");
        assertThat(finish(b).status()).isEqualTo("SUCCEEDED");
        assertThat(first.exchanges).hasValue(1);
        assertThat(sessions.find(b.start().id()).orElseThrow().candidate()).isNull();
        assertThat(json.writeValueAsString(auth.status("HUOLALA", b.start().id())))
                .doesNotContain("raw", "access_token", "refresh_token");
        assertThat(current().toString()).doesNotContain("raw");
    }

    @Test
    void directConsoleCallbackAndAnotherBrowserCannotSelectTenant() {
        assertThatThrownBy(() -> auth.callback("HUOLALA", null, "code")).hasMessageContaining("会话");
        var b = browser("HUOLALA");
        assertThatThrownBy(
                        () ->
                                auth.callback(
                                        "HUOLALA",
                                        b.start().id() + "." + OAuthSupport.nonce(),
                                        "code"))
                .hasMessageContaining("浏览器");
        assertThat(first.exchanges).hasValue(0);
    }

    @Test
    void statusAndCancellationAreTenantAndActorBound() throws Exception {
        var b = browser("HUOLALA");
        setActor(UUID.randomUUID());
        assertThatThrownBy(() -> auth.status("HUOLALA", b.start().id()))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(() -> auth.cancel("HUOLALA", b.start().id()))
                .isInstanceOf(AuthorizationDeniedException.class);
        setIdentity(null);
        assertThatThrownBy(() -> auth.create("HUOLALA", connector))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void newSessionSupersedesOldOneAndStartTicketIsSingleUse() {
        var first = browser("HUOLALA");
        var next = browser("HUOLALA");
        assertThat(finish(first).status()).isEqualTo("SUPERSEDED");
        assertThatThrownBy(() -> auth.open("HUOLALA", next.start().url().split("ticket=")[1]))
                .hasMessageContaining("已使用");
        assertThat(finish(next).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void noEnterpriseProofKeepsPreviousAuthorizedTokenAndCandidateIsRetried() {
        finish(browser("HUOLALA"));
        var old = current().token();
        first.proof = false;
        first.response = new OAuthToken("new", "refresh-new", Instant.now().plusSeconds(3600));
        var b = browser("HUOLALA");
        assertThat(finish(b).status()).isEqualTo("PENDING_VERIFICATION");
        assertThat(current().token()).isEqualTo(old);
        assertThat(sessions.find(b.start().id()).orElseThrow().candidate()).isNotNull();
        first.proof = true;
        auth.recover();
        assertThat(current().token().accessToken()).isEqualTo("new");
        assertThat(auth.status("HUOLALA", b.start().id()).status()).isEqualTo("SUCCEEDED");
        assertThat(sessions.find(b.start().id()).orElseThrow().candidate()).isNull();
    }

    @Test
    void mismatchedEnterpriseRejectsAndClearsCandidate() {
        first.verifyFailure = new OAuthFailure(OAuthFailure.Kind.REAUTHORIZE, "MISMATCH", "wrong");
        var b = browser("HUOLALA");
        assertThat(finish(b).status()).isEqualTo("FAILED");
        assertThat(current().authStatus()).isEqualTo("UNAUTHORIZED");
        assertThat(sessions.find(b.start().id()).orElseThrow().candidate()).isNull();
    }

    @Test
    void ambiguousExchangeNeverReusesAuthorizationCode() {
        first.exchangeFailure = new OAuthFailure(OAuthFailure.Kind.UNCERTAIN, "NETWORK", "network");
        var b = browser("HUOLALA");
        assertThat(finish(b).status()).isEqualTo("UNCERTAIN");
        assertThat(finish(b).status()).isEqualTo("UNCERTAIN");
        assertThat(first.exchanges).hasValue(1);
    }

    @Test
    void knownRejectedResponseIsDiagnosableAndPreservesPreviousAuthorization() {
        finish(browser("HUOLALA"));
        var old = current().token();
        first.exchangeFailure =
                new OAuthFailure(
                        OAuthFailure.Kind.REAUTHORIZE,
                        "TOKEN_EXPIRY_INVALID",
                        "sensitive-response-must-not-leak");
        var b = browser("HUOLALA");
        var result = finish(b);
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(sessions.find(b.start().id()).orElseThrow().errorCode())
                .isEqualTo("TOKEN_EXPIRY_INVALID");
        assertThat(json.writeValueAsString(result))
                .doesNotContain("sensitive-response-must-not-leak");
        assertThat(current().token()).isEqualTo(old);
        assertThat(current().authStatus()).isEqualTo("AUTHORIZED");
        finish(b);
        assertThat(first.exchanges).hasValue(2);
    }

    @Test
    void configurationChangeRequiresNewSession() {
        var b = browser("HUOLALA");
        first.fingerprint = "changed";
        assertThatThrownBy(() -> finish(b)).hasMessageContaining("配置已变更");
        assertThat(first.exchanges).hasValue(0);
    }

    @Test
    void expiredCandidateIsClearedAndCannotAuthorize() {
        first.proof = false;
        var b = browser("HUOLALA");
        finish(b);
        jdbc.update(
                "UPDATE integration_oauth_authorization_session SET expires_at=?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        auth.recover();
        assertThat(auth.status("HUOLALA", b.start().id()).status()).isEqualTo("EXPIRED");
        assertThat(sessions.find(b.start().id()).orElseThrow().candidate()).isNull();
        assertThat(current().token().accessToken()).isNull();
    }

    @Test
    void secondPlatformUsesSameServicesWithoutMixingCredentials() {
        var b = browser("SECOND");
        assertThat(auth.callback("SECOND", b.cookie(), "code").status()).isEqualTo("SUCCEEDED");
        assertThat(tokens.find(tenant, "HUOLALA", connector)).isEmpty();
        assertThat(tokens.find(tenant, "SECOND", connector)).isPresent();
        assertThat(first.exchanges).hasValue(0);
        assertThat(second.exchanges).hasValue(1);
        assertThatThrownBy(() -> auth.create("unregistered", connector))
                .hasMessageContaining("尚未配置");
    }

    @Test
    void refreshRotatesBothRawTokensAndNeverClearsValidTokensOnTemporaryFailure() {
        finish(browser("HUOLALA"));
        makeDue();
        var old = current().token();
        first.refreshFailure = new OAuthFailure(OAuthFailure.Kind.TEMPORARY, "RATE_LIMIT", "retry");
        assertThat(tokenService.valid(tenant, "HUOLALA", connector)).isEqualTo(old);
        assertThat(current().authStatus()).isEqualTo("AUTHORIZED");
        assertThat(current().failureCount()).isEqualTo(1);
        first.refreshFailure = null;
        first.response =
                new OAuthToken(" rotated ", " rotated-refresh ", Instant.now().plusSeconds(3600));
        makeDue();
        assertThat(tokenService.valid(tenant, "HUOLALA", connector)).isEqualTo(first.response);
        assertThat(current().refreshLeaseId()).isNull();
    }

    @Test
    void uncertainRotatingRefreshRequiresReauthorizationWithoutErasingLastTokens() {
        finish(browser("HUOLALA"));
        makeDue();
        var old = current().token();
        first.refreshFailure =
                new OAuthFailure(OAuthFailure.Kind.UNCERTAIN, "NETWORK", "uncertain");
        assertThatThrownBy(() -> tokenService.valid(tenant, "HUOLALA", connector))
                .hasMessageContaining("重新授权");
        assertThat(current().token()).isEqualTo(old);
        assertThat(current().authStatus()).isEqualTo("REAUTHORIZE_REQUIRED");
    }

    @Test
    void refreshLeaseBlocksCompetingRefreshAndNewAuthorization() throws Exception {
        finish(browser("HUOLALA"));
        makeDue();
        first.entered = new CountDownLatch(1);
        first.release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var job = executor.submit(() -> tokenService.valid(tenant, "HUOLALA", connector));
            assertThat(first.entered.await(3, TimeUnit.SECONDS)).isTrue();
            tokenService.valid(tenant, "HUOLALA", connector);
            assertThat(first.refreshes).hasValue(1);
            assertThatThrownBy(() -> auth.create("HUOLALA", connector)).hasMessageContaining("处理中");
            first.release.countDown();
            job.get(5, TimeUnit.SECONDS);
            assertThat(current().refreshLeaseId()).isNull();
        } finally {
            first.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void staleResponseCannotOverwriteNewAuthorizationRevision() {
        finish(browser("HUOLALA"));
        var old = current();
        UUID lease = UUID.randomUUID();
        var tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(
                s -> tokens.claimRefresh(old, lease, Instant.now().plusSeconds(90)));
        var leased = current();
        jdbc.update(
                "UPDATE integration_oauth_token SET"
                        + " refresh_lease_id=NULL,refresh_lease_expires_at=NULL");
        first.response =
                new OAuthToken("new-authorized", "new-refresh", Instant.now().plusSeconds(3600));
        finish(browser("HUOLALA"));
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                tokens.activate(
                                                        leased,
                                                        first.app,
                                                        old.token(),
                                                        Instant.now(),
                                                        Instant.now().plusSeconds(200),
                                                        actor,
                                                        false)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(current().token().accessToken()).isEqualTo("new-authorized");
    }

    @Test
    void publicBackendCallbackRequiresOnlyBoundBrowserCookieAndNeverEchoesCredentials()
            throws Exception {
        var start = auth.create("HUOLALA", connector);
        String ticket = start.url().split("ticket=")[1];
        var controller =
                new com.rigour.integration.api.controller.thirdparty
                        .HuolalaAuthorizationCallbackController(auth);
        var mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                controller)
                        .build();
        setIdentity(null);
        var opened =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/api/v1/integration/huolala/oauth/start")
                                        .param("ticket", ticket))
                        .andReturn()
                        .getResponse();
        assertThat(opened.getStatus()).isEqualTo(302);
        String header = opened.getHeader("Set-Cookie");
        assertThat(header)
                .contains(
                        "HttpOnly",
                        "SameSite=Lax",
                        "Path=/api/v1/integration/huolala/oauth/callback")
                .doesNotContain("raw");
        String binding = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        var response =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/api/v1/integration/huolala/oauth/callback")
                                        .cookie(
                                                new jakarta.servlet.http.Cookie(
                                                        "rigour_huolala_authorization", binding))
                                        .param("code", "fixture-sensitive-code"))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("授权已完成")
                .doesNotContain("raw", "fixture-sensitive-code");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(current().authStatus()).isEqualTo("AUTHORIZED");
    }

    @Test
    void repositoriesCanBeClassProxiedAndWiredBySpring() {
        try (var context =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(ObjectMapper.class, () -> json);
            context.registerBean(
                    org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor
                            .class,
                    () -> {
                        var processor =
                                new org.springframework.dao.annotation
                                        .PersistenceExceptionTranslationPostProcessor();
                        processor.setProxyTargetClass(true);
                        return processor;
                    });
            context.register(
                    JdbcThirdPartyTokenStore.class, JdbcThirdPartyAuthorizationSessionStore.class);
            context.refresh();
            assertThat(
                            org.springframework.aop.support.AopUtils.isCglibProxy(
                                    context.getBean(ThirdPartyTokenStore.class)))
                    .isTrue();
            assertThat(
                            org.springframework.aop.support.AopUtils.isCglibProxy(
                                    context.getBean(ThirdPartyAuthorizationSessionStore.class)))
                    .isTrue();
        }
    }

    @Test
    void expiredAccessTokenIsNotReturnedAfterTemporaryRefreshFailure() {
        finish(browser("HUOLALA"));
        makeDue();
        jdbc.update(
                "UPDATE integration_oauth_token SET access_token_expires_at=?",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        first.refreshFailure = new OAuthFailure(OAuthFailure.Kind.TEMPORARY, "RATE_LIMIT", "retry");
        assertThatThrownBy(() -> tokenService.valid(tenant, "HUOLALA", connector))
                .hasMessageContaining("将重试");
        assertThat(current().authStatus()).isEqualTo("AUTHORIZED");
        assertThat(current().token().accessToken()).isEqualTo(first.response.accessToken());
    }

    @Test
    void expiredTerminalHistoryIsPurgedWithoutRemovingCurrentCredentialsOrLiveSessions() {
        var done = browser("HUOLALA");
        finish(done);
        jdbc.update(
                "UPDATE integration_oauth_authorization_session SET finished_at=?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(31));
        var live = auth.create("HUOLALA", connector);
        auth.recover();
        assertThat(sessions.find(done.start().id())).isEmpty();
        assertThat(sessions.find(live.id())).isPresent();
        assertThat(current().authStatus()).isEqualTo("AUTHORIZED");
    }
}
