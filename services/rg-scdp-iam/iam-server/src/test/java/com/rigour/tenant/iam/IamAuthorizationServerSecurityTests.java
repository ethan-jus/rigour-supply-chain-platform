package com.rigour.tenant.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.rigour.tenant.iam.application.port.out.PasswordHasher;
import com.rigour.tenant.iam.infrastructure.security.oidc.IamJwtCustomizer;
import com.rigour.tenant.iam.infrastructure.security.oidc.IamTokenClaimsResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import com.rigour.tenant.iam.infrastructure.security.oidc.IamRefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "rigour.iam.authentication.password.default-tenant-code=browser-scdp",
            "rigour.iam.authentication.native-app.enabled=true",
            "rigour.iam.authentication.native-app.allowed-client-ids[0]=rigour-sales-app-android",
            "spring.cloud.nacos.config.enabled=false",
            "spring.cloud.nacos.discovery.enabled=false"
        })
@AutoConfigureMockMvc
@Import(IamAuthorizationServerSecurityTests.TestSigningConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class IamAuthorizationServerSecurityTests {

    private static final String CLIENT_ID = "rigour-scdp-browser-test";
    private static final String REDIRECT_URI =
            "https://scdp.dev.rigour.local/login/oauth2/code/rigour-iam";
    private static final String USERNAME = "browser-scdp-admin";
    private static final String PASSWORD = "Browser-Test-Password-42!";
    private static final String NATIVE_CLIENT_ID = "rigour-sales-app-android";
    private static final String NATIVE_REDIRECT = "com.rigour.workbench://auth/callback";
    private static final String NATIVE_VERIFIER = "native-login-verifier-0123456789abcdefghijklmnopqrstuvwxyz";
    private static final Pattern CSRF_VALUE = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("rigour_iam")
                    .withUsername("rigour_iam_browser_test")
                    .withPassword("rigour_iam_browser_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
        registry.add("rigour.iam.oidc.server.enabled", () -> "true");
        registry.add("rigour.iam.oidc.server.issuer", () -> "https://iam.dev.rigour.local");
        registry.add(
                "rigour.iam.oidc.server.allowed-origins[0]",
                () -> "https://scdp.dev.rigour.local");
        registry.add("rigour.iam.oidc.authorization-attributes.enabled", () -> "true");
        registry.add("rigour.iam.oidc.authorization-attributes.active-key-version", () -> "v1");
        registry.add(
                "rigour.iam.oidc.authorization-attributes.keys-base64.v1",
                IamAuthorizationServerSecurityTests::authorizationEncryptionKey);
    }

    @Autowired private MockMvc mockMvc;

    @Autowired private RegisteredClientRepository registeredClientRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private PasswordHasher passwordHasher;

    @Autowired private JwtDecoder jwtDecoder;

    @Autowired private OAuth2AuthorizationService authorizationService;

    @BeforeEach
    void initializeClientAndUser() {
        registeredClientRepository.save(scdpClient());
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM iam_user WHERE username = ?",
                        Integer.class,
                        USERNAME);
        if (count != null && count == 0) {
            insertTenantUser();
        }
    }

    @Test
    void publishesDiscoveryAndPublicJwks() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration").secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("https://iam.dev.rigour.local"))
                .andExpect(jsonPath("$.authorization_endpoint").exists())
                .andExpect(jsonPath("$.token_endpoint").exists())
                .andExpect(jsonPath("$.jwks_uri").exists());
        mockMvc.perform(get("/oauth2/jwks").secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value(TestSigningConfiguration.KEY_ID))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString("\"d\""))));
        mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(
                                        "/oauth2/token")
                                .secure(true)
                                .header("Origin", "https://scdp.dev.rigour.local")
                                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Access-Control-Allow-Origin",
                                        "https://scdp.dev.rigour.local"));
    }

    @Test
    void rejectsPublicAuthorizationRequestWithoutPkce() throws Exception {
        String requestUri =
                "/oauth2/authorize?response_type=code&client_id="
                        + CLIENT_ID
                        + "&redirect_uri="
                        + REDIRECT_URI
                        + "&scope=openid%20profile&state=missing-pkce-state";
        mockMvc.perform(get(URI.create(requestUri)).secure(true))
                .andExpect(status().is3xxRedirection())
                .andExpect(
                        header().string(
                                        "Location",
                                        org.hamcrest.Matchers.containsString("error=")));
    }

    @Test
    void nativeProfileOnlyClientCompletesLoginPkceAndVerifiedIdentity() throws Exception {
        var properties = new com.rigour.tenant.iam.infrastructure.bootstrap.SalesAppClientBootstrapProperties();
        properties.setClientId("rigour-sales-app-android-dev");
        properties.setRedirectUri("uniappx://rigour-sales/auth/callback");
        properties.setAllowDebugBase(true);
        var bootstrap = new com.rigour.tenant.iam.infrastructure.bootstrap.SalesAppClientBootstrapCommand(
                registeredClientRepository, UUID::randomUUID, properties, true);
        bootstrap.run(new org.springframework.boot.DefaultApplicationArguments());
        bootstrap.run(new org.springframework.boot.DefaultApplicationArguments());
        String verifier = "native-mobile-pkce-verifier-long-enough-0123456789abcdef";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorize = "/oauth2/authorize?response_type=code&client_id=" + properties.getClientId()
                + "&redirect_uri=" + properties.getRedirectUri() + "&scope=profile&state=native-state"
                + "&code_challenge_method=S256&code_challenge=" + challenge;
        var start = mockMvc.perform(get(URI.create(authorize)).secure(true))
                .andExpect(redirectedUrl("/login")).andReturn();
        var session = (MockHttpSession) start.getRequest().getSession(false);
        var page = mockMvc.perform(get("/login").secure(true).session(session))
                .andExpect(status().isOk()).andReturn();
        var csrf = CSRF_VALUE.matcher(page.getResponse().getContentAsString());
        assertThat(csrf.find()).isTrue();
        mockMvc.perform(post("/login").secure(true).session(session).param("_csrf", csrf.group(1))
                        .param("tenantCode", "browser-scdp").param("username", USERNAME).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/oauth2/authorize")));
        var finish = mockMvc.perform(get(URI.create(authorize)).secure(true).session(session))
                .andExpect(status().is3xxRedirection()).andReturn();
        var callback = URI.create(finish.getResponse().getHeader("Location"));
        assertThat(callback.toString()).startsWith(properties.getRedirectUri() + "?");
        assertThat(queryParameter(callback.getRawQuery(), "state")).isEqualTo("native-state");
        var token = mockMvc.perform(post("/oauth2/token").secure(true)
                        .param("grant_type", "authorization_code").param("client_id", properties.getClientId())
                        .param("redirect_uri", properties.getRedirectUri())
                        .param("code", queryParameter(callback.getRawQuery(), "code")).param("code_verifier", verifier))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access_token").isString())
                .andExpect(jsonPath("$.refresh_token").isString())
                .andExpect(jsonPath("$.id_token").doesNotExist()).andReturn();
        var json = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(token.getResponse().getContentAsByteArray());
        mockMvc.perform(get("/api/v1/me").secure(true).header("Authorization", "Bearer " + json.get("access_token").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.principalScope").value("TENANT"))
                .andExpect(jsonPath("$.username").value(USERNAME));
        mockMvc.perform(get(URI.create(authorize.replace("/auth/callback", "/auth/other"))).secure(true).session(session))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void completesBrowserLoginPkceCodeAndTokenFlow() throws Exception {
        String verifier = "browser-pkce-verifier-that-is-long-enough-0123456789";
        String challenge =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorizationPath =
                "/oauth2/authorize?response_type=code&client_id="
                        + CLIENT_ID
                        + "&redirect_uri="
                        + REDIRECT_URI
                        + "&scope=openid%20profile&state=browser-state"
                        + "&code_challenge="
                        + challenge
                        + "&code_challenge_method=S256";

        MvcResult authorizationStart =
                mockMvc.perform(get(URI.create(authorizationPath)).secure(true))
                        .andExpect(status().is3xxRedirection())
                        .andExpect(redirectedUrl("/login"))
                        .andReturn();
        MockHttpSession session =
                (MockHttpSession) authorizationStart.getRequest().getSession(false);
        assertThat(session).isNotNull();

        MvcResult loginPage =
                mockMvc.perform(get("/login").secure(true).session(session))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string(
                                                "Content-Security-Policy",
                                                org.hamcrest.Matchers.containsString(
                                                        "frame-ancestors 'none'")))
                        .andExpect(
                                header().string(
                                                "Content-Security-Policy",
                                                org.hamcrest.Matchers.containsString(
                                                        "script-src 'nonce-")))
                        .andExpect(
                                header().string(
                                                "Content-Security-Policy",
                                                org.hamcrest.Matchers.containsString(
                                                        "form-action 'self'"
                                                            + " https://scdp.dev.rigour.local")))
                        .andExpect(
                                content()
                                        .string(
                                                org.hamcrest.Matchers.containsString(
                                                        "瑞盖供应链数字化平台")))
                        .andExpect(
                                content().string(org.hamcrest.Matchers.containsString("正在验证身份…")))
                        .andReturn();
        Matcher matcher = CSRF_VALUE.matcher(loginPage.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();
        String csrf = matcher.group(1);

        mockMvc.perform(
                        post("/login")
                                .secure(true)
                                .session(session)
                                .param("_csrf", csrf)
                                .param("tenantCode", "browser-scdp")
                                .param("username", USERNAME)
                                .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(
                        header().string(
                                        "Location",
                                        org.hamcrest.Matchers.containsString("/oauth2/authorize")));

        MvcResult authorizationFinish =
                mockMvc.perform(get(URI.create(authorizationPath)).secure(true).session(session))
                        .andExpect(status().is3xxRedirection())
                        .andExpect(
                                header().string(
                                                "Location",
                                                org.hamcrest.Matchers.startsWith(REDIRECT_URI)))
                        .andReturn();
        URI callback = URI.create(authorizationFinish.getResponse().getHeader("Location"));
        String code = queryParameter(callback.getRawQuery(), "code");
        assertThat(queryParameter(callback.getRawQuery(), "state")).isEqualTo("browser-state");

        MvcResult tokenResult =
                mockMvc.perform(
                                post("/oauth2/token")
                                        .secure(true)
                                        .param("grant_type", "authorization_code")
                                        .param("client_id", CLIENT_ID)
                                        .param("redirect_uri", REDIRECT_URI)
                                        .param("code", code)
                                        .param("code_verifier", verifier))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.access_token").isString())
                        .andExpect(jsonPath("$.id_token").isString())
                        .andExpect(jsonPath("$.refresh_token").isString())
                        .andReturn();
        var tokenJson =
                tools.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .readTree(tokenResult.getResponse().getContentAsByteArray());
        String accessToken = tokenJson.get("access_token").asString();
        String idToken = tokenJson.get("id_token").asString();
        assertThat(authorizationService.findByToken(idToken, new OAuth2TokenType("id_token")))
                .isNotNull();
        Jwt accessJwt = jwtDecoder.decode(accessToken);
        assertThat(accessJwt.getAudience()).contains("rigour-api");
        assertThat(accessJwt.getClaimAsString("principalScope")).isEqualTo("TENANT");
        assertThat(accessJwt.getClaimAsString("sessionId")).isNotBlank();
        assertThat(accessJwt.getClaimAsString("tokenUse")).isEqualTo("access");
        String refreshToken = tokenJson.get("refresh_token").asString();
        MvcResult refreshed = mockMvc.perform(post("/oauth2/token").secure(true)
                        .param("grant_type", "refresh_token").param("client_id", CLIENT_ID)
                        .param("refresh_token", refreshToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refresh_token").isString()).andReturn();
        var refreshedJson = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(refreshed.getResponse().getContentAsByteArray());
        String rotatedRefreshToken = refreshedJson.get("refresh_token").asString();
        assertThat(rotatedRefreshToken).isNotEqualTo(refreshToken);
        Jwt refreshedId = jwtDecoder.decode(refreshedJson.get("id_token").asString());
        Jwt originalId = jwtDecoder.decode(idToken);
        assertThat(refreshedId.getClaimAsString("sid")).isEqualTo(originalId.getClaimAsString("sid"));
        assertThat(refreshedId.getClaimAsInstant("auth_time")).isEqualTo(originalId.getClaimAsInstant("auth_time"));
        accessToken = refreshedJson.get("access_token").asString();
        assertThat(jwtDecoder.decode(accessToken).getClaimAsString("sessionId"))
                .isEqualTo(accessJwt.getClaimAsString("sessionId"));

        mockMvc.perform(
                        get("/api/v1/me")
                                .secure(true)
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.principalScope").value("TENANT"));
        mockMvc.perform(get("/api/v1/scdp/navigation").secure(true)
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
        for (String retired : java.util.List.of("/api/v1/portal/apps", "/api/v1/management/platform/applications", "/api/v1/management/tenant/users")) {
            mockMvc.perform(get(retired).secure(true).header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isNotFound());
        }

        String logoutPath =
                "/connect/logout?id_token_hint="
                        + idToken
                        + "&post_logout_redirect_uri=https%3A%2F%2Fscdp.dev.rigour.local%2F";
        mockMvc.perform(get(URI.create(logoutPath)).secure(true).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://scdp.dev.rigour.local/"))
                .andExpect(cookie().maxAge("RIGOUR_IAM_SESSION", 0));
        mockMvc.perform(
                        get("/api/v1/me")
                                .secure(true)
                                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/oauth2/token").secure(true)
                        .param("grant_type", "refresh_token").param("client_id", CLIENT_ID)
                        .param("refresh_token", rotatedRefreshToken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void loginPageDisplaysDatabaseTenantNameWithoutCodeInput() throws Exception {
        mockMvc.perform(get("/login").secure(true))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("浏览器测试企业")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"tenantCode\""))));
    }

    @Test
    void frontendLoginRequiresCsrfAndReturnsUniformCredentialFailure() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult result = mockMvc.perform(get("/scdp/session").secure(true).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.tenantName").value("浏览器测试企业")).andReturn();
        String csrf = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsByteArray()).get("csrfToken").asString();
        mockMvc.perform(post("/scdp/login").secure(true).session(session)
                        .param("tenantCode", "browser-scdp").param("username", USERNAME).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        for (String tenant : List.of("unknown-tenant", "browser-scdp")) {
            mockMvc.perform(post("/scdp/login").secure(true).session(session).param("_csrf", csrf)
                            .param("tenantCode", tenant).param("username", USERNAME).param("password", "incorrect-password"))
                    .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"));
        }
        mockMvc.perform(post("/scdp/login").secure(true).session(session).param("_csrf", csrf)
                        .param("tenantCode", "browser-scdp").param("principalScope", "PLATFORM")
                        .param("username", USERNAME).param("password", PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void frontendSessionLoginThenPkceAuthorizationAndLogout() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String oldSessionId = session.getId();
        MvcResult result = mockMvc.perform(get("/scdp/session").secure(true).session(session))
                .andExpect(status().isOk()).andReturn();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        String csrf = json.readTree(result.getResponse().getContentAsByteArray()).get("csrfToken").asString();
        mockMvc.perform(post("/scdp/login").secure(true).session(session).param("_csrf", csrf)
                        .param("username", USERNAME).param("password", PASSWORD))
                .andExpect(status().isNoContent()).andExpect(header().doesNotExist("Location"));
        assertThat(session.getId()).isNotEqualTo(oldSessionId);
        mockMvc.perform(get("/scdp/session").secure(true).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));
        // 登录后轮换 CSRF，旧表单不能重放。
        mockMvc.perform(post("/scdp/login").secure(true).session(session).param("_csrf", csrf))
                .andExpect(status().isForbidden());
        String verifier = "frontend-pkce-verifier-long-enough-0123456789abcdef";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorizationPath = "/oauth2/authorize?response_type=code&client_id=" + CLIENT_ID
                + "&redirect_uri=" + REDIRECT_URI + "&scope=openid%20profile&state=frontend-state&nonce=frontend-nonce"
                + "&code_challenge=" + challenge + "&code_challenge_method=S256";
        MvcResult authorized = mockMvc.perform(get(URI.create(authorizationPath)).secure(true).session(session))
                .andExpect(status().is3xxRedirection()).andReturn();
        URI callback = URI.create(authorized.getResponse().getHeader("Location"));
        assertThat(callback.toString()).startsWith(REDIRECT_URI);
        assertThat(queryParameter(callback.getRawQuery(), "state")).isEqualTo("frontend-state");
        MvcResult tokens = mockMvc.perform(post("/oauth2/token").secure(true)
                        .param("grant_type", "authorization_code").param("client_id", CLIENT_ID)
                        .param("redirect_uri", REDIRECT_URI).param("code", queryParameter(callback.getRawQuery(), "code"))
                        .param("code_verifier", verifier))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access_token").isString())
                .andExpect(jsonPath("$.id_token").isString()).andReturn();
        String accessToken = json.readTree(tokens.getResponse().getContentAsByteArray()).get("access_token").asString();
        MvcResult loggedIn = mockMvc.perform(get("/scdp/session").secure(true).session(session))
                .andExpect(status().isOk()).andReturn();
        String logoutCsrf = json.readTree(loggedIn.getResponse().getContentAsByteArray()).get("csrfToken").asString();
        mockMvc.perform(post("/scdp/logout").secure(true).session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/scdp/logout").secure(true).session(session).param("_csrf", logoutCsrf))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("RIGOUR_IAM_SESSION", 0));
        mockMvc.perform(get("/api/v1/me").secure(true).header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/scdp/session").secure(true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void directLoginReturnsToPrimarySCDPInsteadOfDeniedIamRoot() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult loginPage =
                mockMvc.perform(get("/login").secure(true).session(session))
                        .andExpect(status().isOk())
                        .andReturn();
        Matcher matcher = CSRF_VALUE.matcher(loginPage.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();

        mockMvc.perform(
                        post("/login")
                                .secure(true)
                                .session(session)
                                .param("_csrf", matcher.group(1))
                                .param("tenantCode", "browser-scdp")
                                .param("username", USERNAME)
                                .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://scdp.dev.rigour.local/"));
    }

    @Test
    void promptLoginForcesFreshIamLoginAfterExistingBrowserSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult loginPage =
                mockMvc.perform(get("/login").secure(true).session(session))
                        .andExpect(status().isOk())
                        .andReturn();
        Matcher matcher = CSRF_VALUE.matcher(loginPage.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();

        mockMvc.perform(
                        post("/login")
                                .secure(true)
                                .session(session)
                                .param("_csrf", matcher.group(1))
                                .param("tenantCode", "browser-scdp")
                                .param("username", USERNAME)
                                .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://scdp.dev.rigour.local/"));

        String verifier = "prompt-login-pkce-verifier-that-is-long-enough-0123456789";
        String challenge =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorizationPath =
                "/oauth2/authorize?response_type=code&client_id="
                        + CLIENT_ID
                        + "&redirect_uri="
                        + REDIRECT_URI
                        + "&scope=openid%20profile&state=prompt-login-state"
                        + "&code_challenge="
                        + challenge
                        + "&code_challenge_method=S256"
                        + "&prompt=login";

        MvcResult forceLogin =
                mockMvc.perform(get(URI.create(authorizationPath)).secure(true).session(session))
                        .andExpect(status().is3xxRedirection())
                        .andExpect(redirectedUrl("/login"))
                        .andReturn();
        MockHttpSession freshSession = (MockHttpSession) forceLogin.getRequest().getSession(false);
        assertThat(freshSession).isNotNull();

        MvcResult freshLoginPage =
                mockMvc.perform(get("/login").secure(true).session(freshSession))
                        .andExpect(status().isOk())
                        .andReturn();
        Matcher freshMatcher =
                CSRF_VALUE.matcher(freshLoginPage.getResponse().getContentAsString());
        assertThat(freshMatcher.find()).isTrue();
        mockMvc.perform(
                        post("/login")
                                .secure(true)
                                .session(freshSession)
                                .param("_csrf", freshMatcher.group(1))
                                .param("tenantCode", "browser-scdp")
                                .param("username", USERNAME)
                                .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(
                        header().string(
                                        "Location",
                                        org.hamcrest.Matchers.containsString("/oauth2/authorize")));

        mockMvc.perform(get(URI.create(authorizationPath)).secure(true).session(freshSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(
                        header().string(
                                        "Location",
                                        org.hamcrest.Matchers.startsWith(REDIRECT_URI)));
    }

    @Test
    void refreshRotationRejectsReplayAndRevokesTheSession() throws Exception {
        String original = issueRefreshToken();
        var refreshed = mockMvc.perform(post("/oauth2/token").secure(true)
                        .param("grant_type", "refresh_token").param("client_id", CLIENT_ID).param("refresh_token", original))
                .andExpect(status().isOk()).andReturn();
        var tokens = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(refreshed.getResponse().getContentAsByteArray());
        mockMvc.perform(post("/oauth2/token").secure(true)
                        .param("grant_type", "refresh_token").param("client_id", CLIENT_ID).param("refresh_token", original))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        mockMvc.perform(post("/oauth2/token").secure(true).param("grant_type", "refresh_token")
                        .param("client_id", CLIENT_ID).param("refresh_token", tokens.get("refresh_token").asString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        mockMvc.perform(get("/api/v1/me").secure(true).header("Authorization", "Bearer " + tokens.get("access_token").asString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshIsBoundToClientScopeAndExpiration() throws Exception {
        String token = issueRefreshToken();
        registeredClientRepository.save(RegisteredClient.from(scdpClient()).id(UUID.randomUUID().toString())
                .clientId("another-pkce-client").build());
        mockMvc.perform(post("/oauth2/token").secure(true).param("grant_type", "refresh_token")
                        .param("client_id", "another-pkce-client").param("refresh_token", token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        mockMvc.perform(post("/oauth2/token").secure(true).param("grant_type", "refresh_token")
                        .param("client_id", CLIENT_ID).param("refresh_token", token).param("scope", "admin"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_scope"));
        jdbcTemplate.update("""
                UPDATE iam_refresh_token SET issued_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 HOUR),
                    expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR)
                WHERE token_hash=UNHEX(SHA2(?,256))
                """, token);
        mockMvc.perform(post("/oauth2/token").secure(true).param("grant_type", "refresh_token")
                        .param("client_id", CLIENT_ID).param("refresh_token", token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void migrationAddsOnlyScdpRefreshGrantAndCanBeReapplied() {
        registeredClientRepository.save(RegisteredClient.from(scdpClient()).id(UUID.randomUUID().toString())
                .clientId("unrelated-code-only").authorizationGrantTypes(grants -> grants.remove(AuthorizationGrantType.REFRESH_TOKEN)).build());
        jdbcTemplate.update("DELETE g FROM iam_oauth_client_grant g JOIN iam_oauth_client c ON c.id=g.client_id WHERE c.client_id=? AND g.grant_type='refresh_token'", CLIENT_ID);
        var migration = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/V109__iam_scdp_rotating_refresh_tokens.sql"));
        migration.execute(jdbcTemplate.getDataSource());
        migration.execute(jdbcTemplate.getDataSource());
        assertThat(registeredClientRepository.findByClientId(CLIENT_ID).getAuthorizationGrantTypes())
                .contains(AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(registeredClientRepository.findByClientId("unrelated-code-only").getAuthorizationGrantTypes())
                .doesNotContain(AuthorizationGrantType.REFRESH_TOKEN);
    }

    @Test
    void nativePasswordLoginUsesIamCodeTokensAndNoBrowserSession() throws Exception {
        var login = nativeLogin(nativeRequest(PASSWORD)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.data.expiresIn").value(300)).andReturn();
        assertThat(login.getRequest().getSession(false)).isNull();
        var data = json(login).get("data");
        assertThat(data.get("state").asString()).isEqualTo(nativeRequest(PASSWORD).get("state"));
        String code = data.get("authorizationCode").asString();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iam_oauth_authorization WHERE authorization_code_hash=UNHEX(SHA2(?,256))", Integer.class, code)).isEqualTo(1);
        var tokens = nativeExchange(code, NATIVE_VERIFIER, NATIVE_CLIENT_ID, NATIVE_REDIRECT)
                .andExpect(status().isOk()).andExpect(jsonPath("$.id_token").doesNotExist()).andReturn();
        var body = json(tokens);
        var jwt = jwtDecoder.decode(body.get("access_token").asString());
        assertThat(jwt.getClaimAsString("principalScope")).isEqualTo("TENANT");
        assertThat(jdbcTemplate.queryForObject("SELECT client_type FROM iam_auth_session WHERE id=?", String.class, uuid(UUID.fromString(jwt.getClaimAsString("sessionId"))))).isEqualTo("NATIVE_APP");
        assertThat(jdbcTemplate.queryForObject("SELECT TIMESTAMPDIFF(SECOND,issued_at,expires_at) FROM iam_auth_session WHERE id=?", Long.class, uuid(UUID.fromString(jwt.getClaimAsString("sessionId"))))).isEqualTo(28800L);
        mockMvc.perform(get("/api/v1/me").secure(true).header("Authorization", "Bearer " + body.get("access_token").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(USERNAME));
    }

    @Test
    void nativeLoginKeepsPasswordFailureCountsAndLockout() throws Exception {
        long before = nativeSessions();
        try {
            for (int attempt = 0; attempt < 5; attempt++) nativeLogin(nativeRequest("incorrect-password"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("IAM_LOGIN_FAILED"));
            assertThat(jdbcTemplate.queryForObject("SELECT c.failed_attempts FROM iam_user_credential c JOIN iam_user u ON u.id=c.user_id WHERE u.username=?", Integer.class, USERNAME)).isEqualTo(5);
            nativeLogin(nativeRequest(PASSWORD)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("IAM_LOGIN_FAILED"));
            assertThat(nativeSessions()).isEqualTo(before);
        } finally { resetNativeCredential(); }
    }

    @Test
    void nativeLoginRejectsUnavailableUserTenantAndCredential() throws Exception {
        long before = nativeSessions();
        try {
            jdbcTemplate.update("UPDATE iam_user SET status='DISABLED' WHERE username=?", USERNAME);
            nativeLogin(nativeRequest(PASSWORD)).andExpect(status().isUnauthorized());
            jdbcTemplate.update("UPDATE iam_user SET status='ACTIVE' WHERE username=?", USERNAME);
            jdbcTemplate.update("UPDATE iam_tenant SET status='SUSPENDED' WHERE tenant_code='browser-scdp'");
            nativeLogin(nativeRequest(PASSWORD)).andExpect(status().isUnauthorized());
            jdbcTemplate.update("UPDATE iam_tenant SET status='ACTIVE' WHERE tenant_code='browser-scdp'");
            jdbcTemplate.update("UPDATE iam_user_credential c JOIN iam_user u ON u.id=c.user_id SET c.status='DISABLED' WHERE u.username=?", USERNAME);
            nativeLogin(nativeRequest(PASSWORD)).andExpect(status().isUnauthorized());
            assertThat(nativeSessions()).isEqualTo(before);
        } finally {
            jdbcTemplate.update("UPDATE iam_user SET status='ACTIVE' WHERE username=?", USERNAME);
            jdbcTemplate.update("UPDATE iam_tenant SET status='ACTIVE' WHERE tenant_code='browser-scdp'");
            jdbcTemplate.update("UPDATE iam_user_credential c JOIN iam_user u ON u.id=c.user_id SET c.status='ACTIVE' WHERE u.username=?", USERNAME);
        }
    }

    @Test
    void nativeLoginRejectsUnapprovedClientRedirectAndPkceBeforePassword() throws Exception {
        long before = nativeSessions();
        var bad = nativeRequest(PASSWORD);
        bad.put("clientId", CLIENT_ID);
        nativeLogin(bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IAM_NATIVE_CLIENT_INVALID"));
        bad = nativeRequest(PASSWORD); bad.put("redirectUri", NATIVE_REDIRECT + "?foreign=true");
        nativeLogin(bad).andExpect(status().isBadRequest());
        bad = nativeRequest(PASSWORD); bad.put("codeChallengeMethod", "plain");
        nativeLogin(bad).andExpect(status().isBadRequest());
        bad = nativeRequest(PASSWORD); bad.put("codeChallenge", "short");
        nativeLogin(bad).andExpect(status().isBadRequest());
        bad = nativeRequest(PASSWORD); bad.put("state", "short");
        nativeLogin(bad).andExpect(status().isBadRequest());
        try {
            jdbcTemplate.update("UPDATE iam_oauth_client SET status='DISABLED' WHERE client_id=?", NATIVE_CLIENT_ID);
            nativeLogin(nativeRequest(PASSWORD)).andExpect(status().isBadRequest());
        } finally { jdbcTemplate.update("UPDATE iam_oauth_client SET status='ACTIVE' WHERE client_id=?", NATIVE_CLIENT_ID); }
        assertThat(nativeSessions()).isEqualTo(before);
    }

    @Test
    void nativeLoginRequiresJsonAndDoesNotReuseOrChangeBrowserSession() throws Exception {
        mockMvc.perform(post("/api/v1/auth/native-app/login").secure(true)
                        .contentType("application/x-www-form-urlencoded").param("username", USERNAME).param("password", PASSWORD))
                .andExpect(status().isUnsupportedMediaType());
        var session = new MockHttpSession();
        var initial = mockMvc.perform(get("/scdp/session").secure(true).session(session)).andReturn();
        String csrf = json(initial).get("csrfToken").asString();
        mockMvc.perform(post("/scdp/login").secure(true).session(session).param("_csrf", csrf)
                        .param("username", USERNAME).param("password", PASSWORD)).andExpect(status().isNoContent());
        Object context = session.getAttribute("SPRING_SECURITY_CONTEXT");
        try {
            mockMvc.perform(post("/api/v1/auth/native-app/login").secure(true).session(session)
                            .contentType("application/json").content(toJson(nativeRequest("wrong"))))
                    .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
            assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isSameAs(context);
            mockMvc.perform(post("/api/v1/auth/native-app/login").secure(true).session(session)
                            .contentType("application/json").content(toJson(nativeRequest(PASSWORD))))
                    .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
            assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT")).isSameAs(context);
            mockMvc.perform(post("/scdp/logout").secure(true).session(session)).andExpect(status().isForbidden());
        } finally { resetNativeCredential(); }
    }

    @Test
    void nativeCodeIsBoundToVerifierClientRedirectAndExpires() throws Exception {
        String code = nativeCode();
        nativeExchange(code, "wrong-verifier-that-is-long-enough-0123456789abc", NATIVE_CLIENT_ID, NATIVE_REDIRECT)
                .andExpect(status().isBadRequest());
        nativeExchange(code, NATIVE_VERIFIER, CLIENT_ID, NATIVE_REDIRECT).andExpect(status().isBadRequest());
        nativeExchange(code, NATIVE_VERIFIER, NATIVE_CLIENT_ID, NATIVE_REDIRECT + "/other").andExpect(status().isBadRequest());
        String expired = nativeCode();
        jdbcTemplate.update("""
                UPDATE iam_oauth_authorization SET code_issued_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE),
                    code_expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE)
                WHERE authorization_code_hash=UNHEX(SHA2(?,256))
                """, expired);
        nativeExchange(expired, NATIVE_VERIFIER, NATIVE_CLIENT_ID, NATIVE_REDIRECT).andExpect(status().isBadRequest());
    }

    @Test
    void nativeCodeAndRefreshReplayAreRejected() throws Exception {
        String code = nativeCode();
        nativeExchange(code, NATIVE_VERIFIER, NATIVE_CLIENT_ID, NATIVE_REDIRECT).andExpect(status().isOk());
        nativeExchange(code, NATIVE_VERIFIER, NATIVE_CLIENT_ID, NATIVE_REDIRECT)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        var tokens = nativeTokens();
        var rotated = nativeRefresh(tokens.get("refresh_token").asString()).andExpect(status().isOk()).andReturn();
        assertThat(json(rotated).get("refresh_token").asString()).isNotEqualTo(tokens.get("refresh_token").asString());
        nativeRefresh(tokens.get("refresh_token").asString()).andExpect(status().isBadRequest());
        nativeRefresh(json(rotated).get("refresh_token").asString()).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/me").secure(true).header("Authorization", "Bearer " + json(rotated).get("access_token").asString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nativeLogoutRevokesOnlyCurrentSessionAndCannotUseCookie() throws Exception {
        var first = nativeTokens();
        var other = nativeTokens();
        String webRefresh = issueRefreshToken();
        mockMvc.perform(post("/api/v1/auth/native-app/logout").secure(true)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/native-app/logout").secure(true)
                        .header("Authorization", "Bearer " + first.get("access_token").asString())
                        .contentType("application/json").content("{\"sessionId\":\"" + jwtDecoder.decode(other.get("access_token").asString()).getClaimAsString("sessionId") + "\"}"))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        nativeRefresh(first.get("refresh_token").asString()).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/me").secure(true).header("Authorization", "Bearer " + first.get("access_token").asString()))
                .andExpect(status().isUnauthorized());
        nativeRefresh(other.get("refresh_token").asString()).andExpect(status().isOk());
        mockMvc.perform(post("/oauth2/token").secure(true).param("grant_type", "refresh_token")
                        .param("client_id", CLIENT_ID).param("refresh_token", webRefresh)).andExpect(status().isOk());
    }

    @Test
    void nativeRefreshRejectsDisabledUserTenantAndExpiredSessionAsInvalidGrant() throws Exception {
        var userTokens = nativeTokens();
        try {
            jdbcTemplate.update("UPDATE iam_user SET status='DISABLED' WHERE username=?", USERNAME);
            mockMvc.perform(get("/api/v1/me").secure(true).header("Authorization", "Bearer " + userTokens.get("access_token").asString()))
                    .andExpect(status().isUnauthorized());
            nativeRefresh(userTokens.get("refresh_token").asString()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"));
        } finally { jdbcTemplate.update("UPDATE iam_user SET status='ACTIVE' WHERE username=?", USERNAME); }
        var tenantTokens = nativeTokens();
        try {
            jdbcTemplate.update("UPDATE iam_tenant SET status='SUSPENDED' WHERE tenant_code='browser-scdp'");
            mockMvc.perform(get("/api/v1/me").secure(true).header("Authorization", "Bearer " + tenantTokens.get("access_token").asString()))
                    .andExpect(status().isUnauthorized());
            nativeRefresh(tenantTokens.get("refresh_token").asString()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"));
        } finally { jdbcTemplate.update("UPDATE iam_tenant SET status='ACTIVE' WHERE tenant_code='browser-scdp'"); }
        var expired = nativeTokens();
        var sid = uuid(UUID.fromString(jwtDecoder.decode(expired.get("access_token").asString()).getClaimAsString("sessionId")));
        jdbcTemplate.update("""
                UPDATE iam_auth_session SET issued_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 HOUR),
                    last_seen_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 HOUR),expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR)
                WHERE id=?
                """, sid);
        nativeRefresh(expired.get("refresh_token").asString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    private java.util.Map<String, Object> nativeRequest(String password) throws Exception {
        if (registeredClientRepository.findByClientId(NATIVE_CLIENT_ID) == null
                && jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iam_oauth_client WHERE client_id=?", Integer.class, NATIVE_CLIENT_ID) == 0) {
            var properties = new com.rigour.tenant.iam.infrastructure.bootstrap.SalesAppClientBootstrapProperties();
            new com.rigour.tenant.iam.infrastructure.bootstrap.SalesAppClientBootstrapCommand(
                    registeredClientRepository, UUID::randomUUID, properties, false).run(new org.springframework.boot.DefaultApplicationArguments());
        }
        var request = new java.util.LinkedHashMap<String, Object>();
        request.put("clientId", NATIVE_CLIENT_ID); request.put("redirectUri", NATIVE_REDIRECT);
        request.put("codeChallenge", Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(NATIVE_VERIFIER.getBytes(StandardCharsets.US_ASCII))));
        request.put("codeChallengeMethod", "S256"); request.put("state", "native-state-0123456789abcdefghijklmnop");
        request.put("username", USERNAME); request.put("password", password); request.put("deviceName", "Native test phone");
        return request;
    }

    private org.springframework.test.web.servlet.ResultActions nativeLogin(java.util.Map<String, Object> request) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/native-app/login").secure(true).contentType("application/json").content(toJson(request)));
    }

    private String nativeCode() throws Exception {
        return json(nativeLogin(nativeRequest(PASSWORD)).andExpect(status().isOk()).andReturn()).get("data").get("authorizationCode").asString();
    }

    private tools.jackson.databind.JsonNode nativeTokens() throws Exception {
        return json(nativeExchange(nativeCode(), NATIVE_VERIFIER, NATIVE_CLIENT_ID, NATIVE_REDIRECT).andExpect(status().isOk()).andReturn());
    }

    private org.springframework.test.web.servlet.ResultActions nativeExchange(String code, String verifier, String clientId, String redirect) throws Exception {
        return mockMvc.perform(post("/oauth2/token").secure(true).param("grant_type", "authorization_code")
                .param("client_id", clientId).param("redirect_uri", redirect).param("code", code).param("code_verifier", verifier));
    }

    private org.springframework.test.web.servlet.ResultActions nativeRefresh(String refresh) throws Exception {
        return mockMvc.perform(post("/oauth2/token").secure(true).param("grant_type", "refresh_token")
                .param("client_id", NATIVE_CLIENT_ID).param("refresh_token", refresh));
    }

    private static String toJson(Object value) { return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(value); }
    private static tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.getResponse().getContentAsByteArray());
    }
    private long nativeSessions() { return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iam_auth_session WHERE client_type='NATIVE_APP'", Long.class); }
    private void resetNativeCredential() {
        jdbcTemplate.update("UPDATE iam_user_credential c JOIN iam_user u ON u.id=c.user_id SET c.failed_attempts=0,c.locked_until=NULL,c.last_failed_at=NULL WHERE u.username=?", USERNAME);
    }

    private String issueRefreshToken() throws Exception {
        var session = new MockHttpSession();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var status = mockMvc.perform(get("/scdp/session").secure(true).session(session)).andReturn();
        String csrf = json.readTree(status.getResponse().getContentAsByteArray()).get("csrfToken").asString();
        mockMvc.perform(post("/scdp/login").secure(true).session(session).param("_csrf", csrf)
                        .param("tenantCode", "browser-scdp").param("username", USERNAME).param("password", PASSWORD))
                .andExpect(status().isNoContent());
        String verifier = "refresh-pkce-verifier-long-enough-0123456789abcdef";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var authorized = mockMvc.perform(get("/oauth2/authorize").secure(true).session(session)
                        .queryParam("response_type", "code").queryParam("client_id", CLIENT_ID).queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("scope", "openid profile").queryParam("state", "refresh-state").queryParam("nonce", "refresh-nonce")
                        .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String code = queryParameter(URI.create(authorized.getResponse().getHeader("Location")).getRawQuery(), "code");
        var response = mockMvc.perform(post("/oauth2/token").secure(true)
                        .param("grant_type", "authorization_code").param("client_id", CLIENT_ID)
                        .param("redirect_uri", REDIRECT_URI).param("code", code).param("code_verifier", verifier))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refresh_token").isString()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray()).get("refresh_token").asString();
    }

    private RegisteredClient scdpClient() {
        return RegisteredClient.withId("019fb000-0000-7000-8000-000000000099")
                .clientId(CLIENT_ID)
                .clientIdIssuedAt(Instant.parse("2026-07-31T00:00:00Z"))
                .clientName("Browser Test SCDP")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT_URI)
                .postLogoutRedirectUri("https://scdp.dev.rigour.local/")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(
                        ClientSettings.builder()
                                .requireProofKey(true)
                                .requireAuthorizationConsent(false)
                                .build())
                .tokenSettings(
                        TokenSettings.builder()
                                .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                                .accessTokenTimeToLive(Duration.ofMinutes(15))
                                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                                .refreshTokenTimeToLive(Duration.ofDays(7))
                                .reuseRefreshTokens(false)
                                .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                                .build())
                .build();
    }

    private void insertTenantUser() {
        UUID tenantId = UUID.randomUUID(), userId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("INSERT INTO iam_tenant(id,tenant_code,company_name,status,created_at,updated_at) VALUES(?, 'browser-scdp','浏览器测试企业','ACTIVE',?,?)", uuid(tenantId),now,now);
        jdbcTemplate.update("INSERT INTO iam_user(id,tenant_id,username,display_name,status,created_at,updated_at) VALUES(?,?,?,'Browser User','ACTIVE',?,?)",uuid(userId),uuid(tenantId),USERNAME,now,now);
        jdbcTemplate.update("""
          INSERT INTO iam_user_credential(id,tenant_id,user_id,credential_type,password_hash,algorithm,
            algorithm_version,failed_attempts,password_changed_at,status,version,created_at,updated_at)
          VALUES(?,?,?,'PASSWORD',?,'ARGON2ID',1,0,?,'ACTIVE',0,?,?)
          """,uuid(UUID.randomUUID()),uuid(tenantId),uuid(userId),passwordHasher.hash(PASSWORD),now,now,now);
    }

    private static String queryParameter(String query, String name) {
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("Missing query parameter " + name);
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static String authorizationEncryptionKey() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 0x6B);
        return Base64.getEncoder().encodeToString(key);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSigningConfiguration {

        static final String KEY_ID = "browser-test-rsa-key";
        private static final KeyPair KEY_PAIR = generateKeyPair();

        @Bean
        JWKSource<SecurityContext> testJwkSource() {
            RSAKey rsaKey =
                    new RSAKey.Builder((RSAPublicKey) KEY_PAIR.getPublic())
                            .privateKey((RSAPrivateKey) KEY_PAIR.getPrivate())
                            .keyID(KEY_ID)
                            .keyUse(KeyUse.SIGNATURE)
                            .algorithm(JWSAlgorithm.RS256)
                            .build();
            return new ImmutableJWKSet<>(new JWKSet(rsaKey));
        }

        @Bean
        JwtDecoder testJwtDecoder(JWKSource<SecurityContext> testJwkSource) {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        }

        @Bean
        OAuth2TokenGenerator<OAuth2Token> testTokenGenerator(
                JWKSource<SecurityContext> testJwkSource, JdbcTemplate jdbcTemplate) {
            JwtEncoder encoder = new NimbusJwtEncoder(testJwkSource);
            JwtGenerator jwtGenerator = new JwtGenerator(encoder);
            jwtGenerator.setJwtCustomizer(
                    new IamJwtCustomizer(
                            new IamTokenClaimsResolver(jdbcTemplate), List.of("rigour-api")));
            return new DelegatingOAuth2TokenGenerator(
                    jwtGenerator, new IamRefreshTokenGenerator());
        }

        private static KeyPair generateKeyPair() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(3072);
                return generator.generateKeyPair();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
