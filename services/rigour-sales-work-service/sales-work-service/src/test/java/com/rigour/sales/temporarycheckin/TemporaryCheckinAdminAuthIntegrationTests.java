package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rigour.sales.application.port.out.AmapPoiClient;
import com.rigour.shared.file.FileStorage;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 使用真实 MySQL 验证后台一次性密码、会话、CSRF、改密、退出和登录锁定。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TemporaryCheckinAdminAuthIntegrationTests {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000011");
    private static final String BOOTSTRAP_SECRET = "bootstrap-secret-for-integration-test";
    private static final String PROXY_MARKER = "proxy-marker-for-integration-test";
    private static final String COOKIE_NAME = "__Host-rigour-sales-checkin-admin";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work")
            .withUsername("rigour_admin_auth_test")
            .withPassword("rigour_admin_auth_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
        registry.add("rigour.sales.temporary-checkin.enabled", () -> true);
        registry.add("rigour.sales.temporary-checkin.tenant-id", TENANT_ID::toString);
        registry.add("rigour.sales.temporary-checkin.identity-signing-key-base64",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("rigour.sales.temporary-checkin.risk-hmac-key-base64",
                () -> "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=");
        registry.add("rigour.sales.temporary-checkin.trusted-proxy-marker", () -> PROXY_MARKER);
        registry.add("rigour.sales.temporary-checkin.admin-auth.pbkdf2-iterations", () -> 120_000);
        registry.add("rigour.sales.temporary-checkin.admin-auth.bootstrap-secret", () -> BOOTSTRAP_SECRET);
        registry.add("rigour.sales.temporary-checkin.admin-auth.bootstrap-proxy-marker", () -> PROXY_MARKER);
        registry.add("rigour.sales.temporary-checkin.admin-auth.bootstrap-allowed-remote-cidrs",
                () -> "127.0.0.1/32");
    }

    @Autowired private WebApplicationContext context;
    @Autowired private TemporaryCheckinAdminAuthenticationFilter authFilter;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private FileStorage fileStorage;
    @MockitoBean private TemporaryCheckinReverseGeocoder reverseGeocoder;
    @MockitoBean private AmapPoiClient amapPoiClient;
    @MockitoBean private TemporaryCheckinAiClient aiClient;

    private MockMvc mockMvc;

    @BeforeEach
    void resetAccounts() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(authFilter).build();
        jdbc.update("DELETE FROM temp_sales_checkin_admin_session");
        jdbc.update("DELETE FROM temp_sales_checkin_admin_account");
        jdbc.update("DELETE FROM temp_sales_checkin_city");
    }

    @Test
    void bootstrapsUniquePasswordsThenRequiresChangeAndRotatesSessionOnLogout() throws Exception {
        JsonNode bootstrap = bootstrap();
        String globalTemporary = bootstrap.get("createdAccounts").get(0).get("temporaryPassword").asText();
        String cityTemporary = bootstrap.get("createdAccounts").get(1).get("temporaryPassword").asText();
        assertThat(globalTemporary).isNotEqualTo(cityTemporary);
        assertThat(jdbc.queryForObject("""
                SELECT password_hash FROM temp_sales_checkin_admin_account
                 WHERE tenant_id=? AND username='sales-checkin-admin'
                """, String.class, com.rigour.sales.infrastructure.persistence.SalesUuidCodec.encode(TENANT_ID)))
                .startsWith("pbkdf2-sha256$120000$").doesNotContain(globalTemporary);

        MvcResult loggedIn = login("sales-checkin-admin", globalTemporary)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"),
                        org.hamcrest.Matchers.containsString("Path=/"))))
                .andExpect(jsonPath("$.account.mustChangePassword").value(true))
                .andReturn();
        Cookie temporaryCookie = sessionCookie(loggedIn);
        String temporaryCsrf = json(loggedIn).get("account").get("csrfToken").asText();

        mockMvc.perform(get("/sales-checkin/admin/api/v1/options").cookie(temporaryCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_PASSWORD_CHANGE_REQUIRED"));

        mockMvc.perform(post("/sales-checkin/admin/api/v1/auth/change-password")
                        .cookie(temporaryCookie)
                        .header(TemporaryCheckinAdminAuthenticationFilter.CSRF_HEADER, temporaryCsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"all-lowercase-2026"}
                                """.formatted(globalTemporary)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("新密码必须同时包含大写字母、小写字母和数字"));

        MvcResult changed = mockMvc.perform(post("/sales-checkin/admin/api/v1/auth/change-password")
                        .cookie(temporaryCookie)
                        .header(TemporaryCheckinAdminAuthenticationFilter.CSRF_HEADER, temporaryCsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"Permanent-admin-2026!"}
                                """.formatted(globalTemporary)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.mustChangePassword").value(false))
                .andExpect(jsonPath("$.account.canManageSalespersons").value(true))
                .andExpect(jsonPath("$.account.canManageCities").value(true))
                .andReturn();
        Cookie permanentCookie = sessionCookie(changed);
        String permanentCsrf = json(changed).get("account").get("csrfToken").asText();
        assertThat(permanentCookie.getValue()).isNotEqualTo(temporaryCookie.getValue());

        mockMvc.perform(get("/sales-checkin/admin/api/v1/auth/me").cookie(temporaryCookie))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/sales-checkin/admin/api/v1/auth/me").cookie(permanentCookie))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.username").value("sales-checkin-admin"));

        mockMvc.perform(post("/sales-checkin/admin/api/v1/cities")
                        .cookie(permanentCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"天津\",\"adminUsername\":\"city-tianjin\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_ADMIN_CSRF_INVALID"));
        mockMvc.perform(post("/sales-checkin/admin/api/v1/cities")
                        .cookie(permanentCookie)
                        .header(TemporaryCheckinAdminAuthenticationFilter.CSRF_HEADER, permanentCsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"天津\",\"adminUsername\":\"city-tianjin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city.name").value("天津"))
                .andExpect(jsonPath("$.administrator.temporaryPassword").isNotEmpty());

        mockMvc.perform(get("/sales-checkin/admin/api/v1/admin-accounts")
                        .cookie(permanentCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$[?(@.username == 'sales-checkin-admin')].role")
                        .value(org.hamcrest.Matchers.contains("GLOBAL_ADMIN")))
                .andExpect(jsonPath("$[?(@.username == 'city-beijing')].city")
                        .value(org.hamcrest.Matchers.contains("北京")))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[0].temporaryPassword").doesNotExist());

        MvcResult cityLoggedIn = login("city-beijing", cityTemporary)
                .andExpect(status().isOk())
                .andReturn();
        Cookie cityTemporaryCookie = sessionCookie(cityLoggedIn);
        String cityTemporaryCsrf = json(cityLoggedIn).get("account").get("csrfToken").asText();
        MvcResult cityChanged = mockMvc.perform(post("/sales-checkin/admin/api/v1/auth/change-password")
                        .cookie(cityTemporaryCookie)
                        .header(TemporaryCheckinAdminAuthenticationFilter.CSRF_HEADER, cityTemporaryCsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"Beijing-manager-2026!"}
                                """.formatted(cityTemporary)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.canManageSalespersons").value(true))
                .andExpect(jsonPath("$.account.canManageCities").value(false))
                .andReturn();
        mockMvc.perform(get("/sales-checkin/admin/api/v1/admin-accounts")
                        .cookie(sessionCookie(cityChanged)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_ADMIN_FORBIDDEN"));

        mockMvc.perform(post("/sales-checkin/admin/api/v1/auth/logout")
                        .cookie(permanentCookie)
                        .header(TemporaryCheckinAdminAuthenticationFilter.CSRF_HEADER, permanentCsrf))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.containsString("Max-Age=0")));
        mockMvc.perform(get("/sales-checkin/admin/api/v1/auth/me").cookie(permanentCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void locksRepeatedFailuresAndRejectsBootstrapWithoutBothSecretsAndAllowedRemote() throws Exception {
        mockMvc.perform(get("/sales-checkin/admin/api/v1/options")
                        .header("X-Sales-Checkin-Admin-User", "sales-checkin-admin"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/sales-checkin/internal/v1/admin-bootstrap")
                        .with(request -> { request.setRemoteAddr("203.0.113.10"); return request; })
                        .header(TemporaryCheckinAdminBootstrapController.BOOTSTRAP_SECRET_HEADER, BOOTSTRAP_SECRET)
                        .header(TemporaryCheckinRequestFacts.PROXY_MARKER_HEADER, PROXY_MARKER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cities\":[],\"accounts\":[]}"))
                .andExpect(status().isForbidden());

        String temporaryPassword = bootstrap().get("createdAccounts").get(0)
                .get("temporaryPassword").asText();
        for (int attempt = 0; attempt < 5; attempt++) {
            login("sales-checkin-admin", "wrong-password")
                    .andExpect(status().isUnauthorized());
        }
        login("sales-checkin-admin", temporaryPassword)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_LOGIN_LOCKED"));
    }

    private JsonNode bootstrap() throws Exception {
        MvcResult result = mockMvc.perform(post("/sales-checkin/internal/v1/admin-bootstrap")
                        .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                        .header(TemporaryCheckinAdminBootstrapController.BOOTSTRAP_SECRET_HEADER, BOOTSTRAP_SECRET)
                        .header(TemporaryCheckinRequestFacts.PROXY_MARKER_HEADER, PROXY_MARKER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cities":["北京"],
                                  "accounts":[
                                    {"username":"sales-checkin-admin","displayName":"总管理员","role":"GLOBAL_ADMIN"},
                                    {"username":"city-beijing","displayName":"北京管理员","role":"CITY_ADMIN","city":"北京"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.createdAccounts", org.hamcrest.Matchers.hasSize(2)))
                .andReturn();
        return json(result);
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password)
            throws Exception {
        return mockMvc.perform(post("/sales-checkin/admin/api/v1/auth/login")
                .header("Origin", "https://admin.example.test")
                .header("Host", "admin.example.test")
                .header("X-Forwarded-Proto", "https")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\""
                        + jsonEscape(password) + "\"}"));
    }

    private Cookie sessionCookie(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).isNotNull();
        String prefix = COOKIE_NAME + "=";
        String value = header.substring(prefix.length(), header.indexOf(';'));
        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        return cookie;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
