package com.rigour.tenant.iam;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.rigour.tenant.iam.application.port.out.PasswordHasher;
import com.rigour.tenant.iam.application.port.out.IamManagementStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.*;
import com.rigour.tenant.iam.application.service.portal.PortalAccessQuery;
import com.rigour.tenant.iam.application.service.portal.PortalAccessService;
import com.rigour.tenant.iam.application.service.portal.PortalApplication;
import com.rigour.tenant.iam.application.service.portal.PortalCurrentUser;
import com.rigour.tenant.iam.domain.model.session.AuthSession.ClientType;
import com.rigour.tenant.iam.domain.model.session.AuthSession.PrincipalScope;
import com.rigour.tenant.iam.infrastructure.security.session.IamAuthenticationDetails;
import com.rigour.tenant.iam.infrastructure.security.session.IamLoginAuthenticationToken;
import com.rigour.tenant.iam.infrastructure.security.oidc.JdbcRsaJwkSource;
import com.rigour.tenant.iam.infrastructure.security.oidc.PrivateKeyReferenceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.ApplicationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TenantIamServiceApplicationTests {

    @TempDir
    Path temporaryDirectory;

    private static final OAuth2TokenType STATE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.STATE);
    private static final OAuth2TokenType CODE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.CODE);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
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
        registry.add("rigour.iam.oidc.authorization-attributes.keys-base64.v1",
                TenantIamServiceApplicationTests::testEncryptionKeyBase64);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationMapper applicationMapper;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private OAuth2AuthorizationConsentService authorizationConsentService;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @Autowired
    private AuthenticationProvider authenticationProvider;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private IamManagementStore managementStore;

    @Autowired
    private PortalAccessService portalAccessService;

    @Test
    void contextLoadsAndMigratesIamSchema() {
        assertCount("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", 15);
        assertCount("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name LIKE 'iam\\_%'", 34);
        assertCount("SELECT COUNT(*) FROM iam_application", 6);
        assertCount("SELECT COUNT(*) FROM iam_resource", 113);
        assertCount("SELECT COUNT(permission_code) FROM iam_resource", 33);
        assertCount("SELECT COUNT(*) FROM iam_package_resource", 86);
        assertCount("SELECT COUNT(*) FROM iam_resource_ui", 74);
        assertCount("SELECT COUNT(*) FROM iam_application WHERE app_code='PLATFORM_ADMIN' AND target_uri='/platform-admin'", 1);
        assertCount("SELECT COUNT(*) FROM iam_application WHERE app_code='SYSTEM_ADMIN' AND target_uri='/system-admin'", 1);
        assertCount("SELECT COUNT(*) FROM iam_application "
                + "WHERE app_code='DINGHUOBAO' AND app_name='订货宝商城系统' "
                + "AND launch_mode='EXTERNAL_URL' AND target_uri='https://pc.dhb168.com' "
                + "AND status='ACTIVE'", 1);
        assertCount("SELECT COUNT(*) FROM iam_application "
                + "WHERE app_code='DINGHUOBAO_INTEGRATION' AND status='DISABLED'", 1);
        org.assertj.core.api.Assertions.assertThat(applicationMapper.selectById(
                        UUID.fromString("019facf1-0000-7000-8000-000000000003")))
                .extracting("appCode")
                .isEqualTo("SUPPLY_CHAIN");
        org.assertj.core.api.Assertions.assertThat(applicationMapper.selectActiveByScope("TENANT"))
                .extracting("appCode")
                .containsExactly("SYSTEM_ADMIN", "SUPPLY_CHAIN", "DINGHUOBAO");
        assertCount("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'iam_refresh_token' "
                + "AND column_name = 'authorization_id'", 1);
        assertCount("SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema = DATABASE() AND table_name = 'iam_oauth_authorization' "
                + "AND constraint_type = 'FOREIGN KEY'", 2);
        assertCount("SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema = DATABASE() AND table_name = 'iam_oauth_client' "
                + "AND constraint_name = 'uk_iam_oauth_client_id'", 1);
        assertCount("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() "
                + "AND table_name IN ('iam_oauth_authorization', 'iam_refresh_token') "
                + "AND column_name IN ('authorization_code_value', 'access_token_value', "
                + "'oidc_id_token_value', 'refresh_token_value')", 0);
    }

    @Test
    void managementConsoleEnforcesTenantBoundaryAndPersistsRealConfiguration() {
        UUID platformId = insertPlatformIdentity("platform-" + UUID.randomUUID(),
                "Platform-Password-42!", "ACTIVE", "ACTIVE");
        Actor platform = new Actor("PLATFORM", platformId, null);
        assertThat(managementStore.applications(platform)).extracting(ApplicationView::code)
                .contains("PLATFORM_ADMIN", "SYSTEM_ADMIN", "SUPPLY_CHAIN");

        TenantAdminFixture first = insertTenantAdministrator();
        OrganizationView organization = managementStore.createOrganization(first.actor(),
                new OrganizationCommand(null, "ORG-" + UUID.randomUUID(), "销售中心", "DEPARTMENT", 10,
                        "ACTIVE", 0));
        assertThat(managementStore.organizations(first.actor())).extracting(OrganizationView::id)
                .contains(organization.id());

        SettingView setting = managementStore.saveSetting(first.actor(), "portal-theme",
                new SettingCommand("{\"primaryColor\":\"#2457d6\"}", 0));
        assertThat(setting.valueJson()).contains("primaryColor");
        assertThat(managementStore.navigation(first.actor(), "SYSTEM_ADMIN")).isNotEmpty();
        assertThat(managementStore.navigation(first.actor(), "SYSTEM_ADMIN"))
                .anyMatch(node -> "/system-admin".equals(node.routePath()));
        assertThat(managementStore.navigation(first.actor(), "SUPPLY_CHAIN"))
                .anyMatch(node -> "/supply-chain".equals(node.routePath()));

        List<ResourceView> grantable = managementStore.grantableResources(first.actor());
        RoleView role = managementStore.createRole(first.actor(), new RoleCommand(
                "SALES_VIEWER_" + UUID.randomUUID().toString().substring(0, 8), "销售查看员", "CUSTOM",
                "ACTIVE", grantable.stream().limit(4).map(ResourceView::id).toList(), 0));
        UUID subscribedApplicationId = grantable.stream().map(ResourceView::applicationId).findFirst().orElseThrow();
        DataScopeView dataScope = managementStore.saveDataScope(first.actor(), null, new DataScopeCommand(
                role.id(), subscribedApplicationId, "sales-data", "SELF", "ACTIVE", 0));
        assertThat(managementStore.dataScopes(first.actor())).extracting(DataScopeView::id).contains(dataScope.id());
        UUID platformApplicationId = managementStore.applications(platform).stream()
                .filter(application -> "PLATFORM_ADMIN".equals(application.code()))
                .map(ApplicationView::id).findFirst().orElseThrow();
        assertThatThrownBy(() -> managementStore.saveDataScope(first.actor(), null, new DataScopeCommand(
                role.id(), platformApplicationId, "platform-data", "ALL", "ACTIVE", 0)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        UserView user = managementStore.createUser(first.actor(), new UserCommand(
                "user-" + UUID.randomUUID().toString().substring(0, 8), "业务用户", "ACTIVE",
                "Initial-Password-42!", List.of(role.id()), List.of(organization.id()), 0));
        assertThat(user.roleIds()).containsExactly(role.id());
        assertThat(user.organizationIds()).containsExactly(organization.id());
        managementStore.resetUserPassword(first.actor(), user.id(),
                new PasswordResetCommand("Reset-Password-2026!"));
        String resetHash = jdbcTemplate.queryForObject("""
                SELECT password_hash FROM iam_user_credential WHERE tenant_id=? AND user_id=?
                """, String.class, uuidBytes(first.actor().tenantId()), uuidBytes(user.id()));
        assertThat(passwordHasher.matches("Reset-Password-2026!", resetHash)).isTrue();
        assertThat(managementStore.users(first.actor()).stream().filter(item -> item.id().equals(user.id()))
                .findFirst().orElseThrow().securityVersion()).isEqualTo(1);

        UserView tenantAdministrator = managementStore.users(first.actor()).stream()
                .filter(item -> item.id().equals(first.actor().principalId())).findFirst().orElseThrow();
        assertThatThrownBy(() -> managementStore.updateUser(first.actor(), tenantAdministrator.id(),
                new UserCommand(tenantAdministrator.username(), tenantAdministrator.displayName(), "DISABLED", null,
                        tenantAdministrator.roleIds(), tenantAdministrator.organizationIds(), tenantAdministrator.version())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("last active tenant administrator");
        assertThatThrownBy(() -> managementStore.updateRole(first.actor(), first.roleId(), new RoleCommand(
                "TENANT_SUPER_ADMIN", "租户超级管理员", "SYSTEM", "ACTIVE", grantable.stream()
                .map(ResourceView::id).toList(), 0))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        jdbcTemplate.update("UPDATE iam_tenant_subscription SET user_limit=2 WHERE tenant_id=? AND status='ACTIVE'",
                uuidBytes(first.actor().tenantId()));
        assertThatThrownBy(() -> managementStore.createUser(first.actor(), new UserCommand(
                "over-limit-" + UUID.randomUUID().toString().substring(0, 8), "超限用户", "ACTIVE",
                "Initial-Password-42!", List.of(role.id()), List.of(), 0)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("user limit");

        Instant futureStart = Instant.now().plus(Duration.ofDays(30));
        managementStore.subscribeTenant(platform, first.actor().tenantId(), new SubscriptionCommand(
                UUID.fromString("019facf3-0000-7000-8000-000000000002"), futureStart,
                futureStart.plus(Duration.ofDays(365)), 100));
        assertThat(managementStore.subscriptions(platform, first.actor().tenantId()))
                .extracting(SubscriptionView::status).contains("ACTIVE", "SCHEDULED");

        TenantAdminFixture second = insertTenantAdministrator();
        assertThat(managementStore.organizations(second.actor())).extracting(OrganizationView::id)
                .doesNotContain(organization.id());
        assertThatThrownBy(() -> managementStore.updateOrganization(second.actor(), organization.id(),
                new OrganizationCommand(null, organization.code(), "越权修改", organization.type(),
                        organization.sortOrder(), organization.status(), organization.version())))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(managementStore.audits(first.actor(), 100)).extracting(AuditView::action)
                .contains("ORGANIZATION_CREATE", "SETTING_SAVE", "ROLE_CREATE", "DATA_SCOPE_CREATE", "USER_CREATE",
                        "USER_PASSWORD_RESET");
    }

    @Test
    void managesPlatformAndTenantDictionariesWithSeparateBoundaries() {
        UUID platformId = insertPlatformIdentity("dictionary-platform-" + UUID.randomUUID(),
                "Platform-Password-42!", "ACTIVE", "ACTIVE");
        Actor platform = new Actor("PLATFORM", platformId, null);
        DictionaryTypeView platformType = managementStore.createDictionaryType(platform,
                new DictionaryTypeCommand("ORDER_SOURCE", "订单来源", "平台级订单来源字典", "ACTIVE", 0));
        DictionaryItemView platformItem = managementStore.createDictionaryItem(platform,
                new DictionaryItemCommand(platformType.id(), "DINGHUOBAO", "订货宝", "dinghuobao", 10,
                        "ACTIVE", 0));
        assertThat(managementStore.dictionaryTypes(platform)).extracting(DictionaryTypeView::code)
                .contains("ORDER_SOURCE");
        assertThat(managementStore.dictionaryItems(platform, platformType.id()))
                .extracting(DictionaryItemView::code).containsExactly("DINGHUOBAO");

        TenantAdminFixture first = insertTenantAdministrator();
        PortalCurrentUser currentUser = portalAccessService.currentUser(new PortalAccessQuery(
                "TENANT", first.actor().principalId(), first.actor().tenantId()));
        assertThat(currentUser.permissions()).contains("iam:dictionary:write", "integration:dinghuobao:read");
        assertThat(portalAccessService.grantedApplications(new PortalAccessQuery(
                        "TENANT", first.actor().principalId(), first.actor().tenantId())))
                .extracting(PortalApplication::code)
                .contains("SYSTEM_ADMIN", "SUPPLY_CHAIN", "DINGHUOBAO");
        DictionaryTypeView tenantType = managementStore.createDictionaryType(first.actor(),
                new DictionaryTypeCommand("VISIT_RESULT", "拜访结果", "本租户拜访结果字典", "ACTIVE", 0));
        DictionaryItemView tenantItem = managementStore.createDictionaryItem(first.actor(),
                new DictionaryItemCommand(tenantType.id(), "COMPLETED", "已完成", "completed", 10,
                        "ACTIVE", 0));
        assertThat(managementStore.dictionaryTypes(first.actor())).extracting(DictionaryTypeView::code)
                .doesNotContain("ORDER_SOURCE").contains("VISIT_RESULT");
        assertThat(managementStore.dictionaryItems(first.actor(), tenantType.id()))
                .extracting(DictionaryItemView::code).containsExactly("COMPLETED");
        assertThatThrownBy(() -> managementStore.dictionaryItems(first.actor(), platformType.id()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        managementStore.updateDictionaryItem(first.actor(), tenantItem.id(),
                new DictionaryItemCommand(tenantType.id(), "COMPLETED", "已完成拜访", "completed", 20,
                        "ACTIVE", tenantItem.version()));
        assertThat(managementStore.dictionaryItems(first.actor(), tenantType.id()))
                .singleElement().extracting(DictionaryItemView::label).isEqualTo("已完成拜访");
        assertThat(managementStore.audits(first.actor(), 100)).extracting(AuditView::action)
                .contains("DICTIONARY_TYPE_CREATE", "DICTIONARY_ITEM_CREATE", "DICTIONARY_ITEM_UPDATE");
    }

    @Test
    void persistsPublicPkceClientThroughNormalizedOidcTables() {
        RegisteredClient client = portalClient();

        registeredClientRepository.save(client);
        registeredClientRepository.save(client);

        UUID platformId = insertPlatformIdentity("oidc-admin-" + UUID.randomUUID(),
                "Platform-Password-42!", "ACTIVE", "ACTIVE");
        Actor platform = new Actor("PLATFORM", platformId, null);
        ApplicationView supplyChain = managementStore.applications(platform).stream()
                .filter(application -> "SUPPLY_CHAIN".equals(application.code()))
                .findFirst().orElseThrow();
        OidcClientView secondClient = managementStore.saveOidcClient(platform, new OidcClientCommand(
                supplyChain.id(), "rigour-supply-chain-local", "供应链本地应用",
                "https://supply-chain.dev.example/login/oauth2/code/rigour-iam",
                "https://supply-chain.dev.example/"));

        RegisteredClient loadedById = registeredClientRepository.findById(client.getId());
        RegisteredClient loadedByClientId = registeredClientRepository.findByClientId(client.getClientId());
        RegisteredClient loadedSecondClient = registeredClientRepository.findByClientId(secondClient.clientId());
        assertThat(loadedById).isEqualTo(client);
        assertThat(loadedByClientId).isEqualTo(client);
        assertThat(loadedSecondClient).isNotNull();
        assertThat(loadedSecondClient.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(loadedSecondClient.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(loadedById.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(loadedById.getAuthorizationGrantTypes())
                .containsExactlyInAnyOrder(AuthorizationGrantType.AUTHORIZATION_CODE,
                        AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(loadedById.getScopes()).containsExactlyInAnyOrder(OidcScopes.OPENID, OidcScopes.PROFILE);
        assertThat(loadedById.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(loadedById.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertCount("SELECT COUNT(*) FROM iam_oauth_client", 2);
        assertCount("SELECT COUNT(*) FROM iam_oauth_client_redirect_uri", 4);
        assertThatThrownBy(() -> managementStore.saveOidcClient(platform, new OidcClientCommand(
                supplyChain.id(), "unsafe-loopback", "未显式开启的本地应用",
                "http://127.0.0.1:9999/callback", "http://127.0.0.1:9999/")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicitly enabled loopback HTTP");
    }

    @Test
    void savesAndSoftRevokesAuthorizationConsent() {
        RegisteredClient client = portalClient();
        registeredClientRepository.save(client);
        OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent.withId(client.getId(), "sales-user-001")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .authority(new SimpleGrantedAuthority("ROLE_PORTAL_USER"))
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
        RegisteredClient confidentialClient = RegisteredClient.from(portalClient())
                .clientAuthenticationMethods(methods -> {
                    methods.clear();
                    methods.add(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
                })
                .clientSecret("plaintext-secret")
                .clientSettings(ClientSettings.builder()
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
        RegisteredClient client = portalClient();
        registeredClientRepository.save(client);
        SessionFixture session = insertActiveSession();
        String internalState = "consent-state-that-must-not-be-stored";
        OAuth2Authorization authorization = pendingAuthorization(client, session, internalState);

        authorizationService.save(authorization);

        OAuth2Authorization loadedByState = authorizationService.findByToken(internalState, STATE_TOKEN_TYPE);
        assertThat(loadedByState).isNotNull();
        assertThat(loadedByState.<String>getAttribute(OAuth2ParameterNames.STATE)).isEqualTo(internalState);
        assertThat(loadedByState.<OAuth2AuthorizationRequest>getAttribute(
                OAuth2AuthorizationRequest.class.getName())).isEqualTo(
                authorization.getAttribute(OAuth2AuthorizationRequest.class.getName()));
        assertThat(loadedByState.<org.springframework.security.core.Authentication>getAttribute(
                Principal.class.getName()).getName()).isEqualTo(authorization.getPrincipalName());

        OAuth2Authorization loadedById = authorizationService.findById(authorization.getId());
        assertThat(loadedById).isNotNull();
        assertThat(loadedById.<Object>getAttribute(OAuth2ParameterNames.STATE)).isNull();
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM iam_oauth_authorization
                        WHERE id = ? AND state_hash = UNHEX(SHA2(?, 256))
                          AND attributes_key_version = 'v1'
                          AND attributes_ciphertext IS NOT NULL
                        """, Integer.class, uuidBytes(UUID.fromString(authorization.getId())), internalState))
                .isEqualTo(1);
        byte[] ciphertext = jdbcTemplate.queryForObject(
                "SELECT attributes_ciphertext FROM iam_oauth_authorization WHERE id = ?",
                byte[].class, uuidBytes(UUID.fromString(authorization.getId())));
        assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain(internalState);
    }

    @Test
    void consumesAuthorizationCodeOnceAndSoftRevokesAuthorization() {
        RegisteredClient client = portalClient();
        registeredClientRepository.save(client);
        SessionFixture session = insertActiveSession();
        String rawCode = "authorization-code-that-must-not-be-stored";
        Instant issuedAt = Instant.now().minusSeconds(5);
        OAuth2AuthorizationCode code = new OAuth2AuthorizationCode(rawCode, issuedAt, issuedAt.plusSeconds(300));
        OAuth2Authorization authorization = authorizationWithCode(client, session, code);

        authorizationService.save(authorization);

        OAuth2Authorization loaded = authorizationService.findByToken(rawCode, CODE_TOKEN_TYPE);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getToken(OAuth2AuthorizationCode.class).getToken().getTokenValue()).isEqualTo(rawCode);
        assertThat(loaded.getToken(OAuth2AuthorizationCode.class).isActive()).isTrue();
        OAuth2Authorization consumed = OAuth2Authorization.from(loaded).invalidate(
                loaded.getToken(OAuth2AuthorizationCode.class).getToken()).build();
        authorizationService.save(consumed);

        OAuth2Authorization loadedAfterConsumption = authorizationService.findByToken(rawCode, CODE_TOKEN_TYPE);
        assertThat(loadedAfterConsumption.getToken(OAuth2AuthorizationCode.class).isInvalidated()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM iam_oauth_authorization
                        WHERE id = ? AND authorization_code_hash = UNHEX(SHA2(?, 256))
                          AND code_consumed_at IS NOT NULL
                        """, Integer.class, uuidBytes(UUID.fromString(authorization.getId())), rawCode))
                .isEqualTo(1);

        authorizationService.remove(loadedAfterConsumption);
        assertThat(authorizationService.findByToken(rawCode, CODE_TOKEN_TYPE)).isNull();
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM iam_oauth_authorization
                        WHERE id = ? AND status = 'REVOKED' AND revoked_at IS NOT NULL
                """, Integer.class, uuidBytes(UUID.fromString(authorization.getId())))).isEqualTo(1);
    }

    @Test
    void rotatesHashedRefreshTokenAndRevokesSessionOnReplay() {
        RegisteredClient client = portalClient();
        registeredClientRepository.save(client);
        SessionFixture session = insertActiveSession();
        Instant issuedAt = Instant.now().minusSeconds(2);
        OAuth2AuthorizationCode code = new OAuth2AuthorizationCode(
                "refresh-flow-authorization-code", issuedAt, issuedAt.plusSeconds(300));
        OAuth2Authorization codeAuthorization = authorizationWithCode(client, session, code);
        authorizationService.save(codeAuthorization);

        OAuth2Authorization loadedByCode = authorizationService.findByToken(
                code.getTokenValue(), CODE_TOKEN_TYPE);
        String firstRawRefreshToken = "first-refresh-token-that-must-not-be-stored";
        OAuth2Authorization firstIssued = OAuth2Authorization.from(loadedByCode)
                .invalidate(loadedByCode.getToken(OAuth2AuthorizationCode.class).getToken())
                .accessToken(new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER, "self-contained-access-token-one",
                        issuedAt, issuedAt.plusSeconds(900), Set.of("openid", "profile")))
                .refreshToken(new OAuth2RefreshToken(
                        firstRawRefreshToken, issuedAt, issuedAt.plusSeconds(604800)))
                .build();
        authorizationService.save(firstIssued);

        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM iam_refresh_token
                         WHERE authorization_id = ? AND token_hash = UNHEX(SHA2(?, 256))
                           AND consumed_at IS NULL AND revoked_at IS NULL
                        """, Integer.class, uuidBytes(UUID.fromString(firstIssued.getId())), firstRawRefreshToken))
                .isEqualTo(1);
        OAuth2Authorization loadedByRefresh = authorizationService.findByToken(
                firstRawRefreshToken, OAuth2TokenType.REFRESH_TOKEN);
        assertThat(loadedByRefresh).isNotNull();
        assertThat(loadedByRefresh.getRefreshToken().getToken().getTokenValue())
                .isEqualTo(firstRawRefreshToken);

        String secondRawRefreshToken = "second-refresh-token-that-must-not-be-stored";
        Instant rotatedAt = Instant.now();
        OAuth2Authorization rotated = OAuth2Authorization.from(loadedByRefresh)
                .accessToken(new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER, "self-contained-access-token-two",
                        rotatedAt, rotatedAt.plusSeconds(900), Set.of("openid", "profile")))
                .refreshToken(new OAuth2RefreshToken(
                        secondRawRefreshToken, rotatedAt, rotatedAt.plusSeconds(604800)))
                .build();
        authorizationService.save(rotated);

        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM iam_refresh_token old_token
                          JOIN iam_refresh_token new_token ON new_token.id = old_token.replaced_by_id
                         WHERE old_token.authorization_id = ?
                           AND old_token.token_hash = UNHEX(SHA2(?, 256))
                           AND old_token.consumed_at IS NOT NULL
                           AND new_token.token_hash = UNHEX(SHA2(?, 256))
                        """, Integer.class, uuidBytes(UUID.fromString(rotated.getId())),
                firstRawRefreshToken, secondRawRefreshToken)).isEqualTo(1);

        assertThat(authorizationService.findByToken(
                firstRawRefreshToken, OAuth2TokenType.REFRESH_TOKEN)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_auth_session WHERE id = ?",
                String.class, uuidBytes(session.sessionId()))).isEqualTo("REVOKED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_oauth_authorization WHERE id = ?",
                String.class, uuidBytes(UUID.fromString(rotated.getId())))).isEqualTo("REVOKED");
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM iam_refresh_token
                         WHERE authorization_id = ? AND revoked_at IS NOT NULL
                        """, Integer.class, uuidBytes(UUID.fromString(rotated.getId())))).isEqualTo(2);
    }

    @Test
    void loadsRestrictedRsa3072KeyByReferenceAndSignsWithCatalogKid() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair keyPair = generator.generateKeyPair();
        String kid = "test-signing-" + UUID.randomUUID();
        Path privateKeyFile = temporaryDirectory.resolve(kid + ".pem");
        String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(privateKeyFile, privateKeyPem, StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(privateKeyFile, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // 测试文件系统不支持POSIX权限时，仍验证绝对路径和非符号链接规则。
        }

        RSAKey publicJwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .keyID(kid)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
        UUID keyId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                        INSERT INTO iam_signing_key (
                            id, kid, algorithm, key_use, public_jwk_json, private_key_ref, status,
                            not_before, not_after, activated_at, created_at
                        ) VALUES (?, ?, 'RS256', 'sig', ?, ?, 'ACTIVE', ?, ?, ?, ?)
                        """, uuidBytes(keyId), kid, publicJwk.toJSONString(),
                "file:" + privateKeyFile.toAbsolutePath(), now.minusMinutes(1), now.plusDays(1), now, now);
        try {
            JdbcRsaJwkSource jwkSource = new JdbcRsaJwkSource(
                    jdbcTemplate, new PrivateKeyReferenceResolver(), 3072);
            assertThat(jwkSource.get(new JWKSelector(new JWKMatcher.Builder()
                            .keyID(kid).algorithm(JWSAlgorithm.RS256).privateOnly(true).build()), null))
                    .singleElement()
                    .satisfies(jwk -> assertThat(jwk.isPrivate()).isTrue());

            NimbusJwtEncoder encoder = new NimbusJwtEncoder(jwkSource);
            Instant issuedAt = Instant.now();
            Jwt jwt = encoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(SignatureAlgorithm.RS256).keyId(kid).build(),
                    JwtClaimsSet.builder()
                            .issuer("https://iam.dev.rigour.local")
                            .subject(UUID.randomUUID().toString())
                            .issuedAt(issuedAt)
                            .expiresAt(issuedAt.plusSeconds(60))
                            .build()));
            assertThat(jwt.getHeaders()).containsEntry("kid", kid);
            Jwt decoded = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic())
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
        RegisteredClient client = portalClient();
        registeredClientRepository.save(client);
        SessionFixture session = insertActiveSession();
        UsernamePasswordAuthenticationToken mismatchedPrincipal = UsernamePasswordAuthenticationToken.authenticated(
                UUID.randomUUID().toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_PORTAL_USER")));
        mismatchedPrincipal.setDetails(IamAuthenticationDetails.create(
                session.sessionId(), PrincipalScope.PLATFORM.name(), session.principalId(), null, 0));
        OAuth2Authorization authorization = baseAuthorization(client, mismatchedPrincipal).build();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> authorizationService.save(authorization))
                .withMessageContaining("does not match");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM iam_oauth_authorization WHERE id = ?",
                Integer.class, uuidBytes(UUID.fromString(authorization.getId())))).isZero();
    }

    @Test
    void authenticatesPlatformUserAndCreatesCredentialFreeSession() {
        String username = "platform-" + UUID.randomUUID();
        String password = "Dev-Test-Password-42!";
        UUID principalId = insertPlatformIdentity(username, password, "ACTIVE", "ACTIVE");
        IamLoginAuthenticationToken request = loginToken(
                PrincipalScope.PLATFORM, null, username, password);

        org.springframework.security.core.Authentication authenticated =
                authenticationProvider.authenticate(request);

        assertThat(authenticated.isAuthenticated()).isTrue();
        assertThat(authenticated.getCredentials()).isNull();
        assertThat(request.getCredentials()).isNull();
        assertThat(authenticated.getPrincipal()).isEqualTo(principalId.toString());
        assertThat(authenticated.getName()).isEqualTo(principalId.toString());
        @SuppressWarnings("unchecked")
        Map<String, String> details = (Map<String, String>) authenticated.getDetails();
        assertThat(details).containsEntry(IamAuthenticationDetails.PRINCIPAL_SCOPE, "PLATFORM")
                .containsEntry(IamAuthenticationDetails.PRINCIPAL_ID, principalId.toString());
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM iam_auth_session
                         WHERE id = ? AND principal_scope = 'PLATFORM' AND tenant_id IS NULL
                           AND principal_id = ? AND status = 'ACTIVE' AND expires_at > issued_at
                        """, Integer.class,
                uuidBytes(UUID.fromString(details.get(IamAuthenticationDetails.SESSION_ID))),
                uuidBytes(principalId))).isEqualTo(1);
    }

    @Test
    void authenticatesTenantOnlyWithinRequestedTenant() {
        String username = "tenant-user-" + UUID.randomUUID();
        String password = "Tenant-Password-42!";
        TenantIdentityFixture tenant = insertTenantIdentity(username, password, "ACTIVE", "ACTIVE", "ACTIVE");

        IamLoginAuthenticationToken correct = loginToken(
                PrincipalScope.TENANT, tenant.tenantCode(), username, password);
        org.springframework.security.core.Authentication authenticated =
                authenticationProvider.authenticate(correct);
        @SuppressWarnings("unchecked")
        Map<String, String> tenantDetails = (Map<String, String>) authenticated.getDetails();
        assertThat(tenantDetails).containsEntry(
                IamAuthenticationDetails.TENANT_ID, tenant.tenantId().toString());

        IamLoginAuthenticationToken crossTenant = loginToken(
                PrincipalScope.TENANT, "another-tenant", username, password);
        assertThatThrownBy(() -> authenticationProvider.authenticate(crossTenant))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Authentication failed");
        assertThat(crossTenant.getCredentials()).isNull();
    }

    @Test
    void commitsFailureCountAndLocksCredentialBeforeReturningUniformFailure() {
        String username = "locked-user-" + UUID.randomUUID();
        String password = "Correct-Password-42!";
        UUID principalId = insertPlatformIdentity(username, password, "ACTIVE", "ACTIVE");

        for (int attempt = 0; attempt < 5; attempt++) {
            IamLoginAuthenticationToken request = loginToken(
                    PrincipalScope.PLATFORM, null, username, "Wrong-Password-42!");
            assertThatThrownBy(() -> authenticationProvider.authenticate(request))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessage("Authentication failed");
        }

        Map<String, Object> lockState = jdbcTemplate.queryForMap("""
                SELECT c.failed_attempts, c.locked_until
                  FROM iam_platform_user_credential c
                 WHERE c.platform_user_id = ?
                """, uuidBytes(principalId));
        assertThat(lockState.get("failed_attempts")).isEqualTo(5L);
        assertThat(lockState.get("locked_until")).isNotNull();
        IamLoginAuthenticationToken lockedRequest = loginToken(
                PrincipalScope.PLATFORM, null, username, password);
        assertThatThrownBy(() -> authenticationProvider.authenticate(lockedRequest))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Authentication failed");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM iam_auth_session WHERE principal_id = ?",
                Integer.class, uuidBytes(principalId))).isZero();
    }

    @Test
    void rejectsDisabledPrincipalCredentialAndTenantWithSameExternalFailure() {
        String password = "Disabled-Password-42!";
        String platformUsername = "disabled-platform-" + UUID.randomUUID();
        insertPlatformIdentity(platformUsername, password, "DISABLED", "ACTIVE");
        TenantIdentityFixture suspendedTenant = insertTenantIdentity(
                "suspended-user-" + UUID.randomUUID(), password, "ACTIVE", "ACTIVE", "SUSPENDED");

        assertUniformFailure(loginToken(PrincipalScope.PLATFORM, null, platformUsername, password));
        assertUniformFailure(loginToken(
                PrincipalScope.TENANT, suspendedTenant.tenantCode(), suspendedTenant.username(), password));
        assertUniformFailure(loginToken(
                PrincipalScope.PLATFORM, null, "missing-" + UUID.randomUUID(), password));
    }

    private OAuth2Authorization pendingAuthorization(
            RegisteredClient client,
            SessionFixture session,
            String internalState
    ) {
        UsernamePasswordAuthenticationToken principal = authenticatedPrincipal(session);
        return baseAuthorization(client, principal)
                .attribute(OAuth2ParameterNames.STATE, internalState)
                .build();
    }

    private OAuth2Authorization authorizationWithCode(
            RegisteredClient client,
            SessionFixture session,
            OAuth2AuthorizationCode code
    ) {
        UsernamePasswordAuthenticationToken principal = authenticatedPrincipal(session);
        return baseAuthorization(client, principal)
                .authorizedScopes(Set.of(OidcScopes.OPENID, OidcScopes.PROFILE))
                .token(code)
                .build();
    }

    private OAuth2Authorization.Builder baseAuthorization(
            RegisteredClient client,
            UsernamePasswordAuthenticationToken principal
    ) {
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://iam.dev.rigour.local/oauth2/authorize")
                .clientId(client.getClientId())
                .redirectUri(client.getRedirectUris().iterator().next())
                .scopes(Set.of(OidcScopes.OPENID, OidcScopes.PROFILE))
                .state("browser-state-inside-encrypted-context")
                .additionalParameters(Map.of(
                        PkceParameterNames.CODE_CHALLENGE, "test-code-challenge",
                        PkceParameterNames.CODE_CHALLENGE_METHOD, "S256"
                ))
                .build();
        return OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(principal.getName())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute(Principal.class.getName(), principal)
                .attribute(OAuth2AuthorizationRequest.class.getName(), authorizationRequest);
    }

    private UsernamePasswordAuthenticationToken authenticatedPrincipal(SessionFixture session) {
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                session.principalId().toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_PORTAL_USER"))
        );
        authentication.setDetails(IamAuthenticationDetails.create(
                session.sessionId(), PrincipalScope.PLATFORM.name(), session.principalId(), null, 0));
        return authentication;
    }

    private SessionFixture insertActiveSession() {
        UUID sessionId = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        LocalDateTime issuedAt = LocalDateTime.ofInstant(Instant.now().minusSeconds(5), ZoneOffset.UTC);
        jdbcTemplate.update("""
                        INSERT INTO iam_auth_session (
                            id, principal_scope, tenant_id, principal_id, client_type,
                            device_name, client_fingerprint_hash, user_agent_hash, ip_address,
                            issued_at, last_seen_at, expires_at, revoked_at, revoke_reason,
                            status, version
                        ) VALUES (?, 'PLATFORM', NULL, ?, 'WEB', 'integration-test', NULL, NULL, NULL,
                                  ?, ?, ?, NULL, NULL, 'ACTIVE', 0)
                        """,
                uuidBytes(sessionId), uuidBytes(principalId), issuedAt, issuedAt,
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

    private record SessionFixture(UUID sessionId, UUID principalId) {
    }

    private IamLoginAuthenticationToken loginToken(
            PrincipalScope scope, String tenantCode, String username, String password) {
        return new IamLoginAuthenticationToken(
                scope, tenantCode, username, password.toCharArray(), ClientType.WEB,
                "integration-test", null, null, new byte[]{127, 0, 0, 1});
    }

    private UUID insertPlatformIdentity(
            String username, String password, String userStatus, String credentialStatus) {
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                        INSERT INTO iam_platform_user (
                            id, username, display_name, platform_role, status, security_version, version,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, 'SUPER_ADMIN', ?, 0, 0, ?, ?)
                        """, uuidBytes(userId), username, "Platform Test User", userStatus, now, now);
        jdbcTemplate.update("""
                        INSERT INTO iam_platform_user_credential (
                            id, platform_user_id, credential_type, password_hash, algorithm, algorithm_version,
                            failed_attempts, password_changed_at, status, version, created_at, updated_at
                        ) VALUES (?, ?, 'PASSWORD', ?, 'ARGON2ID', 1, 0, ?, ?, 0, ?, ?)
                        """, uuidBytes(credentialId), uuidBytes(userId), passwordHasher.hash(password),
                now, credentialStatus, now, now);
        return userId;
    }

    private TenantIdentityFixture insertTenantIdentity(
            String username,
            String password,
            String userStatus,
            String credentialStatus,
            String tenantStatus
    ) {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        String tenantCode = "tenant-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcTemplate.update("""
                        INSERT INTO iam_tenant (
                            id, tenant_code, company_name, status, policy_version, version, created_at, updated_at
                        ) VALUES (?, ?, 'Test Tenant', ?, 0, 0, ?, ?)
                        """, uuidBytes(tenantId), tenantCode, tenantStatus, now, now);
        jdbcTemplate.update("""
                        INSERT INTO iam_user (
                            id, tenant_id, username, display_name, status, security_version, version,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, 'Tenant Test User', ?, 0, 0, ?, ?)
                        """, uuidBytes(userId), uuidBytes(tenantId), username, userStatus, now, now);
        jdbcTemplate.update("""
                        INSERT INTO iam_user_credential (
                            id, tenant_id, user_id, credential_type, password_hash, algorithm, algorithm_version,
                            failed_attempts, password_changed_at, status, version, created_at, updated_at
                        ) VALUES (?, ?, ?, 'PASSWORD', ?, 'ARGON2ID', 1, 0, ?, ?, 0, ?, ?)
                        """, uuidBytes(credentialId), uuidBytes(tenantId), uuidBytes(userId),
                passwordHasher.hash(password), now, credentialStatus, now, now);
        return new TenantIdentityFixture(tenantId, tenantCode, userId, username);
    }

    private TenantAdminFixture insertTenantAdministrator() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expires = now.plusYears(1);
        jdbcTemplate.update("""
                INSERT INTO iam_tenant
                (id, tenant_code, company_name, status, policy_version, version, created_at, updated_at)
                VALUES (?, ?, 'Management Test Tenant', 'ACTIVE', 0, 0, ?, ?)
                """, uuidBytes(tenantId), "mgmt-" + UUID.randomUUID().toString().substring(0, 8), now, now);
        jdbcTemplate.update("""
                INSERT INTO iam_tenant_subscription
                (id, tenant_id, package_version_id, effective_from, effective_to, user_limit, status,
                 created_at, updated_at)
                VALUES (?, ?, UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), ?, ?, 100, 'ACTIVE', ?, ?)
                """, uuidBytes(UUID.randomUUID()), uuidBytes(tenantId), now.minusMinutes(1), expires, now, now);
        jdbcTemplate.update("""
                INSERT INTO iam_user
                (id, tenant_id, username, display_name, status, security_version, version, created_at, updated_at)
                VALUES (?, ?, ?, 'Tenant Administrator', 'ACTIVE', 0, 0, ?, ?)
                """, uuidBytes(userId), uuidBytes(tenantId),
                "admin-" + UUID.randomUUID().toString().substring(0, 8), now, now);
        jdbcTemplate.update("""
                INSERT INTO iam_role
                (id, tenant_id, role_code, role_name, role_type, status, created_at, updated_at)
                VALUES (?, ?, 'TENANT_SUPER_ADMIN', '租户超级管理员', 'SYSTEM', 'ACTIVE', ?, ?)
                """, uuidBytes(roleId), uuidBytes(tenantId), now, now);
        jdbcTemplate.update("""
                INSERT INTO iam_role_resource
                (tenant_id, role_id, resource_id, status, created_at, updated_at)
                SELECT ?, ?, resource_id, 'ACTIVE', ?, ? FROM iam_package_resource
                 WHERE package_version_id=UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
                """, uuidBytes(tenantId), uuidBytes(roleId), now, now);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM iam_package_resource
                 WHERE package_version_id=UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
                """, Integer.class)).isEqualTo(86);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM iam_package_resource package_resource
                 JOIN iam_resource resource_record ON resource_record.id=package_resource.resource_id
                 WHERE package_resource.package_version_id=UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
                   AND resource_record.permission_code='iam:dictionary:write'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM iam_role_resource rr
                 JOIN iam_resource resource_record ON resource_record.id=rr.resource_id
                 WHERE rr.tenant_id=? AND rr.role_id=? AND resource_record.permission_code='iam:dictionary:write'
                """, Integer.class, uuidBytes(tenantId), uuidBytes(roleId))).isEqualTo(1);
        jdbcTemplate.update("""
                INSERT INTO iam_user_role
                (tenant_id, user_id, role_id, status, effective_from, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, uuidBytes(tenantId), uuidBytes(userId), uuidBytes(roleId), now, now, now);
        return new TenantAdminFixture(new Actor("TENANT", userId, tenantId), roleId);
    }

    private void assertUniformFailure(IamLoginAuthenticationToken request) {
        assertThatThrownBy(() -> authenticationProvider.authenticate(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Authentication failed");
        assertThat(request.getCredentials()).isNull();
    }

    private record TenantIdentityFixture(UUID tenantId, String tenantCode, UUID userId, String username) {
    }

    private record TenantAdminFixture(Actor actor, UUID roleId) {
    }

    private RegisteredClient portalClient() {
        return RegisteredClient.withId("019fb000-0000-7000-8000-000000000001")
                .clientId("rigour-portal")
                .clientIdIssuedAt(Instant.parse("2026-07-31T00:00:00Z"))
                .clientName("瑞盖统一门户")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://portal.dev.rigour.local/login/oauth2/code/rigour-iam")
                .postLogoutRedirectUri("https://portal.dev.rigour.local/")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
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

    private void assertCount(String sql, int expected) {
        Integer actual = jdbcTemplate.queryForObject(sql, Integer.class);
        org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(expected);
    }
}
