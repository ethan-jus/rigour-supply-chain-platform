package com.rigour.tenant.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.rigour.tenant.iam.application.port.out.PasswordHasher;
import com.rigour.tenant.iam.application.service.management.ManagementModels.*;
import com.rigour.tenant.iam.application.service.identity.IdentityAccessQuery;
import com.rigour.tenant.iam.application.service.identity.IdentityAccessService;
import com.rigour.tenant.iam.application.service.identity.CurrentUser;
import com.rigour.tenant.iam.domain.model.session.AuthSession.ClientType;
import com.rigour.tenant.iam.domain.model.session.AuthSession.PrincipalScope;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.ApplicationMapper;
import com.rigour.tenant.iam.infrastructure.security.oidc.JdbcRsaJwkSource;
import com.rigour.tenant.iam.infrastructure.security.oidc.PrivateKeyReferenceResolver;
import com.rigour.tenant.iam.infrastructure.security.session.IamAuthenticationDetails;
import com.rigour.tenant.iam.infrastructure.security.session.IamLoginAuthenticationToken;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class IamApplicationTests {
    private final org.assertj.core.api.SoftAssertions sqlAssertions =
            new org.assertj.core.api.SoftAssertions();

    @org.junit.jupiter.api.AfterEach
    void verifyAllSqlCounts() {
        sqlAssertions.assertAll();
    }

    @TempDir Path temporaryDirectory;

    private static final OAuth2TokenType STATE_TOKEN_TYPE =
            new OAuth2TokenType(OAuth2ParameterNames.STATE);
    private static final OAuth2TokenType CODE_TOKEN_TYPE =
            new OAuth2TokenType(OAuth2ParameterNames.CODE);

    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4")
                    .withDatabaseName("rigour_iam")
                    .withUsername("rigour_iam_test")
                    .withPassword("rigour_iam_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
        registry.add("rigour.iam.oidc.authorization-attributes.enabled", () -> "true");
        registry.add("rigour.iam.oidc.authorization-attributes.active-key-version", () -> "v1");
        registry.add(
                "rigour.iam.oidc.authorization-attributes.keys-base64.v1",
                IamApplicationTests::testEncryptionKeyBase64);
    }

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void retiredServicesLoseCapabilitiesWhileCustomerRegionsAndCityAnalyticsRemainActive() {
        var retired = jdbcTemplate.queryForList(
                "SELECT r.status, ui.visible FROM iam_resource r JOIN iam_resource_ui ui"
                        + " ON ui.resource_id = r.id WHERE ui.route_key LIKE 'supply.city.%'"
                        + " OR ui.route_key LIKE 'supply.channel.%'");
        assertThat(retired).isNotEmpty().allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("DISABLED");
            assertThat(((Number) row.get("visible")).intValue()).isZero();
        });
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM iam_resource WHERE resource_code IN"
                        + " ('WORKBENCH.PAGE.CHAT', 'WORKBENCH.API.COLLABORATION_IM',"
                        + " 'WORKBENCH.API.COLLABORATION_MEETING')", String.class))
                .hasSize(3).containsOnly("DISABLED");
        for (String routeKey : List.of("supply.crm.customers.areas", "supply.bi.city-cost",
                "supply.settings.users", "supply.settings.roles", "supply.settings.menus")) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT r.status FROM iam_resource r JOIN iam_resource_ui ui"
                            + " ON ui.resource_id = r.id WHERE ui.route_key = ?",
                    String.class, routeKey)).as(routeKey).isEqualTo("ACTIVE");
        }
    }

    @Autowired private ApplicationMapper applicationMapper;

    @Autowired private RegisteredClientRepository registeredClientRepository;

    @Autowired private OAuth2AuthorizationConsentService authorizationConsentService;

    @Autowired private OAuth2AuthorizationService authorizationService;

    @Autowired private AuthenticationProvider authenticationProvider;

    @Autowired private PasswordHasher passwordHasher;


    @Autowired private IdentityAccessService identityAccessService;

    @Test
    void contextLoadsAndMigratesIamSchema() throws java.io.IOException {
        int migrationCount =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                        .getResources("classpath:db/migration/V*.sql")
                        .length;
        assertCount("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", migrationCount);
        assertCount(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version IN"
                        + " ('51.1','52.1')",
                2);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE status NOT IN ('ACTIVE','DISABLED')", 0);
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "UPDATE iam_resource SET status='INACTIVE' LIMIT 1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("ck_iam_resource_status");
        assertCount(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND ((version='33'"
                    + " AND script='V33__iam_erp_product_master_data_permissions.sql') OR"
                    + " (version='34' AND script='V34__iam_align_erp_product_center_menu.sql') OR"
                    + " (version='35' AND"
                    + " script='V35__iam_backfill_erp_product_center_menu_config.sql') OR"
                    + " (version='36' AND"
                    + " script='V36__iam_erp_supply_data_pages_and_permissions.sql') OR"
                    + " (version='39' AND"
                    + " script='V39__iam_sales_visit_photo_evidence_capabilities.sql') OR"
                    + " (version='40' AND script='V40__iam_sales_visit_plan_capabilities.sql') OR"
                    + " (version='41' AND script='V41__iam_hide_order_access_navigation.sql') OR"
                    + " (version='42' AND script='V42__iam_crm_customer_permissions.sql') OR"
                    + " (version='43' AND script='V43__iam_hide_crm_customer_360_navigation.sql')"
                    + " OR (version='44' AND"
                    + " script='V44__iam_split_crm_master_data_navigation.sql') OR (version='45'"
                    + " AND script='V45__iam_customer_management_navigation.sql') OR (version='46'"
                    + " AND script='V46__iam_replace_erp_warehouse_navigation.sql') OR"
                    + " (version='47' AND"
                    + " script='V47__iam_hide_legacy_erp_inventory_navigation.sql') OR"
                    + " (version='48' AND script='V48__iam_business_dictionary_permissions.sql') OR"
                    + " (version='49' AND"
                    + " script='V49__iam_grant_dictionary_read_to_business_roles.sql') OR"
                    + " (version='50' AND script='V50__iam_procurement_payment_placeholder.sql') OR"
                    + " (version='51' AND"
                    + " script='V51__iam_hide_unimplemented_supply_navigation.sql') OR"
                    + " (version='52' AND script='V52__iam_business_first_supply_navigation.sql')"
                    + " OR (version='53' AND"
                    + " script='V53__iam_product_specification_business_page.sql') OR (version='54'"
                    + " AND script='V54__iam_delete_legacy_dhb_order_navigation.sql') OR"
                    + " (version='69' AND script='V69__iam_hide_crm_workspace_navigation.sql') OR"
                    + " (version='74' AND"
                    + " script='V74__iam_supply_bi_gross_profit_and_payment_risk_navigation.sql')"
                    + " OR (version='75' AND"
                    + " script='V75__iam_supply_bi_operating_dashboard_navigation.sql') OR"
                    + " (version='76' AND"
                    + " script='V76__iam_restore_supply_bi_operating_dashboard_children.sql'))",
                24);
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT table_name FROM information_schema.tables WHERE"
                                        + " table_schema=DATABASE() AND table_name LIKE 'iam\\_%'",
                                String.class))
                .contains(
                        "iam_user",
                        "iam_resource",
                        "iam_staff_profile",
                        "iam_staff_assignment",
                        "iam_position");
        assertCount("SELECT COUNT(*) FROM iam_application", 6);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iam_resource", Integer.class))
                .isPositive();
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT permission_code FROM iam_resource "
                                        + "WHERE permission_code IS NOT NULL",
                                String.class))
                .isNotEmpty()
                .doesNotHaveDuplicates();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM iam_package_resource", Integer.class))
                .isPositive();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM iam_resource_ui", Integer.class))
                .isPositive();
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE permission_code IN ("
                        + "'business-settings:dict:read','business-settings:dict:write') "
                        + "AND status='ACTIVE'",
                2);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource resource_record JOIN iam_resource_ui ui_record"
                    + " ON ui_record.resource_id=resource_record.id WHERE"
                    + " resource_record.resource_code='SUPPLY_CHAIN.PAGE.SETTINGS_NUMBERING_DICTIONARIES'"
                    + " AND resource_record.display_name='数据字典' AND resource_record.status='ACTIVE'"
                    + " AND ui_record.visible=1",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_application WHERE app_code='PLATFORM_ADMIN' AND"
                        + " target_uri='/platform-admin'",
                1);
        assertCount("SELECT COUNT(*) FROM iam_application WHERE app_code<>'SUPPLY_CHAIN' AND status='ACTIVE'", 0);
        assertCount("SELECT COUNT(*) FROM iam_resource r JOIN iam_application a ON a.id=r.application_id WHERE a.app_code<>'SUPPLY_CHAIN' AND r.status='ACTIVE'", 0);
        assertCount("SELECT COUNT(*) FROM iam_resource_ui ui JOIN iam_resource r ON r.id=ui.resource_id JOIN iam_application a ON a.id=r.application_id WHERE a.app_code<>'SUPPLY_CHAIN' AND ui.visible=1", 0);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iam_resource r JOIN iam_application a ON a.id=r.application_id WHERE a.app_code='SUPPLY_CHAIN' AND r.permission_code LIKE 'sales:%' AND r.status='ACTIVE'", Integer.class)).isPositive();
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE status='ACTIVE' AND ("
                        + "id IN (UUID_TO_BIN('019facf2-0000-7000-8000-000000000057'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000058')) OR id BETWEEN "
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000121') AND "
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000166'))",
                48);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE status='ACTIVE' AND permission_code IN ("
                    + "'sales:evidence:own:read','sales:evidence:own:write','sales:evidence:sensitive:read')",
                3);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE status='ACTIVE' AND permission_code IN ("
                    + "'sales:visit-plan:own:read','sales:visit-plan:read','sales:visit-plan:write')",
                3);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE status='ACTIVE' AND permission_code IN ("
                    + "'erp:product:read','erp:product:write','erp:supply:read','erp:supply:write')",
                4);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE status='ACTIVE' AND resource_code IN ("
                        + "'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_TAGS',"
                        + "'SUPPLY_CHAIN.MENU.ERP_MASTER_DATA_ATTRIBUTES',"
                        + "'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_SYNC')",
                3);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE"
                        + " parent_id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000049') AND"
                        + " display_name IN ('供应链首页','ERP','CRM','订单管理','销售管理','城市运营','数据看板',"
                        + "'人事与绩效','渠道代理','外部同步','系统设置')",
                11);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key='supply.order.menu' "
                        + "AND route_path IS NULL AND visible=1",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key='supply.order.sales-orders' "
                        + "AND route_path='/supply-chain/order/sales-orders' AND visible=1",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key LIKE 'supply.order.%' AND"
                    + " route_key NOT IN"
                    + " ('supply.order.menu','supply.order.fulfillments','supply.order.sales-orders',"
                    + "'supply.order.lines',"
                    + "'supply.order.shipments','supply.order.sales-payments','supply.order.sales-refunds',"
                    + "'supply.order.fund-documents')",
                0);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key='supply.order.lines' "
                        + "AND route_path='/supply-chain/order/lines' AND visible=1",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key IN ("
                    + "'supply.order.shipments','supply.order.sales-payments','supply.order.sales-refunds','supply.order.fund-documents')"
                    + " AND visible=1",
                4);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key LIKE 'supply.dinghuobao.%' OR"
                    + " route_key IN ("
                    + "'supply.integration.raw-data','supply.integration.connections',"
                    + "'supply.integration.sync-tasks','supply.integration.sync-logs',"
                    + "'supply.integration.retries','supply.integration.field-mappings',"
                    + "'supply.integration.reconciliation','supply.integration.sovereignty',"
                    + "'supply.integration.sync-batches','supply.integration.external-id-mappings')",
                0);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE id IN ("
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000056'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000085'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000086'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000087'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000088'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000089'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000090'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000091'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000102'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000104'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000105'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000106'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000107'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000108'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000109'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000110'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000111'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000112'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000113'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000114'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000115'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000116'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000117'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000118'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000235'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000236'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000237'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000238'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000228'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000229'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000266'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000267'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000276'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000277'))",
                0);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE resource_id=UUID_TO_BIN("
                        + "'019facf2-0000-7000-8000-000000000203') AND visible=0",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE "
                        + "(id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000292') "
                        + "AND parent_id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000199') "
                        + "AND display_name='归属地区')",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key IN ("
                        + "'supply.crm.customers.shipping-addresses',"
                        + "'supply.crm.customers.levels-tags',"
                        + "'supply.crm.customers.areas') "
                        + "AND visible=1",
                3);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key="
                        + "'supply.crm.index' AND route_path='/supply-chain/crm' AND visible=0",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key="
                        + "'supply.crm.assignments.external-staff'",
                0);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE id=UUID_TO_BIN("
                        + "'019facf2-0000-7000-8000-000000000199') AND parent_id=UUID_TO_BIN("
                        + "'019facf2-0000-7000-8000-000000000053') AND display_name='客户管理'",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE id=UUID_TO_BIN("
                        + "'019facf2-0000-7000-8000-000000000200') AND parent_id=UUID_TO_BIN("
                        + "'019facf2-0000-7000-8000-000000000199') AND display_name='客户档案'",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE id IN ("
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000200'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000202'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000292'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000294'))"
                        + " AND display_name IN ('客户档案','客户地址','客户类型','归属地区')",
                4);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key="
                        + "'supply.crm.customers.shipping-addresses' AND visible=1",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource resource_record JOIN iam_resource_ui ui_record"
                    + " ON ui_record.resource_id=resource_record.id WHERE"
                    + " resource_record.id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000297') AND"
                    + " resource_record.parent_id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000177')"
                    + " AND resource_record.display_name='采购付款单' AND"
                    + " ui_record.route_key='supply.erp.procurement.payments' AND"
                    + " ui_record.route_path='/supply-chain/erp/procurement/payments' AND"
                    + " ui_record.visible=1",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key IN ("
                    + "'supply.erp.master-data.products','supply.erp.master-data.attributes.categories',"
                    + "'supply.erp.master-data.attributes.brands','supply.erp.master-data.attributes.tags',"
                    + "'supply.erp.master-data.attributes.specifications',"
                    + "'supply.erp.suppliers.profiles','supply.erp.procurement.orders',"
                    + "'supply.erp.procurement.payments','supply.erp.inventory.inventory',"
                    + "'supply.erp.inventory.inbound','supply.erp.inventory.outbound','supply.erp.inventory.transfers','supply.erp.inventory.warehouses')"
                    + " AND visible=1",
                13);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key IN ("
                        + "'supply.erp.suppliers.products','supply.erp.procurement.receipts',"
                        + "'supply.crm.credit-policy.limits') "
                        + "AND visible=0",
                3);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE id IN ("
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000374'),"
                        + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000375')) "
                        + "AND resource_code IN ('SUPPLY_CHAIN.PAGE.BI_GROSS_PROFIT',"
                        + "'SUPPLY_CHAIN.PAGE.BI_PAYMENT_RISK') "
                        + "AND display_name IN ('销售毛利分析','回款风险看板') "
                        + "AND parent_id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000065') "
                        + "AND status='ACTIVE'",
                2);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key IN ("
                        + "'supply.bi.gross-profit','supply.bi.payment-risk') "
                        + "AND route_path IN ('/supply-chain/bi/gross-profit',"
                        + "'/supply-chain/bi/payment-risk') "
                        + "AND visible=1",
                2);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE id IN ("
                    + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000240'),"
                    + "UUID_TO_BIN('019facf2-0000-7000-8000-000000000377'),UUID_TO_BIN('019facf2-0000-7000-8000-000000000378'))"
                    + " AND resource_code IN"
                    + " ('SUPPLY_CHAIN.PAGE.BI_SALES','SUPPLY_CHAIN.PAGE.BI_ACTIVITY','SUPPLY_CHAIN.PAGE.BI_PRODUCT_INVENTORY')"
                    + " AND display_name IN ('销售看板','活动看板','商品/库存看板') AND"
                    + " parent_id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000065') AND"
                    + " status='ACTIVE'",
                3);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource WHERE id=UUID_TO_BIN("
                        + "'019facf2-0000-7000-8000-000000000376')",
                0);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key IN ("
                        + "'supply.bi.sales','supply.bi.activity','supply.bi.product-inventory') "
                        + "AND route_path IN ('/supply-chain/bi/sales','/supply-chain/bi/activity',"
                        + "'/supply-chain/bi/product-inventory') "
                        + "AND visible=1",
                3);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key='supply.bi.city-operating' "
                        + "AND route_path='/supply-chain/bi/city-operating' "
                        + "AND visible=1",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key LIKE 'supply.bi.%' "
                        + "AND visible=1",
                14);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key IN"
                    + " ('supply.bi.product-sales','supply.bi.payment-risk','supply.bi.inventory-risk')"
                    + " AND visible=1",
                3);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key='supply.bi.sales-collection' "
                        + "AND visible=0",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key='supply.bi.customer' "
                        + "AND route_path='/supply-chain/bi/customer' AND visible=1",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key LIKE 'supply.integration.%'",
                4);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE"
                        + " route_key='supply.integration.feishu-import' AND"
                        + " route_path='/supply-chain/integration/feishu-import' AND visible=1",
                1);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource_ui WHERE route_key IN ("
                        + "'supply.city.menu','supply.erp.index','supply.sales.attendance.menu',"
                        + "'supply.settings.product-inventory') "
                        + "AND visible=0",
                4);
        assertCount(
                "SELECT COUNT(*) FROM iam_resource resource_record JOIN iam_resource_ui ui_record"
                        + " ON ui_record.resource_id=resource_record.id WHERE"
                        + " (ui_record.route_key='supply.integration.sync-control.menu' AND"
                        + " resource_record.display_name='同步控制') OR"
                        + " (ui_record.route_key='supply.integration.overview' AND"
                        + " resource_record.display_name='订货宝同步中心')",
                2);
        org.assertj.core.api.Assertions.assertThat(
                        applicationMapper.selectById(
                                UUID.fromString("019facf1-0000-7000-8000-000000000003")))
                .extracting("appCode")
                .isEqualTo("SUPPLY_CHAIN");
        org.assertj.core.api.Assertions.assertThat(applicationMapper.selectActiveByScope("TENANT"))
                .extracting("appCode")
                .containsExactly("SUPPLY_CHAIN");
        assertCount(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'iam_refresh_token' "
                        + "AND column_name = 'authorization_id'",
                1);
        assertCount(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema"
                    + " = DATABASE() AND table_name = 'iam_oauth_authorization' AND constraint_type"
                    + " = 'FOREIGN KEY'",
                2);
        assertCount(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema"
                        + " = DATABASE() AND table_name = 'iam_oauth_client' AND constraint_name ="
                        + " 'uk_iam_oauth_client_id'",
                1);
        assertCount(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name IN ('iam_oauth_authorization', 'iam_refresh_token') "
                        + "AND column_name IN ('authorization_code_value', 'access_token_value', "
                        + "'oidc_id_token_value', 'refresh_token_value')",
                0);
    }

    @Test
    void savesAndSoftRevokesAuthorizationConsent() {
        RegisteredClient client = scdpClient();
        registeredClientRepository.save(client);
        OAuth2AuthorizationConsent consent =
                OAuth2AuthorizationConsent.withId(client.getId(), "sales-user-001")
                        .scope(OidcScopes.OPENID)
                        .scope(OidcScopes.PROFILE)
                        .authority(new SimpleGrantedAuthority("ROLE_SCDP_USER"))
                        .build();

        authorizationConsentService.save(consent);

        assertThat(authorizationConsentService.findById(client.getId(), "sales-user-001"))
                .isEqualTo(consent);
        authorizationConsentService.remove(consent);
        assertThat(authorizationConsentService.findById(client.getId(), "sales-user-001")).isNull();
        assertCount("SELECT COUNT(*) FROM iam_oauth_consent WHERE revoked_at IS NOT NULL", 1);
    }

    @Test
    void refusesToPersistPlaintextClientSecret() {
        RegisteredClient confidentialClient =
                RegisteredClient.from(scdpClient())
                        .clientAuthenticationMethods(
                                methods -> {
                                    methods.clear();
                                    methods.add(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
                                })
                        .clientSecret("plaintext-secret")
                        .clientSettings(
                                ClientSettings.builder()
                                        .requireProofKey(false)
                                        .requireAuthorizationConsent(false)
                                        .build())
                        .build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registeredClientRepository.save(confidentialClient))
                .withMessageContaining("pre-encoded Argon2id");
    }

    @Test
    void persistsEncryptedAuthorizationAttributesAndFindsOnlyByStateHash() {
        RegisteredClient client = scdpClient();
        registeredClientRepository.save(client);
        SessionFixture session = insertActiveSession();
        String internalState = "consent-state-that-must-not-be-stored";
        OAuth2Authorization authorization = pendingAuthorization(client, session, internalState);

        authorizationService.save(authorization);

        OAuth2Authorization loadedByState =
                authorizationService.findByToken(internalState, STATE_TOKEN_TYPE);
        assertThat(loadedByState).isNotNull();
        assertThat(loadedByState.<String>getAttribute(OAuth2ParameterNames.STATE))
                .isEqualTo(internalState);
        assertThat(
                        loadedByState.<OAuth2AuthorizationRequest>getAttribute(
                                OAuth2AuthorizationRequest.class.getName()))
                .isEqualTo(authorization.getAttribute(OAuth2AuthorizationRequest.class.getName()));
        assertThat(
                        loadedByState
                                .<org.springframework.security.core.Authentication>getAttribute(
                                        Principal.class.getName())
                                .getName())
                .isEqualTo(authorization.getPrincipalName());

        OAuth2Authorization loadedById = authorizationService.findById(authorization.getId());
        assertThat(loadedById).isNotNull();
        assertThat(loadedById.<Object>getAttribute(OAuth2ParameterNames.STATE)).isNull();
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT COUNT(*) FROM iam_oauth_authorization
                                WHERE id = ? AND state_hash = UNHEX(SHA2(?, 256))
                                  AND attributes_key_version = 'v1'
                                  AND attributes_ciphertext IS NOT NULL
                                """,
                                Integer.class,
                                uuidBytes(UUID.fromString(authorization.getId())),
                                internalState))
                .isEqualTo(1);
        byte[] ciphertext =
                jdbcTemplate.queryForObject(
                        "SELECT attributes_ciphertext FROM iam_oauth_authorization WHERE id = ?",
                        byte[].class,
                        uuidBytes(UUID.fromString(authorization.getId())));
        assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain(internalState);
    }

    @Test
    void consumesAuthorizationCodeOnceAndSoftRevokesAuthorization() {
        RegisteredClient client = scdpClient();
        registeredClientRepository.save(client);
        SessionFixture session = insertActiveSession();
        String rawCode = "authorization-code-that-must-not-be-stored";
        Instant issuedAt = Instant.now().minusSeconds(5);
        OAuth2AuthorizationCode code =
                new OAuth2AuthorizationCode(rawCode, issuedAt, issuedAt.plusSeconds(300));
        OAuth2Authorization authorization = authorizationWithCode(client, session, code);

        authorizationService.save(authorization);

        OAuth2Authorization loaded = authorizationService.findByToken(rawCode, CODE_TOKEN_TYPE);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getToken(OAuth2AuthorizationCode.class).getToken().getTokenValue())
                .isEqualTo(rawCode);
        assertThat(loaded.getToken(OAuth2AuthorizationCode.class).isActive()).isTrue();
        OAuth2Authorization consumed =
                OAuth2Authorization.from(loaded)
                        .invalidate(loaded.getToken(OAuth2AuthorizationCode.class).getToken())
                        .build();
        authorizationService.save(consumed);

        OAuth2Authorization loadedAfterConsumption =
                authorizationService.findByToken(rawCode, CODE_TOKEN_TYPE);
        assertThat(loadedAfterConsumption.getToken(OAuth2AuthorizationCode.class).isInvalidated())
                .isTrue();
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT COUNT(*) FROM iam_oauth_authorization
                                WHERE id = ? AND authorization_code_hash = UNHEX(SHA2(?, 256))
                                  AND code_consumed_at IS NOT NULL
                                """,
                                Integer.class,
                                uuidBytes(UUID.fromString(authorization.getId())),
                                rawCode))
                .isEqualTo(1);

        authorizationService.remove(loadedAfterConsumption);
        assertThat(authorizationService.findByToken(rawCode, CODE_TOKEN_TYPE)).isNull();
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
        SELECT COUNT(*) FROM iam_oauth_authorization
        WHERE id = ? AND status = 'REVOKED' AND revoked_at IS NOT NULL
""",
                                Integer.class,
                                uuidBytes(UUID.fromString(authorization.getId()))))
                .isEqualTo(1);
    }

    @Test
    void rotatesHashedRefreshTokenWithoutInvalidatingSessionAndRevokesSessionOnReplay() {
        RegisteredClient client = scdpClient();
        registeredClientRepository.save(client);
        SessionFixture session = insertActiveSession();
        Instant issuedAt = Instant.now().minusSeconds(2);
        OAuth2AuthorizationCode code =
                new OAuth2AuthorizationCode(
                        "refresh-flow-authorization-code", issuedAt, issuedAt.plusSeconds(300));
        OAuth2Authorization codeAuthorization = authorizationWithCode(client, session, code);
        authorizationService.save(codeAuthorization);

        OAuth2Authorization loadedByCode =
                authorizationService.findByToken(code.getTokenValue(), CODE_TOKEN_TYPE);
        String firstRawRefreshToken = "first-refresh-token-that-must-not-be-stored";
        OAuth2Authorization firstIssued =
                OAuth2Authorization.from(loadedByCode)
                        .invalidate(loadedByCode.getToken(OAuth2AuthorizationCode.class).getToken())
                        .accessToken(
                                new OAuth2AccessToken(
                                        OAuth2AccessToken.TokenType.BEARER,
                                        "self-contained-access-token-one",
                                        issuedAt,
                                        issuedAt.plusSeconds(900),
                                        Set.of("openid", "profile")))
                        .refreshToken(
                                new OAuth2RefreshToken(
                                        firstRawRefreshToken,
                                        issuedAt,
                                        issuedAt.plusSeconds(604800)))
                        .build();
        authorizationService.save(firstIssued);

        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT COUNT(*) FROM iam_refresh_token
                                 WHERE authorization_id = ? AND token_hash = UNHEX(SHA2(?, 256))
                                   AND consumed_at IS NULL AND revoked_at IS NULL
                                """,
                                Integer.class,
                                uuidBytes(UUID.fromString(firstIssued.getId())),
                                firstRawRefreshToken))
                .isEqualTo(1);
        OAuth2Authorization loadedByRefresh =
                authorizationService.findByToken(
                        firstRawRefreshToken, OAuth2TokenType.REFRESH_TOKEN);
        assertThat(loadedByRefresh).isNotNull();
        assertThat(loadedByRefresh.getRefreshToken().getToken().getTokenValue())
                .isEqualTo(firstRawRefreshToken);
        Long sessionVersionBeforeRotation =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM iam_auth_session WHERE id = ?",
                        Long.class,
                        uuidBytes(session.sessionId()));

        String secondRawRefreshToken = "second-refresh-token-that-must-not-be-stored";
        Instant rotatedAt = Instant.now();
        OAuth2Authorization rotated =
                OAuth2Authorization.from(loadedByRefresh)
                        .accessToken(
                                new OAuth2AccessToken(
                                        OAuth2AccessToken.TokenType.BEARER,
                                        "self-contained-access-token-two",
                                        rotatedAt,
                                        rotatedAt.plusSeconds(900),
                                        Set.of("openid", "profile")))
                        .refreshToken(
                                new OAuth2RefreshToken(
                                        secondRawRefreshToken,
                                        rotatedAt,
                                        rotatedAt.plusSeconds(604800)))
                        .build();
        authorizationService.save(rotated);

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT version FROM iam_auth_session WHERE id = ?",
                                Long.class,
                                uuidBytes(session.sessionId())))
                .as("normal refresh rotation must not make its newly-issued access token stale")
                .isEqualTo(sessionVersionBeforeRotation);

        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT COUNT(*) FROM iam_refresh_token old_token
  JOIN iam_refresh_token new_token ON new_token.id = old_token.replaced_by_id
 WHERE old_token.authorization_id = ?
   AND old_token.token_hash = UNHEX(SHA2(?, 256))
   AND old_token.consumed_at IS NOT NULL
   AND new_token.token_hash = UNHEX(SHA2(?, 256))
""",
                                Integer.class,
                                uuidBytes(UUID.fromString(rotated.getId())),
                                firstRawRefreshToken,
                                secondRawRefreshToken))
                .isEqualTo(1);

        assertThat(
                        authorizationService.findByToken(
                                firstRawRefreshToken, OAuth2TokenType.REFRESH_TOKEN))
                .isNull();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status FROM iam_auth_session WHERE id = ?",
                                String.class,
                                uuidBytes(session.sessionId())))
                .isEqualTo("REVOKED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status FROM iam_oauth_authorization WHERE id = ?",
                                String.class,
                                uuidBytes(UUID.fromString(rotated.getId()))))
                .isEqualTo("REVOKED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
                                SELECT COUNT(*) FROM iam_refresh_token
                                 WHERE authorization_id = ? AND revoked_at IS NOT NULL
                                """,
                                Integer.class,
                                uuidBytes(UUID.fromString(rotated.getId()))))
                .isEqualTo(2);
    }

    @Test
    void loadsRestrictedRsa3072KeyByReferenceAndSignsWithCatalogKid() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair keyPair = generator.generateKeyPair();
        String kid = "test-signing-" + UUID.randomUUID();
        Path privateKeyFile = temporaryDirectory.resolve(kid + ".pem");
        String privateKeyPem =
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder(64, new byte[] {'\n'})
                                .encodeToString(keyPair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(privateKeyFile, privateKeyPem, StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(
                    privateKeyFile,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // 测试文件系统不支持POSIX权限时，仍验证绝对路径和非符号链接规则。
        }

        RSAKey publicJwk =
                new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                        .keyID(kid)
                        .keyUse(KeyUse.SIGNATURE)
                        .algorithm(JWSAlgorithm.RS256)
                        .build();
        UUID keyId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO iam_signing_key (
                    id, kid, algorithm, key_use, public_jwk_json, private_key_ref, status,
                    not_before, not_after, activated_at, created_at
                ) VALUES (?, ?, 'RS256', 'sig', ?, ?, 'ACTIVE', ?, ?, ?, ?)
                """,
                uuidBytes(keyId),
                kid,
                publicJwk.toJSONString(),
                "file:" + privateKeyFile.toAbsolutePath(),
                now.minusMinutes(1),
                now.plusDays(1),
                now,
                now);
        try {
            JdbcRsaJwkSource jwkSource =
                    new JdbcRsaJwkSource(jdbcTemplate, new PrivateKeyReferenceResolver(), 3072);
            assertThat(
                            jwkSource.get(
                                    new JWKSelector(
                                            new JWKMatcher.Builder()
                                                    .keyID(kid)
                                                    .algorithm(JWSAlgorithm.RS256)
                                                    .privateOnly(true)
                                                    .build()),
                                    null))
                    .singleElement()
                    .satisfies(jwk -> assertThat(jwk.isPrivate()).isTrue());

            NimbusJwtEncoder encoder = new NimbusJwtEncoder(jwkSource);
            Instant issuedAt = Instant.now();
            Jwt jwt =
                    encoder.encode(
                            JwtEncoderParameters.from(
                                    JwsHeader.with(SignatureAlgorithm.RS256).keyId(kid).build(),
                                    JwtClaimsSet.builder()
                                            .issuer("https://iam.dev.rigour.local")
                                            .subject(UUID.randomUUID().toString())
                                            .issuedAt(issuedAt)
                                            .expiresAt(issuedAt.plusSeconds(60))
                                            .build()));
            assertThat(jwt.getHeaders()).containsEntry("kid", kid);
            Jwt decoded =
                    NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic())
                            .signatureAlgorithm(SignatureAlgorithm.RS256)
                            .build()
                            .decode(jwt.getTokenValue());
            assertThat(decoded.getIssuer().toString()).isEqualTo("https://iam.dev.rigour.local");
        } finally {
            jdbcTemplate.update("DELETE FROM iam_signing_key WHERE id = ?", uuidBytes(keyId));
        }
    }

    @Test
    void rejectsAuthorizationWhenSessionPrincipalDoesNotMatch() {
        RegisteredClient client = scdpClient();
        registeredClientRepository.save(client);
        SessionFixture session = insertActiveSession();
        UsernamePasswordAuthenticationToken mismatchedPrincipal =
                UsernamePasswordAuthenticationToken.authenticated(
                        UUID.randomUUID().toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_SCDP_USER")));
        mismatchedPrincipal.setDetails(
                IamAuthenticationDetails.create(
                        session.sessionId(),
                        PrincipalScope.PLATFORM.name(),
                        session.principalId(),
                        null,
                        0));
        OAuth2Authorization authorization = baseAuthorization(client, mismatchedPrincipal).build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> authorizationService.save(authorization))
                .withMessageContaining("does not match");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM iam_oauth_authorization WHERE id = ?",
                                Integer.class,
                                uuidBytes(UUID.fromString(authorization.getId()))))
                .isZero();
    }

    @Test
    void rejectsRetiredPlatformLoginWithoutCreatingSession() {
        String username = "retired-platform-" + UUID.randomUUID();
        UUID principal = insertPlatformIdentity(username, "Password-42!", "ACTIVE", "ACTIVE");
        assertUniformFailure(loginToken(PrincipalScope.PLATFORM, null, username, "Password-42!"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM iam_auth_session WHERE principal_id=?", Integer.class, uuidBytes(principal))).isZero();
    }

    @Test
    void authenticatesTenantOnlyWithinRequestedTenant() {
        String username = "tenant-user-" + UUID.randomUUID();
        String password = "Tenant-Password-42!";
        TenantIdentityFixture tenant =
                insertTenantIdentity(username, password, "ACTIVE", "ACTIVE", "ACTIVE");

        IamLoginAuthenticationToken correct =
                loginToken(PrincipalScope.TENANT, tenant.tenantCode(), username, password);
        org.springframework.security.core.Authentication authenticated =
                authenticationProvider.authenticate(correct);
        @SuppressWarnings("unchecked")
        Map<String, String> tenantDetails = (Map<String, String>) authenticated.getDetails();
        assertThat(tenantDetails)
                .containsEntry(IamAuthenticationDetails.TENANT_ID, tenant.tenantId().toString());

        IamLoginAuthenticationToken crossTenant =
                loginToken(PrincipalScope.TENANT, "another-tenant", username, password);
        assertThatThrownBy(() -> authenticationProvider.authenticate(crossTenant))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Authentication failed");
        assertThat(crossTenant.getCredentials()).isNull();
    }

    @Test
    void commitsFailureCountAndLocksCredentialBeforeReturningUniformFailure() {
        String username = "locked-user-" + UUID.randomUUID();
        String password = "Correct-Password-42!";
        var fixture = insertTenantIdentity(username, password, "ACTIVE", "ACTIVE", "ACTIVE");
        UUID principalId = fixture.userId();

        for (int attempt = 0; attempt < 5; attempt++) {
            IamLoginAuthenticationToken request =
                    loginToken(PrincipalScope.TENANT, fixture.tenantCode(), username, "Wrong-Password-42!");
            assertThatThrownBy(() -> authenticationProvider.authenticate(request))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessage("Authentication failed");
        }

        Map<String, Object> lockState =
                jdbcTemplate.queryForMap(
                        """
                        SELECT c.failed_attempts, c.locked_until
                          FROM iam_user_credential c
                         WHERE c.user_id = ?
                        """,
                        uuidBytes(principalId));
        assertThat(lockState.get("failed_attempts")).isEqualTo(5L);
        assertThat(lockState.get("locked_until")).isNotNull();
        IamLoginAuthenticationToken lockedRequest =
                loginToken(PrincipalScope.TENANT, fixture.tenantCode(), username, password);
        assertThatThrownBy(() -> authenticationProvider.authenticate(lockedRequest))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Authentication failed");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM iam_auth_session WHERE principal_id = ?",
                                Integer.class,
                                uuidBytes(principalId)))
                .isZero();
    }

    @Test
    void rejectsDisabledPrincipalCredentialAndTenantWithSameExternalFailure() {
        String password = "Disabled-Password-42!";
        String platformUsername = "disabled-platform-" + UUID.randomUUID();
        insertPlatformIdentity(platformUsername, password, "DISABLED", "ACTIVE");
        TenantIdentityFixture suspendedTenant =
                insertTenantIdentity(
                        "suspended-user-" + UUID.randomUUID(),
                        password,
                        "ACTIVE",
                        "ACTIVE",
                        "SUSPENDED");

        assertUniformFailure(loginToken(PrincipalScope.PLATFORM, null, platformUsername, password));
        assertUniformFailure(
                loginToken(
                        PrincipalScope.TENANT,
                        suspendedTenant.tenantCode(),
                        suspendedTenant.username(),
                        password));
        assertUniformFailure(
                loginToken(
                        PrincipalScope.PLATFORM, null, "missing-" + UUID.randomUUID(), password));
    }

    private OAuth2Authorization pendingAuthorization(
            RegisteredClient client, SessionFixture session, String internalState) {
        UsernamePasswordAuthenticationToken principal = authenticatedPrincipal(session);
        return baseAuthorization(client, principal)
                .attribute(OAuth2ParameterNames.STATE, internalState)
                .build();
    }

    private OAuth2Authorization authorizationWithCode(
            RegisteredClient client, SessionFixture session, OAuth2AuthorizationCode code) {
        UsernamePasswordAuthenticationToken principal = authenticatedPrincipal(session);
        return baseAuthorization(client, principal)
                .authorizedScopes(Set.of(OidcScopes.OPENID, OidcScopes.PROFILE))
                .token(code)
                .build();
    }

    private OAuth2Authorization.Builder baseAuthorization(
            RegisteredClient client, UsernamePasswordAuthenticationToken principal) {
        OAuth2AuthorizationRequest authorizationRequest =
                OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri("https://iam.dev.rigour.local/oauth2/authorize")
                        .clientId(client.getClientId())
                        .redirectUri(client.getRedirectUris().iterator().next())
                        .scopes(Set.of(OidcScopes.OPENID, OidcScopes.PROFILE))
                        .state("browser-state-inside-encrypted-context")
                        .additionalParameters(
                                Map.of(
                                        PkceParameterNames.CODE_CHALLENGE, "test-code-challenge",
                                        PkceParameterNames.CODE_CHALLENGE_METHOD, "S256"))
                        .build();
        return OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(principal.getName())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute(Principal.class.getName(), principal)
                .attribute(OAuth2AuthorizationRequest.class.getName(), authorizationRequest);
    }

    private UsernamePasswordAuthenticationToken authenticatedPrincipal(SessionFixture session) {
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        session.principalId().toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_SCDP_USER")));
        authentication.setDetails(
                IamAuthenticationDetails.create(
                        session.sessionId(),
                        PrincipalScope.PLATFORM.name(),
                        session.principalId(),
                        null,
                        0));
        return authentication;
    }

    private SessionFixture insertActiveSession() {
        UUID sessionId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        LocalDateTime issuedAt =
                LocalDateTime.ofInstant(Instant.now().minusSeconds(5), ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO iam_auth_session (
                    id, principal_scope, tenant_id, principal_id, client_type,
                    device_name, client_fingerprint_hash, user_agent_hash, ip_address,
                    issued_at, last_seen_at, expires_at, revoked_at, revoke_reason,
                    status, version
                ) VALUES (?, 'PLATFORM', NULL, ?, 'WEB', 'integration-test', NULL, NULL, NULL,
                          ?, ?, ?, NULL, NULL, 'ACTIVE', 0)
                """,
                uuidBytes(sessionId),
                uuidBytes(principalId),
                issuedAt,
                issuedAt,
                issuedAt.plusHours(1));
        return new SessionFixture(sessionId, principalId);
    }

    private static String testEncryptionKeyBase64() {
        byte[] testKey = new byte[32];
        Arrays.fill(testKey, (byte) 0x5A);
        return Base64.getEncoder().encodeToString(testKey);
    }

    private byte[] uuidBytes(UUID value) {
        return java.nio.ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private record SessionFixture(UUID sessionId, UUID principalId) {}

    private IamLoginAuthenticationToken loginToken(
            PrincipalScope scope, String tenantCode, String username, String password) {
        return new IamLoginAuthenticationToken(
                scope,
                tenantCode,
                username,
                password.toCharArray(),
                ClientType.WEB,
                "integration-test",
                null,
                null,
                new byte[] {127, 0, 0, 1});
    }

    private UUID insertPlatformIdentity(
            String username, String password, String userStatus, String credentialStatus) {
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO iam_platform_user (
                    id, username, display_name, platform_role, status, security_version, version,
                    created_at, updated_at
                ) VALUES (?, ?, ?, 'SUPER_ADMIN', ?, 0, 0, ?, ?)
                """,
                uuidBytes(userId),
                username,
                "Platform Test User",
                userStatus,
                now,
                now);
        jdbcTemplate.update(
                """
INSERT INTO iam_platform_user_credential (
    id, platform_user_id, credential_type, password_hash, algorithm, algorithm_version,
    failed_attempts, password_changed_at, status, version, created_at, updated_at
) VALUES (?, ?, 'PASSWORD', ?, 'ARGON2ID', 1, 0, ?, ?, 0, ?, ?)
""",
                uuidBytes(credentialId),
                uuidBytes(userId),
                passwordHasher.hash(password),
                now,
                credentialStatus,
                now,
                now);
        return userId;
    }

    private TenantIdentityFixture insertTenantIdentity(
            String username,
            String password,
            String userStatus,
            String credentialStatus,
            String tenantStatus) {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        String tenantCode = "tenant-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
INSERT INTO iam_tenant (
    id, tenant_code, company_name, status, policy_version, version, created_at, updated_at
) VALUES (?, ?, 'Test Tenant', ?, 0, 0, ?, ?)
""",
                uuidBytes(tenantId),
                tenantCode,
                tenantStatus,
                now,
                now);
        jdbcTemplate.update(
                """
                INSERT INTO iam_user (
                    id, tenant_id, username, display_name, status, security_version, version,
                    created_at, updated_at
                ) VALUES (?, ?, ?, 'Tenant Test User', ?, 0, 0, ?, ?)
                """,
                uuidBytes(userId),
                uuidBytes(tenantId),
                username,
                userStatus,
                now,
                now);
        jdbcTemplate.update(
                """
INSERT INTO iam_user_credential (
    id, tenant_id, user_id, credential_type, password_hash, algorithm, algorithm_version,
    failed_attempts, password_changed_at, status, version, created_at, updated_at
) VALUES (?, ?, ?, 'PASSWORD', ?, 'ARGON2ID', 1, 0, ?, ?, 0, ?, ?)
""",
                uuidBytes(credentialId),
                uuidBytes(tenantId),
                uuidBytes(userId),
                passwordHasher.hash(password),
                now,
                credentialStatus,
                now,
                now);
        return new TenantIdentityFixture(tenantId, tenantCode, userId, username);
    }

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.rigour.tenant.iam.application.port.out.AppReadinessClient readiness;

    @Test
    void activationRechecksVersionsAndRefusesUnavailableDomains() {
        var f = insertTenantAdministrator();
        appSettingsStore.initialize(f.actor());
        var healthy =
                java.util.List.of("hr", "crm", "erp", "order", "bi", "settings").stream()
                        .map(
                                d ->
                                        new com.rigour.tenant.iam.application.service.settings
                                                .AppCutoverModels.Domain(
                                                d, "v1", java.util.List.of()))
                        .toList();
        org.mockito.Mockito.when(readiness.inspect(f.actor().tenantId())).thenReturn(healthy);
        var first = cutover.inspect(f.actor());
        assertThat(first.ready()).as(first.issues().toString()).isTrue();
        var unhealthy = new java.util.ArrayList<>(healthy);
        unhealthy.set(
                0,
                new com.rigour.tenant.iam.application.service.settings.AppCutoverModels.Domain(
                        "hr",
                        "UNAVAILABLE",
                        java.util.List.of(
                                new com.rigour.tenant.iam.application.service.settings
                                        .AppCutoverModels.Issue(
                                        "SERVICE_hr", "BLOCKING", 1, "无法核验 HR"))));
        org.mockito.Mockito.when(readiness.inspect(f.actor().tenantId())).thenReturn(unhealthy);
        assertThatThrownBy(
                        () ->
                                cutover.activate(
                                        f.actor(),
                                        new com.rigour.tenant.iam.application.service.settings
                                                .AppCutoverModels.Command(
                                                first.version(),
                                                first.fingerprint(),
                                                "已核对权限配置",
                                                true)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(appSettingsStore.context(f.actor()).mode()).isEqualTo("PREPARING");
        org.mockito.Mockito.when(readiness.inspect(f.actor().tenantId())).thenReturn(healthy);
        var result =
                cutover.activate(
                        f.actor(),
                        new com.rigour.tenant.iam.application.service.settings.AppCutoverModels
                                .Command(first.version(), first.fingerprint(), "已核对权限配置", true));
        assertThat(result.mode()).isEqualTo("ACTIVE");
        assertThat(result.version()).isGreaterThan(first.version());
        assertThat(
                        appSettingsStore
                                .audits(f.actor(), "AUTHORIZATION_ACTIVATE", null, 1, 20)
                                .total())
                .isEqualTo(1);
    }

    @Test
    void permissionPreviewUsesSavedApplicationRulesWithoutActivatingOrCrossingTenants() {
        var first = insertTenantAdministrator();
        var other = insertTenantAdministrator();
        appSettingsStore.initialize(first.actor());
        appSettingsStore.initialize(other.actor());
        var before = appSettingsStore.context(first.actor());
        var preview =
                cutover.preview(first.actor(), first.actor().principalId(), "crm:customer:read");
        assertThat(preview.policy().functionAllowed()).isFalse();
        assertThat(preview.policy().clauses()).isEmpty();
        assertThat(
                        cutover.preview(
                                        first.actor(),
                                        first.actor().principalId(),
                                        "supply:menu:read")
                                .policy()
                                .functionAllowed())
                .isTrue();
        assertThat(preview.proposedPermissions())
                .contains("supply:menu:read")
                .doesNotContain("crm:customer:read");
        assertThat(preview.applicationVersion()).isEqualTo(before.version());
        assertThat(appSettingsStore.context(first.actor()).mode()).isEqualTo("PREPARING");
        assertThatThrownBy(
                        () ->
                                cutover.preview(
                                        other.actor(),
                                        first.actor().principalId(),
                                        "crm:customer:read"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Autowired private com.rigour.tenant.iam.application.port.out.AppSettingsStore appSettingsStore;
    @Autowired private com.rigour.tenant.iam.application.port.out.AppCutoverStore cutover;

    @Test
    void supplySettingsPersistMenuHierarchyAndProtectTenantBoundaries() {
        var first = insertTenantAdministrator();
        var second = insertTenantAdministrator();
        assertThat(appSettingsStore.context(first.actor()).canInitialize()).isTrue();
        appSettingsStore.initialize(first.actor());
        appSettingsStore.initialize(second.actor());
        var context = appSettingsStore.context(first.actor());
        assertThat(context.permissions())
                .contains("supply:menu:read", "supply:menu:create", "supply:role:grant");
        var before = jdbcTemplate.queryForList("SELECT HEX(id) AS id, username, status FROM iam_user WHERE tenant_id=? ORDER BY id", uuidBytes(first.actor().tenantId()));
        var folder =
                appSettingsStore.saveMenu(
                        first.actor(),
                        null,
                        new com.rigour.tenant.iam.application.service.settings.AppSettingsModels
                                .MenuCommand(
                                null,
                                "MENU",
                                null,
                                "自定义供应链目录",
                                "Folder",
                                3,
                                true,
                                "ACTIVE",
                                0,
                                null,
                                null,
                                null,
                                null));
        var page =
                appSettingsStore.menus(first.actor()).stream()
                        .filter(n -> "PAGE".equals(n.type()) && !n.protectedNode())
                        .findFirst()
                        .orElseThrow();
        var changed =
                appSettingsStore.saveMenu(
                        first.actor(),
                        page.id(),
                        new com.rigour.tenant.iam.application.service.settings.AppSettingsModels
                                .MenuCommand(
                                folder.id(),
                                page.type(),
                                page.resourceId(),
                                "运营页面自定义名称",
                                "Shop",
                                8,
                                true,
                                "ACTIVE",
                                page.version(),
                                null,
                                null,
                                null,
                                null));
        assertThat(changed.parentId()).isEqualTo(folder.id());
        assertThat(changed.name()).isEqualTo("运营页面自定义名称");
        assertThat(appSettingsStore.navigation(first.actor()))
                .anySatisfy(
                        n -> {
                            assertThat(n.id()).isEqualTo(folder.id());
                            assertThat(n.children())
                                    .extracting(NavigationNode::displayName)
                                    .contains("运营页面自定义名称");
                        });
        assertThatThrownBy(
                        () ->
                                appSettingsStore.saveMenu(
                                        second.actor(),
                                        page.id(),
                                        new com.rigour.tenant.iam.application.service.settings
                                                .AppSettingsModels.MenuCommand(
                                                folder.id(),
                                                page.type(),
                                                page.resourceId(),
                                                "跨租户",
                                                "Shop",
                                                8,
                                                true,
                                                "ACTIVE",
                                                page.version(),
                                                null,
                                                null,
                                                null,
                                                null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForList("SELECT HEX(id) AS id, username, status FROM iam_user WHERE tenant_id=? ORDER BY id", uuidBytes(first.actor().tenantId()))).isEqualTo(before);
        assertThat(appSettingsStore.audits(first.actor(), "MENU_UPDATE", null, 1, 20).total())
                .isEqualTo(1);
    }

    @Test
    void supplyCustomMenuPagesValidatePathsAndStayEditable() {
        var fixture = insertTenantAdministrator();
        appSettingsStore.initialize(fixture.actor());
        var actor = fixture.actor();
        var folder =
                appSettingsStore.saveMenu(
                        actor,
                        null,
                        new com.rigour.tenant.iam.application.service.settings.AppSettingsModels
                                .MenuCommand(
                                null, "MENU", null, "自定义页面目录", "Folder", 5, true, "ACTIVE", 0,
                                null, null, null, null));
        assertThatThrownBy(
                        () ->
                                appSettingsStore.saveMenu(
                                        actor,
                                        null,
                                        customPageCommand(
                                                folder.id(),
                                                "缺少组件路径",
                                                0,
                                                null,
                                                "/supply-chain/custom/audit",
                                                null,
                                                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自定义页面组件路径必须以 supply-chain/ 开头并以 .vue 结尾");
        assertThatThrownBy(
                        () ->
                                appSettingsStore.saveMenu(
                                        actor,
                                        null,
                                        customPageCommand(
                                                folder.id(),
                                                "未填路径",
                                                0,
                                                null,
                                                null,
                                                null,
                                                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请选择已注册页面，或填写自定义页面的路由地址与组件路径");
        assertThatThrownBy(
                        () ->
                                appSettingsStore.saveMenu(
                                        actor,
                                        null,
                                        customPageCommand(
                                                folder.id(),
                                                "外部跳转",
                                                0,
                                                null,
                                                "https://example.com/audit",
                                                "supply-chain/custom/AuditView.vue",
                                                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自定义页面路由地址必须以 /supply-chain/ 开头");
        assertThatThrownBy(
                        () ->
                                appSettingsStore.saveMenu(
                                        actor,
                                        null,
                                        customPageCommand(
                                                folder.id(),
                                                "越界路由",
                                                0,
                                                null,
                                                "/supply-chain/../../etc/passwd",
                                                "supply-chain/custom/AuditView.vue",
                                                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自定义页面路由地址必须以 /supply-chain/ 开头");
        assertThatThrownBy(
                        () ->
                                appSettingsStore.saveMenu(
                                        actor,
                                        null,
                                        customPageCommand(
                                                folder.id(),
                                                "越界组件",
                                                0,
                                                null,
                                                "/supply-chain/custom/audit",
                                                "../views/SecretView.vue",
                                                "audit:read")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("自定义页面组件路径必须以 supply-chain/ 开头并以 .vue 结尾");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM iam_app_menu_node WHERE tenant_id=? AND parent_id=?",
                        Integer.class,
                        uuidBytes(actor.tenantId()),
                        uuidBytes(folder.id())))
                .isZero();
        var created =
                appSettingsStore.saveMenu(
                        actor,
                        null,
                        customPageCommand(
                                folder.id(),
                                "自定义审计页面",
                                0,
                                null,
                                "/supply-chain/custom/audit",
                                "supply-chain/custom/AuditView.vue",
                                "audit:read"));
        assertThat(created.resourceId()).isNull();
        assertThat(created.routeKey()).isEqualTo("tenant.custom.page." + created.id());
        assertThat(created.routePath()).isEqualTo("/supply-chain/custom/audit");
        assertThat(created.componentPath()).isEqualTo("supply-chain/custom/AuditView.vue");
        assertThat(created.permissionCode()).isEqualTo("audit:read");
        var edited =
                appSettingsStore.saveMenu(
                        actor,
                        created.id(),
                        customPageCommand(
                                folder.id(),
                                "自定义审计页面",
                                created.version(),
                                null,
                                "/supply-chain/custom/audit-v2",
                                "supply-chain/custom/AuditV2View.vue",
                                "audit:write"));
        assertThat(edited.routeKey()).isEqualTo(created.routeKey());
        assertThat(edited.routePath()).isEqualTo("/supply-chain/custom/audit-v2");
        assertThat(edited.componentPath()).isEqualTo("supply-chain/custom/AuditV2View.vue");
        assertThat(edited.permissionCode()).isEqualTo("audit:write");
        assertThat(appSettingsStore.menus(actor))
                .filteredOn(n -> n.id().equals(created.id()))
                .singleElement()
                .satisfies(
                        n -> {
                            assertThat(n.routePath())
                                    .isEqualTo("/supply-chain/custom/audit-v2");
                            assertThat(n.componentPath())
                                    .isEqualTo("supply-chain/custom/AuditV2View.vue");
                        });
        assertThat(appSettingsStore.navigation(actor))
                .anySatisfy(
                        n -> {
                            assertThat(n.id()).isEqualTo(folder.id());
                            assertThat(n.children())
                                    .anySatisfy(
                                            child -> {
                                                assertThat(child.id()).isEqualTo(created.id());
                                                assertThat(child.code())
                                                        .isEqualTo("CUSTOM." + created.id());
                                                assertThat(child.routePath())
                                                        .isEqualTo("/supply-chain/custom/audit-v2");
                                                assertThat(child.componentPath())
                                                        .isEqualTo(
                                                                "supply-chain/custom/AuditV2View.vue");
                                                assertThat(child.permissionCode())
                                                        .isEqualTo("audit:write");
                                            });
                        });
        assertThat(appSettingsStore.audits(actor, "MENU_CREATE", null, 1, 20).total())
                .isEqualTo(2);
    }

    @Test
    void supplyMenusIgnoreCustomFieldsForDirectoriesAndResourceBoundNodes() {
        var fixture = insertTenantAdministrator();
        appSettingsStore.initialize(fixture.actor());
        var actor = fixture.actor();
        var folder =
                appSettingsStore.saveMenu(
                        actor,
                        null,
                        new com.rigour.tenant.iam.application.service.settings.AppSettingsModels
                                .MenuCommand(
                                null,
                                "MENU",
                                null,
                                "忽略字段目录",
                                "Folder",
                                9,
                                true,
                                "ACTIVE",
                                0,
                                "tenant.custom.page.hijack",
                                "/supply-chain/custom/hijack",
                                "supply-chain/custom/HijackView.vue",
                                "hijack:all"));
        assertThat(folder.routePath()).isNull();
        assertThat(folder.componentPath()).isNull();
        assertThat(folder.permissionCode()).isNull();
        assertThat(
                        jdbcTemplate.queryForMap(
                                "SELECT route_key,route_path,component_path,permission_code FROM"
                                        + " iam_app_menu_node WHERE tenant_id=? AND id=?",
                                uuidBytes(actor.tenantId()),
                                uuidBytes(folder.id())))
                .allSatisfy((column, value) -> assertThat(value).isNull());
        var bound =
                appSettingsStore.menus(actor).stream()
                        .filter(
                                n ->
                                        "PAGE".equals(n.type())
                                                && n.resourceId() != null
                                                && !n.protectedNode())
                        .findFirst()
                        .orElseThrow();
        var updated =
                appSettingsStore.saveMenu(
                        actor,
                        bound.id(),
                        new com.rigour.tenant.iam.application.service.settings.AppSettingsModels
                                .MenuCommand(
                                bound.parentId(),
                                "PAGE",
                                bound.resourceId(),
                                bound.name(),
                                bound.iconKey(),
                                bound.sortOrder(),
                                bound.visible(),
                                bound.status(),
                                bound.version(),
                                "tenant.custom.page.hijack",
                                "/supply-chain/custom/hijack",
                                "supply-chain/custom/HijackView.vue",
                                "hijack:all"));
        assertThat(updated.routeKey()).isEqualTo(bound.routeKey());
        assertThat(updated.routePath()).isEqualTo(bound.routePath());
        assertThat(updated.componentPath()).isNull();
        assertThat(updated.permissionCode()).isEqualTo(bound.permissionCode());
        assertThat(
                        jdbcTemplate.queryForMap(
                                "SELECT route_key,route_path,component_path,permission_code FROM"
                                        + " iam_app_menu_node WHERE tenant_id=? AND id=?",
                                uuidBytes(actor.tenantId()),
                                uuidBytes(bound.id())))
                .allSatisfy((column, value) -> assertThat(value).isNull());
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM iam_app_menu_node WHERE tenant_id=? AND"
                                        + " route_key='tenant.custom.page.hijack'",
                                Integer.class,
                                uuidBytes(actor.tenantId())))
                .isZero();
    }

    @Autowired private com.rigour.tenant.iam.application.port.out.AppRoleStore appRoles;
    @Autowired private com.rigour.tenant.iam.application.port.out.AppMemberStore appMembers;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.rigour.tenant.iam.application.port.out.AppEmployeeClient appEmployees;

    @Test
    void supplyMemberEmployeeBindingAndRoleBatchRemainApplicationLocal() {
        var fixture = insertTenantAdministrator();
        var actor = fixture.actor();
        appSettingsStore.initialize(actor);
        var grant =
                appSettingsStore.menus(actor).stream()
                        .filter(n -> "supply:menu:read".equals(n.permissionCode()))
                        .findFirst()
                        .orElseThrow();
        var role =
                appRoles.saveRole(
                        actor,
                        null,
                        new com.rigour.tenant.iam.application.service.settings.AppAccessModels
                                .RoleCommand(
                                "MENU_VIEWER",
                                "菜单查看",
                                "测试角色",
                                "ACTIVE",
                                0,
                                java.util.Set.of(grant.id()),
                                java.util.List.of()));
        var assignment =
                new com.rigour.tenant.iam.application.service.settings.AppMemberModels.Assignment(
                        role.id(), java.util.Map.of());
        var none =
                new com.rigour.tenant.iam.application.service.settings.AppMemberModels.Limit(
                        "NONE", java.util.List.of());
        var employee =
                new com.rigour.tenant.iam.application.port.out.AppEmployeeClient.Employee(
                        1,
                        "EMP-TEST-1",
                        "张三",
                        "ACTIVE",
                        2L,
                        "杭州销售部",
                        "SALES",
                        "业务员",
                        java.util.List.of(1L,2L),
                        1,
                        1,
                        0,
                        true,
                        null);
        org.mockito.Mockito.when(appEmployees.employee(actor.tenantId(), employee.employeeCode()))
                .thenReturn(employee);
        var member =
                appMembers.save(
                        actor,
                        null,
                        new com.rigour.tenant.iam.application.service.settings.AppMemberModels
                                .Command(
                                null,
                                "sales-test",
                                "Long-local-test-password-123",
                                employee.employeeCode(),
                                "ACTIVE",
                                null,
                                0,
                                java.util.List.of(assignment),
                                none,
                                none,
                                null));
        var memberActor = new Actor("TENANT", member.id(), actor.tenantId());
        assertThat(
                        identityAccessService.currentUser(
                                new IdentityAccessQuery("TENANT", member.id(), actor.tenantId())).permissions())
                .containsExactly("supply:menu:read");
        assertThat(member.name()).isEqualTo("张三");
        assertThat(member.employeeCode()).isEqualTo(employee.employeeCode());
        org.mockito.Mockito.when(appEmployees.employees(org.mockito.ArgumentMatchers.eq(actor.tenantId()),org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(java.util.Map.of(employee.employeeCode(),employee));
        assertThat(appMembers.members(actor,"sales-test",1,1,1L).items()).extracting(com.rigour.tenant.iam.application.service.settings.AppMemberModels.Member::id).containsExactly(member.id());
        assertThat(appMembers.members(actor,"sales-test",2,1,1L).items()).isEmpty();
        assertThat(appMembers.members(actor,"sales-test",2,1,1L).total()).isEqualTo(1);
        assertThat(appMembers.members(actor,"sales-test",1,1,999L).total()).isZero();
        assertThat(member.createdByName()).isNotBlank();
        assertThat(member.createdTime()).isNotNull();

        assertThat(appSettingsStore.context(memberActor).permissions())
                .containsExactly("supply:menu:read");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM iam_user_role WHERE tenant_id=? AND"
                                        + " user_id=?",
                                Integer.class,
                                uuidBytes(actor.tenantId()),
                                uuidBytes(member.id())))
                .isZero();
        assertThatThrownBy(
                        () ->
                                appMembers.save(
                                        actor,
                                        null,
                                        new com.rigour.tenant.iam.application.service.settings
                                                .AppMemberModels.Command(
                                                null,
                                                "duplicate-sales",
                                                "Long-local-test-password-123",
                                                employee.employeeCode(),
                                                "ACTIVE",
                                                null,
                                                0,
                                                java.util.List.of(assignment),
                                                none,
                                                none,
                                                null)))
                .hasMessageContaining("已关联其他");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM iam_user WHERE tenant_id=? AND"
                                        + " username='duplicate-sales'",
                                Integer.class,
                                uuidBytes(actor.tenantId())))
                .isZero();
        var target =
                new com.rigour.tenant.iam.application.service.settings.AppMemberModels
                        .VersionedMember(member.id(), member.version());
        var preview =
                appMembers.preview(
                        actor,
                        new com.rigour.tenant.iam.application.service.settings.AppMemberModels
                                .BatchCommand(
                                "REPLACE",
                                java.util.List.of(target),
                                java.util.List.of(assignment),
                                0));
        appMembers.status(
                actor,
                member.id(),
                new com.rigour.tenant.iam.application.service.settings.AppMemberModels
                        .StatusCommand("DISABLED", member.version(), null));
        assertThat(appSettingsStore.context(memberActor).permissions()).isEmpty();
        assertThat(
                        identityAccessService.currentUser(
                                new IdentityAccessQuery("TENANT", member.id(), actor.tenantId())).permissions())
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                appMembers.assignBatch(
                                        actor,
                                        new com.rigour.tenant.iam.application.service.settings
                                                .AppMemberModels.BatchCommand(
                                                "REPLACE",
                                                java.util.List.of(target),
                                                java.util.List.of(assignment),
                                                preview.applicationVersion())))
                .hasMessageContaining("重新预览");
        assertThat(
                        appRoles.roles(actor).stream()
                                .filter(r -> r.id().equals(role.id()))
                                .findFirst()
                                .orElseThrow()
                                .userCount())
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status FROM iam_user WHERE tenant_id=? AND id=?",
                                String.class,
                                uuidBytes(actor.tenantId()),
                                uuidBytes(member.id())))
                .isEqualTo("ACTIVE");
        org.mockito.Mockito.when(appEmployees.employee(actor.tenantId(), employee.employeeCode()))
                .thenReturn(
                        new com.rigour.tenant.iam.application.port.out.AppEmployeeClient.Employee(
                                1,
                                employee.employeeCode(),
                                "张三",
                                "ACTIVE",
                                1L,
                                "杭州销售部",
                                "SALES",
                                "业务员",
                                java.util.List.of(1L),
                                3,
                                3,
                                1,
                                true,
                                null));
        assertThatThrownBy(
                        () ->
                                appMembers.status(
                                        actor,
                                        member.id(),
                                        new com.rigour.tenant.iam.application.service.settings
                                                .AppMemberModels.StatusCommand(
                                                "ACTIVE", member.version() + 1, null)))
                .hasMessageContaining("重新核验");
    }

    private com.rigour.tenant.iam.application.service.settings.AppAccessModels.Role supplyTestRole(
            Actor actor,
            String code,
            Set<String> permissions,
            List<com.rigour.tenant.iam.application.service.settings.AppAccessModels.ScopeRule>
                    rules) {
        Set<UUID> nodes =
                appSettingsStore.menus(actor).stream()
                        .filter(
                                n ->
                                        n.permissionCode() != null
                                                && permissions.contains(n.permissionCode()))
                        .map(com.rigour.tenant.iam.domain.model.settings.AppMenuTree.Node::id)
                        .collect(java.util.stream.Collectors.toSet());
        assertThat(nodes).hasSize(permissions.size());
        return appRoles.saveRole(
                actor,
                null,
                new com.rigour.tenant.iam.application.service.settings.AppAccessModels.RoleCommand(
                        code, code, null, "ACTIVE", 0, nodes, rules));
    }

    private com.rigour.tenant.iam.application.port.out.AppEmployeeClient.Employee
            supplyTestEmployee(Actor actor, String code, long department, List<Long> ancestors) {
        var employee =
                new com.rigour.tenant.iam.application.port.out.AppEmployeeClient.Employee(
                        department,
                        code,
                        code,
                        "ACTIVE",
                        department,
                        "部门" + department,
                        "SALES",
                        "销售",
                        ancestors,
                        1,
                        1,
                        0,
                        true,
                        null);
        org.mockito.Mockito.when(appEmployees.employee(actor.tenantId(), code))
                .thenReturn(employee);
        return employee;
    }

    private com.rigour.tenant.iam.application.service.settings.AppMemberModels.Command
            supplyTestCommand(
                    String username,
                    String employee,
                    String status,
                    long version,
                    List<
                                    com.rigour.tenant.iam.application.service.settings
                                            .AppMemberModels.Assignment>
                            roles) {
        var none =
                new com.rigour.tenant.iam.application.service.settings.AppMemberModels.Limit(
                        "NONE", List.of());
        return new com.rigour.tenant.iam.application.service.settings.AppMemberModels.Command(
                null,
                username,
                "Local-permission-regression-password-123",
                employee,
                status,
                null,
                version,
                roles,
                none,
                none,
                "测试员工关联更正");
    }

    private com.rigour.tenant.iam.application.service.settings.AppMemberModels.Assignment
            supplyTestAssignment(
                    com.rigour.tenant.iam.application.service.settings.AppAccessModels.Role role) {
        return new com.rigour.tenant.iam.application.service.settings.AppMemberModels.Assignment(
                role.id(), Map.of());
    }

    @Test
    void supplyCurrentDepartmentDelegationChecksRecipientRebindAndAtomicBatch() {
        var root = insertTenantAdministrator().actor();
        appSettingsStore.initialize(root);
        var managerRole =
                supplyTestRole(
                        root,
                        "DEPARTMENT_MANAGER",
                        Set.of(
                                "supply:user:read",
                                "supply:user:create",
                                "supply:user:update",
                                "supply:user:rebind",
                                "supply:user:assign-role",
                                "supply:user:disable",
                                "supply:menu:read"),
                        List.of());
        var selfRule =
                new com.rigour.tenant.iam.application.service.settings.AppAccessModels.ScopeRule(
                        null,
                        "hr:employee:read",
                        "EMPLOYEE",
                        "DEPARTMENT",
                        "CURRENT",
                        "NONE",
                        "NONE",
                        false,
                        Map.of());
        var treeRule =
                new com.rigour.tenant.iam.application.service.settings.AppAccessModels.ScopeRule(
                        null,
                        "hr:employee:read",
                        "EMPLOYEE",
                        "DEPARTMENT",
                        "CURRENT",
                        "NONE",
                        "NONE",
                        true,
                        Map.of());
        var current =
                supplyTestRole(
                        root, "CURRENT_DEPARTMENT", Set.of("hr:employee:read"), List.of(selfRule));
        var descendants =
                supplyTestRole(
                        root, "CURRENT_SUBTREE", Set.of("hr:employee:read"), List.of(treeRule));
        var viewer = supplyTestRole(root, "BASE_VIEWER", Set.of("supply:menu:read"), List.of());
        supplyTestEmployee(root, "MANAGER", 10, List.of(10L));
        supplyTestEmployee(root, "SAME", 10, List.of(10L));
        supplyTestEmployee(root, "CHILD", 11, List.of(10L, 11L));
        supplyTestEmployee(root, "OTHER", 20, List.of(20L));
        supplyTestEmployee(root, "OTHER-REBIND", 20, List.of(20L));
        supplyTestEmployee(root, "CHILD-NO-TREE", 11, List.of(10L, 11L));
        var manager =
                appMembers.save(
                        root,
                        null,
                        supplyTestCommand(
                                "department-manager",
                                "MANAGER",
                                "ACTIVE",
                                0,
                                List.of(
                                        supplyTestAssignment(managerRole),
                                        supplyTestAssignment(current),
                                        supplyTestAssignment(descendants))));
        var actor = new Actor("TENANT", manager.id(), root.tenantId());
        var same =
                appMembers.save(
                        actor,
                        null,
                        supplyTestCommand(
                                "same-department",
                                "SAME",
                                "ACTIVE",
                                0,
                                List.of(supplyTestAssignment(current))));
        var child =
                appMembers.save(
                        actor,
                        null,
                        supplyTestCommand(
                                "child-department",
                                "CHILD",
                                "ACTIVE",
                                0,
                                List.of(supplyTestAssignment(descendants))));
        assertThat(child.employee().departmentId()).isEqualTo(11L);
        assertThatThrownBy(
                        () ->
                                appMembers.save(
                                        actor,
                                        null,
                                        supplyTestCommand(
                                                "outside-department",
                                                "OTHER",
                                                "ACTIVE",
                                                0,
                                                List.of(supplyTestAssignment(descendants)))))
                .hasMessageContaining("当前部门超出");
        assertThatThrownBy(
                        () ->
                                appMembers.save(
                                        actor,
                                        null,
                                        supplyTestCommand(
                                                "child-without-tree",
                                                "CHILD-NO-TREE",
                                                "ACTIVE",
                                                0,
                                                List.of(supplyTestAssignment(current)))))
                .hasMessageContaining("当前部门超出");
        assertThatThrownBy(
                        () ->
                                appMembers.save(
                                        actor,
                                        same.id(),
                                        supplyTestCommand(
                                                "same-department",
                                                "OTHER-REBIND",
                                                "ACTIVE",
                                                same.version(),
                                                same.roles())))
                .hasMessageContaining("当前部门超出");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT employee_code FROM iam_app_employee_binding WHERE"
                                        + " tenant_id=? AND user_id=?",
                                String.class,
                                uuidBytes(root.tenantId()),
                                uuidBytes(same.id())))
                .isEqualTo("SAME");
        var disabledOutside =
                appMembers.save(
                        root,
                        null,
                        supplyTestCommand(
                                "disabled-outside",
                                "OTHER-REBIND",
                                "DISABLED",
                                0,
                                List.of(supplyTestAssignment(current))));
        assertThatThrownBy(
                        () ->
                                appMembers.status(
                                        actor,
                                        disabledOutside.id(),
                                        new com.rigour.tenant.iam.application.service.settings
                                                .AppMemberModels.StatusCommand(
                                                "ACTIVE", disabledOutside.version(), null)))
                .hasMessageContaining("当前部门超出");
        assertThatThrownBy(
                        () ->
                                appMembers.save(
                                        actor,
                                        disabledOutside.id(),
                                        supplyTestCommand(
                                                "disabled-outside",
                                                "OTHER-REBIND",
                                                "ACTIVE",
                                                disabledOutside.version(),
                                                disabledOutside.roles())))
                .hasMessageContaining("当前部门超出");
        var other =
                appMembers.save(
                        root,
                        null,
                        supplyTestCommand(
                                "other-viewer",
                                "OTHER",
                                "ACTIVE",
                                0,
                                List.of(supplyTestAssignment(viewer))));
        var batch =
                new com.rigour.tenant.iam.application.service.settings.AppMemberModels.BatchCommand(
                        "REPLACE",
                        List.of(
                                new com.rigour.tenant.iam.application.service.settings
                                        .AppMemberModels.VersionedMember(same.id(), same.version()),
                                new com.rigour.tenant.iam.application.service.settings
                                        .AppMemberModels.VersionedMember(
                                        other.id(), other.version())),
                        List.of(supplyTestAssignment(descendants)),
                        appSettingsStore.context(actor).version());
        assertThatThrownBy(() -> appMembers.preview(actor, batch)).hasMessageContaining("当前部门超出");
        assertThatThrownBy(() -> appMembers.assignBatch(actor, batch))
                .hasMessageContaining("当前部门超出");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT role_id FROM iam_app_member_role WHERE tenant_id=? AND"
                                        + " user_id=?",
                                byte[].class,
                                uuidBytes(root.tenantId()),
                                uuidBytes(same.id())))
                .isEqualTo(uuidBytes(current.id()));
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT role_id FROM iam_app_member_role WHERE tenant_id=? AND"
                                        + " user_id=?",
                                byte[].class,
                                uuidBytes(root.tenantId()),
                                uuidBytes(other.id())))
                .isEqualTo(uuidBytes(viewer.id()));
    }

    @Test
    void supplyRoleRetirementRequiresReplacementThenDisableThenUnassign() {
        var actor = insertTenantAdministrator().actor();
        appSettingsStore.initialize(actor);
        var role = supplyTestRole(actor, "RETIRING_ROLE", Set.of("supply:menu:read"), List.of());
        var replacement =
                supplyTestRole(actor, "REPLACEMENT_ROLE", Set.of("supply:menu:read"), List.of());
        supplyTestEmployee(actor, "RETIRING-USER", 10, List.of(10L));
        var member =
                appMembers.save(
                        actor,
                        null,
                        supplyTestCommand(
                                "retiring-user",
                                "RETIRING-USER",
                                "ACTIVE",
                                0,
                                List.of(supplyTestAssignment(role))));
        var impact = appRoles.impact(actor, role.id());
        assertThat(impact.lastRoleUsernames()).containsExactly("retiring-user");
        assertThat(impact.canDisable()).isFalse();
        assertThat(impact.canDelete()).isFalse();
        var disabled =
                new com.rigour.tenant.iam.application.service.settings.AppAccessModels.RoleCommand(
                        role.code(),
                        role.name(),
                        role.description(),
                        "DISABLED",
                        role.version(),
                        role.menuNodeIds(),
                        role.rules());
        assertThatThrownBy(() -> appRoles.saveRole(actor, role.id(), disabled))
                .hasMessageContaining("最后一个有效角色");
        assertThatThrownBy(() -> appRoles.deleteRole(actor, role.id(), role.version(), true))
                .hasMessageContaining("请先禁用角色");
        member =
                appMembers.save(
                        actor,
                        member.id(),
                        supplyTestCommand(
                                "retiring-user",
                                "RETIRING-USER",
                                "ACTIVE",
                                member.version(),
                                List.of(
                                        supplyTestAssignment(role),
                                        supplyTestAssignment(replacement))));
        assertThat(appRoles.impact(actor, role.id()).canDisable()).isTrue();
        var retired = appRoles.saveRole(actor, role.id(), disabled);
        assertThatThrownBy(() -> appRoles.deleteRole(actor, retired.id(), retired.version(), true))
                .hasMessageContaining("解除关联");
        appMembers.save(
                actor,
                member.id(),
                supplyTestCommand(
                        "retiring-user",
                        "RETIRING-USER",
                        "ACTIVE",
                        member.version(),
                        List.of(supplyTestAssignment(replacement))));
        assertThat(appRoles.impact(actor, retired.id()).canDelete()).isTrue();
        appRoles.deleteRole(actor, retired.id(), retired.version(), false);
        assertThat(appRoles.roles(actor))
                .extracting(
                        com.rigour.tenant.iam.application.service.settings.AppAccessModels.Role::id)
                .doesNotContain(retired.id());
    }

    @Test
    void supplyOrdinaryAdministratorCannotDisableOrDeleteSelfThroughEitherWritePath() {
        var root = insertTenantAdministrator().actor();
        appSettingsStore.initialize(root);
        var role =
                supplyTestRole(
                        root,
                        "ORDINARY_ADMIN",
                        Set.of(
                                "supply:user:read",
                                "supply:user:update",
                                "supply:user:disable",
                                "supply:user:delete"),
                        List.of());
        supplyTestEmployee(root, "ORDINARY-ADMIN", 10, List.of(10L));
        var member =
                appMembers.save(
                        root,
                        null,
                        supplyTestCommand(
                                "ordinary-admin",
                                "ORDINARY-ADMIN",
                                "ACTIVE",
                                0,
                                List.of(supplyTestAssignment(role))));
        var actor = new Actor("TENANT", member.id(), root.tenantId());
        assertThatThrownBy(
                        () ->
                                appMembers.status(
                                        actor,
                                        member.id(),
                                        new com.rigour.tenant.iam.application.service.settings
                                                .AppMemberModels.StatusCommand(
                                                "DISABLED", member.version(), null)))
                .hasMessageContaining("不能禁用或删除当前登录");
        assertThatThrownBy(
                        () ->
                                appMembers.save(
                                        actor,
                                        member.id(),
                                        supplyTestCommand(
                                                "ordinary-admin",
                                                "ORDINARY-ADMIN",
                                                "DISABLED",
                                                member.version(),
                                                member.roles())))
                .hasMessageContaining("不能禁用或删除当前登录");
        assertThatThrownBy(() -> appMembers.delete(actor, member.id(), member.version()))
                .hasMessageContaining("不能禁用或删除当前登录");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status FROM iam_app_member WHERE tenant_id=? AND user_id=?",
                                String.class,
                                uuidBytes(root.tenantId()),
                                uuidBytes(member.id())))
                .isEqualTo("ACTIVE");
    }

    @Test
    void supplyRoleChangePreservesLastUsableManagementEntry() {
        var root = insertTenantAdministrator().actor();
        appSettingsStore.initialize(root);
        var management =
                supplyTestRole(
                        root,
                        "LAST_MANAGEMENT",
                        Set.of(
                                "supply:user:read",
                                "supply:user:assign-role",
                                "supply:role:read",
                                "supply:role:grant",
                                "supply:role:update"),
                        List.of());
        var viewer =
                supplyTestRole(root, "ADMIN_FALLBACK_VIEW", Set.of("supply:menu:read"), List.of());
        supplyTestEmployee(root, "LAST-MANAGER", 10, List.of(10L));
        var member =
                appMembers.save(
                        root,
                        null,
                        supplyTestCommand(
                                "last-manager",
                                "LAST-MANAGER",
                                "ACTIVE",
                                0,
                                List.of(
                                        supplyTestAssignment(management),
                                        supplyTestAssignment(viewer))));
        // 模拟恢复账号因统一账号故障不可用；普通入口仍须保留，不能只数角色或 PROTECTED 标记。
        jdbcTemplate.update(
                "UPDATE iam_user SET status='DISABLED' WHERE tenant_id=? AND id=?",
                uuidBytes(root.tenantId()),
                uuidBytes(root.principalId()));
        var actor = new Actor("TENANT", member.id(), root.tenantId());
        assertThat(appRoles.impact(actor, management.id()).managementEntryUsernames())
                .containsExactly("last-manager");
        assertThatThrownBy(
                        () ->
                                appRoles.saveRole(
                                        actor,
                                        management.id(),
                                        new com.rigour.tenant.iam.application.service.settings
                                                .AppAccessModels.RoleCommand(
                                                management.code(),
                                                management.name(),
                                                null,
                                                "DISABLED",
                                                management.version(),
                                                management.menuNodeIds(),
                                                management.rules())))
                .hasMessageContaining("管理入口");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status FROM iam_app_role WHERE tenant_id=? AND id=?",
                                String.class,
                                uuidBytes(root.tenantId()),
                                uuidBytes(management.id())))
                .isEqualTo("ACTIVE");
    }

    private com.rigour.shared.context.CallerIdentity supplyTestCaller(Actor actor) {
        UUID session = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO"
                    + " iam_auth_session(id,principal_scope,tenant_id,principal_id,client_type,device_name,issued_at,last_seen_at,expires_at,status,version)"
                    + " VALUES(?,'TENANT',?,?,'WEB','permission-test',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL"
                    + " 1 HOUR),'ACTIVE',0)",
                uuidBytes(session),
                uuidBytes(actor.tenantId()),
                uuidBytes(actor.principalId()));
        long userVersion =
                jdbcTemplate.queryForObject(
                        "SELECT security_version FROM iam_user WHERE tenant_id=? AND id=?",
                        Long.class,
                        uuidBytes(actor.tenantId()),
                        uuidBytes(actor.principalId()));
        long tenantVersion =
                jdbcTemplate.queryForObject(
                        "SELECT policy_version FROM iam_tenant WHERE id=?",
                        Long.class,
                        uuidBytes(actor.tenantId()));
        return new com.rigour.shared.context.CallerIdentity(
                "TENANT",
                actor.principalId(),
                actor.tenantId(),
                actor.principalId(),
                null,
                session,
                0,
                userVersion,
                tenantVersion,
                Set.of(),
                Set.of("*:*:*"));
    }

    @Test
    void customerAssignmentTargetPreservesLegacyAccessAndChecksActiveUserCapsAndTenant() {
        var root = insertTenantAdministrator().actor();
        var legacyCaller = supplyTestCaller(root);
        supplyTestEmployee(root, "NO-LOGIN", 10, List.of(10L));
        var uninitialized =
                appAuthorization.customerAssignmentTarget(legacyCaller, "NO-LOGIN", null);
        assertThat(uninitialized.usable()).isTrue();
        assertThat(uninitialized.userId()).isNull();
        assertThat(uninitialized.regionLimit().mode()).isEqualTo("ALL");
        appSettingsStore.initialize(root);
        assertThat(
                        appAuthorization
                                .customerAssignmentTarget(legacyCaller, "NO-LOGIN", null)
                                .usable())
                .isTrue();
        var viewer =
                supplyTestRole(root, "ASSIGNMENT_VIEWER", Set.of("supply:menu:read"), List.of());
        supplyTestEmployee(root, "WITH-LOGIN", 10, List.of(10L));
        var member =
                appMembers.save(
                        root,
                        null,
                        supplyTestCommand(
                                "assignment-target",
                                "WITH-LOGIN",
                                "ACTIVE",
                                0,
                                List.of(supplyTestAssignment(viewer))));
        var target = appAuthorization.customerAssignmentTarget(legacyCaller, null, member.id());
        assertThat(target.userId()).isEqualTo(member.id());
        assertThat(target.regionLimit().mode()).isEqualTo("NONE");
        assertThat(target.usable()).isTrue();
        jdbcTemplate.update(
                "UPDATE iam_app_settings SET authorization_mode='ACTIVE' WHERE tenant_id=?",
                uuidBytes(root.tenantId()));
        var ordinary = supplyTestCaller(new Actor("TENANT", member.id(), root.tenantId()));
        assertThatThrownBy(
                        () -> appAuthorization.customerAssignmentTarget(ordinary, "NO-LOGIN", null))
                .hasMessageContaining("缺少客户主责"); // caller 中伪造通配不覆盖当前应用权限。
        assertThatThrownBy(
                        () ->
                                appAuthorization.customerAssignmentTarget(
                                        legacyCaller, null, UUID.randomUUID()))
                .hasMessageContaining("不存在或未关联员工");
        appMembers.status(
                root,
                member.id(),
                new com.rigour.tenant.iam.application.service.settings.AppMemberModels
                        .StatusCommand("DISABLED", member.version(), null));
        assertThat(
                        appAuthorization
                                .customerAssignmentTarget(legacyCaller, "WITH-LOGIN", null)
                                .usable())
                .isFalse();
        assertThat(
                        appAuthorization
                                .customerAssignmentTarget(legacyCaller, "WITH-LOGIN", null)
                                .regionLimit()
                                .mode())
                .isEqualTo("NONE");
    }

    @Test
    void preparingCustomerAssignmentDoesNotActivateDraftMemberRestrictionsBeforeCutover() {
        var root = insertTenantAdministrator().actor();
        appSettingsStore.initialize(root);
        var caller = supplyTestCaller(root);
        var viewer =
                supplyTestRole(root, "PREPARING_VIEWER", Set.of("supply:menu:read"), List.of());
        var employee = supplyTestEmployee(root, "DRAFT-MEMBER", 10, List.of(10L));
        var member =
                appMembers.save(
                        root,
                        null,
                        supplyTestCommand(
                                "draft-member",
                                "DRAFT-MEMBER",
                                "DISABLED",
                                0,
                                List.of(supplyTestAssignment(viewer))));
        var legacyTarget = appAuthorization.customerAssignmentTarget(caller, "DRAFT-MEMBER", null);
        assertThat(legacyTarget.usable()).isTrue();
        assertThat(legacyTarget.regionLimit().mode()).isEqualTo("ALL");
        var configurationTarget =
                appAuthorization.customerAssignmentTarget(caller, null, member.id());
        assertThat(configurationTarget.usable()).isFalse();
        assertThat(configurationTarget.regionLimit().mode()).isEqualTo("NONE");
        org.mockito.Mockito.when(appEmployees.employee(root.tenantId(), "DRAFT-MEMBER"))
                .thenReturn(
                        new com.rigour.tenant.iam.application.port.out.AppEmployeeClient.Employee(
                                employee.id(),
                                employee.employeeCode(),
                                employee.employeeName(),
                                "LEFT",
                                employee.departmentId(),
                                employee.departmentName(),
                                employee.positionCode(),
                                employee.positionName(),
                                employee.departmentAncestorIds(),
                                2,
                                employee.organizationVersion(),
                                1,
                                false,
                                "员工已离职"));
        assertThat(appAuthorization.customerAssignmentTarget(caller, "DRAFT-MEMBER", null).usable())
                .isFalse();
        assertThat(
                        appAuthorization
                                .customerAssignmentTarget(caller, "DRAFT-MEMBER", null)
                                .unavailableReason())
                .isEqualTo("员工已离职");
        org.mockito.Mockito.when(appEmployees.employee(root.tenantId(), "DRAFT-MEMBER"))
                .thenThrow(new IllegalStateException("HR 服务暂时无法核验"));
        assertThatThrownBy(
                        () ->
                                appAuthorization.customerAssignmentTarget(
                                        caller, "DRAFT-MEMBER", null))
                .hasMessageContaining("HR 服务暂时无法核验");
        org.mockito.Mockito.doReturn(employee)
                .when(appEmployees)
                .employee(root.tenantId(), "DRAFT-MEMBER");
        jdbcTemplate.update(
                "UPDATE iam_app_settings SET authorization_mode='ACTIVE' WHERE tenant_id=?",
                uuidBytes(root.tenantId()));
        var activeTarget = appAuthorization.customerAssignmentTarget(caller, "DRAFT-MEMBER", null);
        assertThat(activeTarget.usable()).isFalse();
        assertThat(activeTarget.unavailableReason()).isEqualTo("目标用户已禁用");
        assertThat(activeTarget.regionLimit().mode()).isEqualTo("NONE");
    }

    @Autowired
    private com.rigour.tenant.iam.application.port.out.AppAuthorizationStore appAuthorization;

    @Test
    void realRequestShadowKeepsOldAndNewDecisionsSeparateAndRejectsExpiredSessions() {
        var f = insertTenantAdministrator();
        var other = insertTenantAdministrator();
        appSettingsStore.initialize(f.actor());
        appSettingsStore.initialize(other.actor());
        var a = f.actor();
        var session = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO"
                    + " iam_auth_session(id,principal_scope,tenant_id,principal_id,client_type,device_name,issued_at,last_seen_at,expires_at,status,version)"
                    + " VALUES(?,'TENANT',?,?,'WEB','shadow-test',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL"
                    + " 1 HOUR),'ACTIVE',0)",
                uuidBytes(session),
                uuidBytes(a.tenantId()),
                uuidBytes(a.principalId()));
        long userVersion =
                jdbcTemplate.queryForObject(
                        "SELECT security_version FROM iam_user WHERE tenant_id=? AND id=?",
                        Long.class,
                        uuidBytes(a.tenantId()),
                        uuidBytes(a.principalId()));
        long tenantVersion =
                jdbcTemplate.queryForObject(
                        "SELECT policy_version FROM iam_tenant WHERE id=?",
                        Long.class,
                        uuidBytes(a.tenantId()));
        var caller =
                new com.rigour.shared.context.CallerIdentity(
                        "TENANT",
                        a.principalId(),
                        a.tenantId(),
                        a.principalId(),
                        null,
                        session,
                        0,
                        userVersion,
                        tenantVersion,
                        java.util.Set.of(),
                        java.util.Set.of());
        appAuthorization.observe(caller, "crm:customer:read", "crm:customer:read");
        appAuthorization.observe(caller, "crm:customer:read", "crm:customer:read");
        var rows = cutover.observations(a, 1, 20);
        assertThat(rows.total()).isEqualTo(1);
        var row = rows.items().getFirst();
        assertThat(row.sampleCount()).isEqualTo(2);
        assertThat(row.legacyAllowed()).isTrue();
        assertThat(row.proposedAllowed()).isFalse();
        assertThat(row.policyJson()).contains("legacyRoles", "TENANT_SUPER_ADMIN");
        assertThat(cutover.observations(other.actor(), 1, 20).total()).isZero();
        var candidate = appAuthorization.candidate(caller, "crm:customer:read");
        assertThat(candidate.mode()).isEqualTo("PREPARING");
        assertThat(candidate.functionAllowed()).isFalse();
        var data =
                new com.rigour.tenant.iam.application.model.settings.AppDataObservation(
                        "crm:customer:read",
                        "CRM",
                        "record-123",
                        candidate.applicationVersion(),
                        candidate.memberVersion(),
                        candidate.employeeRevision(),
                        candidate.organizationVersion(),
                        true,
                        false);
        appAuthorization.observeData(caller, data);
        appAuthorization.observeData(caller, data);
        var dataPage = cutover.dataObservations(a, 1, 1);
        assertThat(dataPage.total()).isEqualTo(1);
        assertThat(dataPage.items().getFirst().sampleCount()).isEqualTo(2);
        assertThat(dataPage.items().getFirst().proposedAllowed()).isFalse();
        assertThat(cutover.dataObservations(other.actor(), 1, 20).total()).isZero();
        assertThatThrownBy(
                        () ->
                                appAuthorization.observeData(
                                        caller,
                                        new com.rigour.tenant.iam.application.model.settings
                                                .AppDataObservation(
                                                data.action(),
                                                data.domain(),
                                                data.recordKey(),
                                                data.applicationVersion() + 1,
                                                data.memberVersion(),
                                                data.employeeRevision(),
                                                data.organizationVersion(),
                                                true,
                                                false)))
                .hasMessageContaining("已变化");
        assertThatThrownBy(
                        () ->
                                appAuthorization.observeData(
                                        caller,
                                        new com.rigour.tenant.iam.application.model.settings
                                                .AppDataObservation(
                                                data.action(),
                                                data.domain(),
                                                data.recordKey(),
                                                data.applicationVersion(),
                                                data.memberVersion(),
                                                data.employeeRevision(),
                                                data.organizationVersion(),
                                                true,
                                                true)))
                .hasMessageContaining("功能授权");

        assertThat(appSettingsStore.context(a).mode()).isEqualTo("PREPARING");
        jdbcTemplate.update(
                "UPDATE iam_auth_session SET"
                    + " status='REVOKED',revoked_at=UTC_TIMESTAMP(6),revoke_reason='shadow-test'"
                    + " WHERE id=?",
                uuidBytes(session));
        assertThatThrownBy(
                        () ->
                                appAuthorization.observe(
                                        caller, "crm:customer:read", "crm:customer:read"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(cutover.observations(a, 1, 20).items().getFirst().sampleCount()).isEqualTo(2);
    }

    @Autowired private com.rigour.tenant.iam.application.port.out.AppLegacyRoleStore legacyRoles;

    @Test
    void legacyRoleImportPreservesSourceAndStartsDisabledWithoutBusinessDataGrants() {
        var f = insertTenantAdministrator();
        var other = insertTenantAdministrator();
        appSettingsStore.initialize(f.actor());
        appSettingsStore.initialize(other.actor());
        var source =
                legacyRoles.sources(f.actor()).stream()
                        .filter(r -> r.id().equals(f.roleId()))
                        .findFirst()
                        .orElseThrow();
        long sourceCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM iam_role_resource WHERE tenant_id=? AND role_id=?",
                        Long.class,
                        uuidBytes(f.actor().tenantId()),
                        uuidBytes(f.roleId()));
        assertThatThrownBy(
                        () ->
                                legacyRoles.importRole(
                                        f.actor(),
                                        f.roleId(),
                                        new com.rigour.tenant.iam.application.service.settings
                                                .AppLegacyRoleModels.Command(
                                                "迁入测试", source.applicationVersion(), "stale")))
                .isInstanceOf(IllegalStateException.class);
        var imported =
                legacyRoles.importRole(
                        f.actor(),
                        f.roleId(),
                        new com.rigour.tenant.iam.application.service.settings.AppLegacyRoleModels
                                .Command(
                                "迁入测试", source.applicationVersion(), source.fingerprint()));
        assertThat(imported.status()).isEqualTo("DISABLED");
        assertThat(imported.rules()).isNotEmpty().allMatch(r -> r.scopeMode().equals("NONE"));
        assertThat(imported.userCount()).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM iam_role_resource WHERE tenant_id=? AND"
                                        + " role_id=?",
                                Long.class,
                                uuidBytes(f.actor().tenantId()),
                                uuidBytes(f.roleId())))
                .isEqualTo(sourceCount);
        assertThat(
                        legacyRoles.sources(f.actor()).stream()
                                .filter(r -> r.id().equals(f.roleId()))
                                .findFirst()
                                .orElseThrow()
                                .importedRoleId())
                .isEqualTo(imported.id());
        assertThat(legacyRoles.sources(other.actor())).noneMatch(r -> r.id().equals(f.roleId()));
        var latest =
                legacyRoles.sources(f.actor()).stream()
                        .filter(r -> r.id().equals(f.roleId()))
                        .findFirst()
                        .orElseThrow();
        assertThatThrownBy(
                        () ->
                                legacyRoles.importRole(
                                        f.actor(),
                                        f.roleId(),
                                        new com.rigour.tenant.iam.application.service.settings
                                                .AppLegacyRoleModels.Command(
                                                "重复",
                                                latest.applicationVersion(),
                                                latest.fingerprint())))
                .hasMessageContaining("已迁入");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM iam_app_legacy_role_mapping WHERE"
                                        + " tenant_id=?",
                                Long.class,
                                uuidBytes(f.actor().tenantId())))
                .isEqualTo(1);
    }

    private TenantAdminFixture insertTenantAdministrator() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expires = now.plusYears(1);
        jdbcTemplate.update(
                """
INSERT INTO iam_tenant
(id, tenant_code, company_name, status, policy_version, version, created_at, updated_at)
VALUES (?, ?, 'Management Test Tenant', 'ACTIVE', 0, 0, ?, ?)
""",
                uuidBytes(tenantId),
                "mgmt-" + UUID.randomUUID().toString().substring(0, 8),
                now,
                now);
        jdbcTemplate.update(
                """
INSERT INTO iam_tenant_subscription
(id, tenant_id, package_version_id, effective_from, effective_to, user_limit, status,
 created_at, updated_at)
VALUES (?, ?, UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), ?, ?, 100, 'ACTIVE', ?, ?)
""",
                uuidBytes(UUID.randomUUID()),
                uuidBytes(tenantId),
                now.minusMinutes(1),
                expires,
                now,
                now);
        jdbcTemplate.update(
                """
INSERT INTO iam_user
(id, tenant_id, username, display_name, status, security_version, version, created_at, updated_at)
VALUES (?, ?, ?, 'Tenant Administrator', 'ACTIVE', 0, 0, ?, ?)
""",
                uuidBytes(userId),
                uuidBytes(tenantId),
                "admin-" + UUID.randomUUID().toString().substring(0, 8),
                now,
                now);
        jdbcTemplate.update(
                """
                INSERT INTO iam_role
                (id, tenant_id, role_code, role_name, role_type, status, created_at, updated_at)
                VALUES (?, ?, 'TENANT_SUPER_ADMIN', '租户超级管理员', 'SYSTEM', 'ACTIVE', ?, ?)
                """,
                uuidBytes(roleId),
                uuidBytes(tenantId),
                now,
                now);
        jdbcTemplate.update(
                """
                INSERT INTO iam_role_resource
                (tenant_id, role_id, resource_id, status, created_at, updated_at)
                SELECT ?, ?, resource_id, 'ACTIVE', ?, ? FROM iam_package_resource
                 WHERE package_version_id=UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
                """,
                uuidBytes(tenantId),
                uuidBytes(roleId),
                now,
                now);
        jdbcTemplate.update(
                """
INSERT INTO iam_tenant_menu_config
(tenant_id, resource_id, visible, created_at, updated_at)
SELECT ?, resource_record.id, resource_ui.visible, ?, ?
  FROM iam_package_resource package_resource
  JOIN iam_resource resource_record ON resource_record.id=package_resource.resource_id
  JOIN iam_resource_ui resource_ui ON resource_ui.resource_id=resource_record.id
 WHERE package_resource.package_version_id=UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
   AND resource_record.resource_type IN ('MENU','PAGE')
""",
                uuidBytes(tenantId),
                now,
                now);
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT COUNT(*) FROM iam_package_resource
 WHERE package_version_id=UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
""",
                                Integer.class))
                .isPositive();
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT COUNT(*) FROM iam_package_resource package_resource
 JOIN iam_resource resource_record ON resource_record.id=package_resource.resource_id
 JOIN iam_resource_ui resource_ui ON resource_ui.resource_id=resource_record.id
 WHERE package_resource.package_version_id=UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
   AND resource_ui.route_key='supply.erp.procurement.payments'
   AND resource_ui.route_path='/supply-chain/erp/procurement/payments'
""",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT COUNT(*) FROM iam_package_resource package_resource
 JOIN iam_resource resource_record ON resource_record.id=package_resource.resource_id
 WHERE package_resource.package_version_id=UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
   AND resource_record.permission_code='iam:dictionary:write'
""",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                """
SELECT COUNT(*) FROM iam_role_resource rr
 JOIN iam_resource resource_record ON resource_record.id=rr.resource_id
 WHERE rr.tenant_id=? AND rr.role_id=? AND resource_record.permission_code='iam:dictionary:write'
""",
                                Integer.class,
                                uuidBytes(tenantId),
                                uuidBytes(roleId)))
                .isEqualTo(1);
        jdbcTemplate.update(
                """
                INSERT INTO iam_user_role
                (tenant_id, user_id, role_id, status, effective_from, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
                """,
                uuidBytes(tenantId),
                uuidBytes(userId),
                uuidBytes(roleId),
                now,
                now,
                now);
        return new TenantAdminFixture(new Actor("TENANT", userId, tenantId), roleId);
    }

    private void assertUniformFailure(IamLoginAuthenticationToken request) {
        assertThatThrownBy(() -> authenticationProvider.authenticate(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Authentication failed");
        assertThat(request.getCredentials()).isNull();
    }

    private record TenantIdentityFixture(
            UUID tenantId, String tenantCode, UUID userId, String username) {}

    private record TenantAdminFixture(Actor actor, UUID roleId) {}

    private static com.rigour.tenant.iam.application.service.settings.AppSettingsModels.MenuCommand
            customPageCommand(
                    UUID parentId,
                    String name,
                    long version,
                    String routeKey,
                    String routePath,
                    String componentPath,
                    String permissionCode) {
        return new com.rigour.tenant.iam.application.service.settings.AppSettingsModels.MenuCommand(
                parentId,
                "PAGE",
                null,
                name,
                "Shop",
                6,
                true,
                "ACTIVE",
                version,
                routeKey,
                routePath,
                componentPath,
                permissionCode);
    }

    private RegisteredClient scdpClient() {
        return RegisteredClient.withId("019fb000-0000-7000-8000-000000000001")
                .clientId("rigour-scdp")
                .clientIdIssuedAt(Instant.parse("2026-07-31T00:00:00Z"))
                .clientName("瑞盖统一SCDP")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://scdp.dev.rigour.local/login/oauth2/code/rigour-iam")
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

    private static java.util.stream.Stream<NavigationNode> flattenNavigation(
            List<NavigationNode> nodes) {
        return nodes.stream()
                .flatMap(
                        node ->
                                java.util.stream.Stream.concat(
                                        java.util.stream.Stream.of(node),
                                        flattenNavigation(node.children())));
    }

    private void assertCount(String sql, int expected) {
        Integer actual = jdbcTemplate.queryForObject(sql, Integer.class);
        // 一次报告全部结构/导航差异，仍由 AfterEach 统一使测试失败，不吞掉断言。
        sqlAssertions.assertThat(actual).as(sql).isEqualTo(expected);
    }
}
