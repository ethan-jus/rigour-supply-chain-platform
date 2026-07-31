package com.rigour.tenant.iam;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(IamAuthorizationServerSecurityTests.TestSigningConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class IamAuthorizationServerSecurityTests {

    private static final String CLIENT_ID = "rigour-portal-browser-test";
    private static final String REDIRECT_URI =
            "https://portal.dev.rigour.local/login/oauth2/code/rigour-iam";
    private static final String USERNAME = "browser-platform-admin";
    private static final String PASSWORD = "Browser-Test-Password-42!";
    private static final Pattern CSRF_VALUE = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
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
        registry.add("rigour.iam.oidc.server.allowed-origins[0]", () -> "https://portal.dev.rigour.local");
        registry.add("rigour.iam.oidc.authorization-attributes.enabled", () -> "true");
        registry.add("rigour.iam.oidc.authorization-attributes.active-key-version", () -> "v1");
        registry.add("rigour.iam.oidc.authorization-attributes.keys-base64.v1",
                IamAuthorizationServerSecurityTests::authorizationEncryptionKey);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @BeforeEach
    void initializeClientAndUser() {
        registeredClientRepository.save(portalClient());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM iam_platform_user WHERE username = ?", Integer.class, USERNAME);
        if (count != null && count == 0) {
            insertPlatformUser();
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
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"d\""))));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/oauth2/token")
                        .secure(true)
                        .header("Origin", "https://portal.dev.rigour.local")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://portal.dev.rigour.local"));
    }

    @Test
    void rejectsPublicAuthorizationRequestWithoutPkce() throws Exception {
        String requestUri = "/oauth2/authorize?response_type=code&client_id=" + CLIENT_ID
                + "&redirect_uri=" + REDIRECT_URI
                + "&scope=openid%20profile&state=missing-pkce-state";
        mockMvc.perform(get(URI.create(requestUri)).secure(true))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("error=")));
    }

    @Test
    void completesBrowserLoginPkceCodeAndTokenFlow() throws Exception {
        String verifier = "browser-pkce-verifier-that-is-long-enough-0123456789";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String authorizationPath = "/oauth2/authorize?response_type=code&client_id=" + CLIENT_ID
                + "&redirect_uri=" + REDIRECT_URI
                + "&scope=openid%20profile&state=browser-state"
                + "&code_challenge=" + challenge + "&code_challenge_method=S256";

        MvcResult authorizationStart = mockMvc.perform(get(URI.create(authorizationPath)).secure(true))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) authorizationStart.getRequest().getSession(false);
        assertThat(session).isNotNull();

        MvcResult loginPage = mockMvc.perform(get("/login").secure(true).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("script-src 'nonce-")))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString(
                                "form-action 'self' https://portal.dev.rigour.local")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("瑞盖统一身份认证")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("正在验证身份…")))
                .andReturn();
        Matcher matcher = CSRF_VALUE.matcher(loginPage.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();
        String csrf = matcher.group(1);

        mockMvc.perform(post("/login").secure(true).session(session)
                        .param("_csrf", csrf)
                        .param("principalScope", "PLATFORM")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/oauth2/authorize")));

        MvcResult authorizationFinish = mockMvc.perform(
                        get(URI.create(authorizationPath)).secure(true).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(REDIRECT_URI)))
                .andReturn();
        URI callback = URI.create(authorizationFinish.getResponse().getHeader("Location"));
        String code = queryParameter(callback.getRawQuery(), "code");
        assertThat(queryParameter(callback.getRawQuery(), "state")).isEqualTo("browser-state");

        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token").secure(true)
                        .param("grant_type", "authorization_code")
                        .param("client_id", CLIENT_ID)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code", code)
                        .param("code_verifier", verifier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isString())
                .andExpect(jsonPath("$.id_token").isString())
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andReturn();
        var tokenJson = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(tokenResult.getResponse().getContentAsByteArray());
        String accessToken = tokenJson.get("access_token").asString();
        String idToken = tokenJson.get("id_token").asString();
        assertThat(authorizationService.findByToken(idToken, new OAuth2TokenType("id_token"))).isNotNull();
        Jwt accessJwt = jwtDecoder.decode(accessToken);
        assertThat(accessJwt.getAudience()).contains("rigour-api");
        assertThat(accessJwt.getClaimAsString("principalScope")).isEqualTo("PLATFORM");
        assertThat(accessJwt.getClaimAsString("sessionId")).isNotBlank();
        assertThat(accessJwt.getClaimAsString("tokenUse")).isEqualTo("access");

        mockMvc.perform(get("/api/v1/me").secure(true)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.principalScope").value("PLATFORM"));
        mockMvc.perform(get("/api/v1/portal/apps").secure(true)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("PLATFORM_ADMIN"));
        mockMvc.perform(get("/api/v1/portal/navigation/PLATFORM_ADMIN").secure(true)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].routePath").value("/platform-admin"));

        String logoutPath = "/connect/logout?id_token_hint=" + idToken
                + "&post_logout_redirect_uri=https%3A%2F%2Fportal.dev.rigour.local%2F";
        mockMvc.perform(get(URI.create(logoutPath)).secure(true).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://portal.dev.rigour.local/"));
        mockMvc.perform(get("/api/v1/me").secure(true)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void directLoginReturnsToPrimaryPortalInsteadOfDeniedIamRoot() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult loginPage = mockMvc.perform(get("/login").secure(true).session(session))
                .andExpect(status().isOk())
                .andReturn();
        Matcher matcher = CSRF_VALUE.matcher(loginPage.getResponse().getContentAsString());
        assertThat(matcher.find()).isTrue();

        mockMvc.perform(post("/login").secure(true).session(session)
                        .param("_csrf", matcher.group(1))
                        .param("principalScope", "PLATFORM")
                        .param("username", USERNAME)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://portal.dev.rigour.local/"));
    }

    private RegisteredClient portalClient() {
        return RegisteredClient.withId("019fb000-0000-7000-8000-000000000099")
                .clientId(CLIENT_ID)
                .clientIdIssuedAt(Instant.parse("2026-07-31T00:00:00Z"))
                .clientName("Browser Test Portal")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT_URI)
                .postLogoutRedirectUri("https://portal.dev.rigour.local/")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder()
                        .authorizationCodeTimeToLive(Duration.ofMinutes(5))
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false)
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                        .build())
                .build();
    }

    private void insertPlatformUser() {
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                        INSERT INTO iam_platform_user (
                            id, username, display_name, platform_role, status, security_version, version,
                            created_at, updated_at
                        ) VALUES (?, ?, 'Browser Admin', 'SUPER_ADMIN', 'ACTIVE', 0, 0, ?, ?)
                        """, uuid(userId), USERNAME, now, now);
        jdbcTemplate.update("""
                        INSERT INTO iam_platform_user_credential (
                            id, platform_user_id, credential_type, password_hash, algorithm, algorithm_version,
                            failed_attempts, password_changed_at, status, version, created_at, updated_at
                        ) VALUES (?, ?, 'PASSWORD', ?, 'ARGON2ID', 1, 0, ?, 'ACTIVE', 0, ?, ?)
                        """, uuid(credentialId), uuid(userId), passwordHasher.hash(PASSWORD), now, now, now);
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
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
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
            RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) KEY_PAIR.getPublic())
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
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        }

        @Bean
        OAuth2TokenGenerator<OAuth2Token> testTokenGenerator(
                JWKSource<SecurityContext> testJwkSource, JdbcTemplate jdbcTemplate) {
            JwtEncoder encoder = new NimbusJwtEncoder(testJwkSource);
            JwtGenerator jwtGenerator = new JwtGenerator(encoder);
            jwtGenerator.setJwtCustomizer(new IamJwtCustomizer(
                    new IamTokenClaimsResolver(jdbcTemplate), List.of("rigour-api")));
            return new DelegatingOAuth2TokenGenerator(jwtGenerator, new OAuth2RefreshTokenGenerator());
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
