package com.rigour.tenant.iam.infrastructure.persistence.management;

import com.rigour.tenant.iam.application.port.out.IamManagementStore;
import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import com.rigour.tenant.iam.application.port.out.PasswordHasher;
import com.rigour.tenant.iam.application.service.management.ManagementModels.*;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.net.URI;
import java.time.Duration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/** JDBC管理仓储；所有租户管理查询在SQL层绑定已验签主体的tenantId。 */
public final class JdbcIamManagementStore implements IamManagementStore {
    private static final Set<String> APPLICATION_SCOPES = Set.of("PLATFORM", "TENANT");
    private static final Set<String> APPLICATION_TYPES = Set.of("INTERNAL", "EXTERNAL");
    private static final Set<String> APPLICATION_STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final Set<String> RESOURCE_TYPES = Set.of("APPLICATION", "MENU", "PAGE", "BUTTON", "API");
    private static final Set<String> RESOURCE_STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final Set<String> ORG_TYPES = Set.of("COMPANY", "REGION", "CITY", "DEPARTMENT", "TEAM");
    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "LOCKED", "DISABLED");
    private static final Set<String> ROLE_TYPES = Set.of("SYSTEM", "CUSTOM");
    private static final Set<String> ROLE_STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final Set<String> DATA_SCOPE_TYPES = Set.of("SELF", "MY_STORES", "MY_CITY", "MY_REGION", "ALL");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final IdentifierGenerator ids;
    private final PasswordHasher passwordHasher;
    private final RegisteredClientRepository registeredClients;
    private final boolean allowInsecureLoopback;

    public JdbcIamManagementStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                  IdentifierGenerator ids, PasswordHasher passwordHasher,
                                  RegisteredClientRepository registeredClients,
                                  boolean allowInsecureLoopback) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.ids = ids;
        this.passwordHasher = passwordHasher;
        this.registeredClients = registeredClients;
        this.allowInsecureLoopback = allowInsecureLoopback;
    }

    @Override
    public List<OidcClientView> oidcClients(Actor actor) {
        requirePlatform(actor);
        return jdbc.query("""
                SELECT c.id, c.application_id, c.client_id, c.client_name,
                       login_uri.uri redirect_uri, logout_uri.uri post_logout_redirect_uri
                  FROM iam_oauth_client c
                  JOIN iam_oauth_client_redirect_uri login_uri ON login_uri.client_id=c.id
                   AND login_uri.uri_type='LOGIN_REDIRECT' AND login_uri.status='ACTIVE'
                  JOIN iam_oauth_client_redirect_uri logout_uri ON logout_uri.client_id=c.id
                   AND logout_uri.uri_type='POST_LOGOUT_REDIRECT' AND logout_uri.status='ACTIVE'
                 WHERE c.status='ACTIVE' ORDER BY c.client_id
                """, (rs, row) -> new OidcClientView(uuid(rs, "id"), uuid(rs, "application_id"),
                rs.getString("client_id"), rs.getString("client_name"), rs.getString("redirect_uri"),
                rs.getString("post_logout_redirect_uri")));
    }

    @Override
    public OidcClientView saveOidcClient(Actor actor, OidcClientCommand command) {
        requirePlatform(actor);
        if (registeredClients == null) throw new IllegalStateException("Registered client repository is unavailable");
        requireApplication(command.applicationId());
        String redirectUri = trustedRedirect(command.redirectUri());
        String logoutUri = trustedRedirect(command.postLogoutRedirectUri());
        RegisteredClient existing = registeredClients.findByClientId(required(command.clientId(), "clientId"));
        UUID id = existing == null ? ids.nextId() : UUID.fromString(existing.getId());
        RegisteredClient client = RegisteredClient.withId(id.toString())
                .clientId(required(command.clientId(), "clientId"))
                .clientIdIssuedAt(existing == null ? Instant.now() : existing.getClientIdIssuedAt())
                .clientName(required(command.clientName(), "clientName"))
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri).postLogoutRedirectUri(logoutUri)
                .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(TokenSettings.builder().authorizationCodeTimeToLive(Duration.ofMinutes(5))
                        .accessTokenTimeToLive(Duration.ofMinutes(15)).accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .refreshTokenTimeToLive(Duration.ofDays(7)).reuseRefreshTokens(false)
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256).build()).build();
        transaction.executeWithoutResult(status -> {
            registeredClients.save(client);
            jdbc.update("UPDATE iam_oauth_client SET application_id=?, updated_by=?, updated_at=UTC_TIMESTAMP(6) WHERE id=?",
                    bin(command.applicationId()), bin(actor.principalId()), bin(id));
            audit(actor, existing == null ? "OIDC_CLIENT_CREATE" : "OIDC_CLIENT_UPDATE", "OIDC_CLIENT", id);
        });
        return new OidcClientView(id, command.applicationId(), client.getClientId(), client.getClientName(),
                redirectUri, logoutUri);
    }

    @Override
    public List<ApplicationView> applications(Actor actor) {
        requirePlatform(actor);
        return jdbc.query("""
                SELECT id, app_code, app_name, app_scope, app_type, icon_key, sort_order,
                       launch_mode, target_uri, status, version
                  FROM iam_application WHERE deleted_at IS NULL ORDER BY sort_order, app_code
                """, (rs, row) -> application(rs));
    }

    @Override
    public ApplicationView createApplication(Actor actor, ApplicationCommand command) {
        requirePlatform(actor);
        validateApplication(command);
        UUID id = ids.nextId();
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO iam_application
                    (id, app_code, app_name, app_scope, app_type, icon_key, sort_order, launch_mode,
                     target_uri, credential_ref, status, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(id), normalizedCode(command.code()), required(command.name(), "name"),
                    allowed(command.scope(), APPLICATION_SCOPES, "scope"),
                    allowed(command.type(), APPLICATION_TYPES, "type"), blankToNull(command.iconKey()),
                    command.sortOrder(), required(command.launchMode(), "launchMode"),
                    blankToNull(command.targetUri()), allowed(command.status(), APPLICATION_STATUSES, "status"),
                    bin(actor.principalId()), bin(actor.principalId()));
            audit(actor, "APPLICATION_CREATE", "APPLICATION", id);
        });
        return applicationById(id);
    }

    @Override
    public ApplicationView updateApplication(Actor actor, UUID id, ApplicationCommand command) {
        requirePlatform(actor);
        validateApplication(command);
        transaction.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE iam_application
                       SET app_name=?, icon_key=?, sort_order=?, launch_mode=?, target_uri=?, status=?,
                           version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE id=? AND app_code=? AND app_scope=? AND app_type=? AND version=? AND deleted_at IS NULL
                    """, required(command.name(), "name"), blankToNull(command.iconKey()), command.sortOrder(),
                    required(command.launchMode(), "launchMode"), blankToNull(command.targetUri()),
                    allowed(command.status(), APPLICATION_STATUSES, "status"), bin(actor.principalId()), bin(id),
                    normalizedCode(command.code()), allowed(command.scope(), APPLICATION_SCOPES, "scope"),
                    allowed(command.type(), APPLICATION_TYPES, "type"), command.version());
            requireChanged(changed);
            audit(actor, "APPLICATION_UPDATE", "APPLICATION", id);
        });
        return applicationById(id);
    }

    @Override
    public List<ResourceView> resources(Actor actor, UUID applicationId) {
        requirePlatform(actor);
        return jdbc.query("""
                SELECT r.id, r.application_id, r.parent_id, r.resource_code, r.resource_type,
                       r.permission_code, r.display_name, r.sort_order, r.status, r.version,
                       ui.route_key, ui.route_path, ui.icon_key, ui.visible, ui.keep_alive
                  FROM iam_resource r LEFT JOIN iam_resource_ui ui ON ui.resource_id=r.id
                 WHERE r.application_id=? AND r.deleted_at IS NULL
                 ORDER BY r.sort_order, r.resource_code
                """, (rs, row) -> resource(rs), bin(applicationId));
    }

    @Override
    public ResourceView createResource(Actor actor, ResourceCommand command) {
        requirePlatform(actor);
        validateResource(command);
        UUID id = ids.nextId();
        transaction.executeWithoutResult(status -> {
            validateParent(command.applicationId(), command.parentId(), null);
            jdbc.update("""
                    INSERT INTO iam_resource
                    (id, application_id, parent_id, resource_code, resource_type, permission_code,
                     display_name, sort_order, status, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(id), bin(command.applicationId()), bin(command.parentId()), normalizedCode(command.code()),
                    allowed(command.type(), RESOURCE_TYPES, "type"), normalizedPermissionCode(command.permissionCode()),
                    required(command.displayName(), "displayName"), command.sortOrder(),
                    allowed(command.status(), RESOURCE_STATUSES, "status"), bin(actor.principalId()),
                    bin(actor.principalId()));
            saveResourceUi(id, command, false);
            bumpAllTenantPolicies();
            audit(actor, "RESOURCE_CREATE", "RESOURCE", id);
        });
        return resourceById(id);
    }

    @Override
    public ResourceView updateResource(Actor actor, UUID id, ResourceCommand command) {
        requirePlatform(actor);
        validateResource(command);
        transaction.executeWithoutResult(status -> {
            validateParent(command.applicationId(), command.parentId(), id);
            int changed = jdbc.update("""
                    UPDATE iam_resource
                       SET parent_id=?, permission_code=?, display_name=?, sort_order=?, status=?,
                           version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE id=? AND application_id=? AND resource_code=? AND resource_type=?
                       AND version=? AND deleted_at IS NULL
                    """, bin(command.parentId()), normalizedPermissionCode(command.permissionCode()),
                    required(command.displayName(), "displayName"), command.sortOrder(),
                    allowed(command.status(), RESOURCE_STATUSES, "status"), bin(actor.principalId()), bin(id),
                    bin(command.applicationId()), normalizedCode(command.code()),
                    allowed(command.type(), RESOURCE_TYPES, "type"), command.version());
            requireChanged(changed);
            saveResourceUi(id, command, true);
            bumpAllTenantPolicies();
            audit(actor, "RESOURCE_UPDATE", "RESOURCE", id);
        });
        return resourceById(id);
    }

    @Override
    public List<NavigationNode> navigation(Actor actor, String applicationCode) {
        String code = normalizedCode(applicationCode);
        List<ResourceView> flat;
        if ("PLATFORM".equals(actor.scope())) {
            requirePlatform(actor);
            flat = jdbc.query("""
                    SELECT r.id, r.application_id, r.parent_id, r.resource_code, r.resource_type,
                           r.permission_code, r.display_name, r.sort_order, r.status, r.version,
                           ui.route_key, ui.route_path, ui.icon_key, ui.visible, ui.keep_alive
                      FROM iam_application a JOIN iam_resource r ON r.application_id=a.id
                      JOIN iam_resource_ui ui ON ui.resource_id=r.id
                     WHERE a.app_code=? AND a.app_scope='PLATFORM' AND a.status='ACTIVE'
                       AND a.deleted_at IS NULL AND r.status='ACTIVE' AND r.deleted_at IS NULL
                       AND r.resource_type IN ('MENU','PAGE')
                     ORDER BY r.sort_order, r.resource_code
                    """, (rs, row) -> resource(rs), code);
        } else {
            flat = jdbc.query("""
                    SELECT DISTINCT r.id, r.application_id,
                           COALESCE(menu_config.parent_group_id, r.parent_id) parent_id,
                           r.resource_code, r.resource_type, r.permission_code,
                           COALESCE(menu_config.display_name_override, r.display_name) display_name,
                           COALESCE(menu_config.sort_order_override, r.sort_order) sort_order,
                           r.status, r.version, ui.route_key, ui.route_path,
                           COALESCE(menu_config.icon_key_override, ui.icon_key) icon_key,
                           ui.visible * menu_config.visible visible, ui.keep_alive
                      FROM iam_user_role ur
                      JOIN iam_role role_record ON role_record.tenant_id=ur.tenant_id AND role_record.id=ur.role_id
                      JOIN iam_role_resource rr ON rr.tenant_id=ur.tenant_id AND rr.role_id=ur.role_id
                      JOIN iam_resource r ON r.id=rr.resource_id
                      JOIN iam_resource_ui ui ON ui.resource_id=r.id
                      JOIN iam_tenant_menu_config menu_config
                        ON menu_config.tenant_id=ur.tenant_id AND menu_config.resource_id=r.id
                      JOIN iam_application a ON a.id=r.application_id
                      JOIN iam_tenant_subscription subscription ON subscription.tenant_id=ur.tenant_id
                       AND subscription.status IN ('ACTIVE','SCHEDULED') AND subscription.effective_from<=UTC_TIMESTAMP(6)
                       AND subscription.effective_to>UTC_TIMESTAMP(6)
                      JOIN iam_package_resource pr ON pr.package_version_id=subscription.package_version_id
                       AND pr.resource_id=r.id
                     WHERE ur.tenant_id=? AND ur.user_id=? AND ur.status='ACTIVE'
                       AND ur.effective_from<=UTC_TIMESTAMP(6)
                       AND (ur.effective_to IS NULL OR ur.effective_to>UTC_TIMESTAMP(6))
                       AND role_record.status='ACTIVE' AND role_record.deleted_at IS NULL
                       AND rr.status='ACTIVE' AND r.status='ACTIVE' AND r.deleted_at IS NULL
                       AND a.app_code=? AND a.app_scope='TENANT' AND a.status='ACTIVE' AND a.deleted_at IS NULL
                       AND r.resource_type IN ('MENU','PAGE')
                     ORDER BY sort_order, r.resource_code
                    """, (rs, row) -> resource(rs), bin(actor.tenantId()), bin(actor.principalId()), code);
            List<ResourceView> tenantNavigation = new ArrayList<>(flat);
            tenantNavigation.addAll(navigationGroups(actor, code));
            Map<UUID, ResourceView> byId = new LinkedHashMap<>();
            tenantNavigation.forEach(resource -> byId.put(resource.id(), resource));
            flat = tenantNavigation.stream()
                    .filter(resource -> grantableThroughVisibleMenu(resource, byId))
                    .toList();
        }
        return tree(flat);
    }

    @Override
    public List<TenantView> tenants(Actor actor) {
        requirePlatform(actor);
        return jdbc.query("""
                SELECT id, tenant_code, company_name, status, policy_version, version
                  FROM iam_tenant WHERE deleted_at IS NULL ORDER BY tenant_code
                """, (rs, row) -> new TenantView(uuid(rs, "id"), rs.getString("tenant_code"),
                rs.getString("company_name"), rs.getString("status"), rs.getLong("policy_version"),
                rs.getLong("version")));
    }

    @Override
    public TenantView createTenant(Actor actor, TenantCommand command) {
        requirePlatform(actor);
        UUID id = ids.nextId();
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO iam_tenant
                    (id, tenant_code, company_name, status, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(id), normalizedCode(command.code()), required(command.companyName(), "companyName"),
                    allowed(command.status(), Set.of("ACTIVE", "SUSPENDED", "EXPIRED", "CLOSED"), "status"),
                    bin(actor.principalId()), bin(actor.principalId()));
            audit(actor, "TENANT_CREATE", "TENANT", id);
        });
        return tenantById(id);
    }

    @Override
    public TenantView updateTenant(Actor actor, UUID id, TenantCommand command) {
        requirePlatform(actor);
        transaction.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE iam_tenant SET company_name=?, status=?, policy_version=policy_version+1,
                           version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE id=? AND tenant_code=? AND version=? AND deleted_at IS NULL
                    """, required(command.companyName(), "companyName"),
                    allowed(command.status(), Set.of("ACTIVE", "SUSPENDED", "EXPIRED", "CLOSED"), "status"),
                    bin(actor.principalId()), bin(id), normalizedCode(command.code()), command.version());
            requireChanged(changed);
            audit(actor, "TENANT_UPDATE", "TENANT", id);
        });
        return tenantById(id);
    }

    @Override
    public List<SubscriptionView> subscriptions(Actor actor, UUID tenantId) {
        requirePlatform(actor);
        return jdbc.query("""
                SELECT subscription.id, subscription.tenant_id, subscription.package_version_id,
                       package_record.package_name, package_version.version_no,
                       subscription.effective_from, subscription.effective_to, subscription.user_limit,
                       CASE
                         WHEN subscription.status='TERMINATED' THEN 'TERMINATED'
                         WHEN subscription.effective_to<=UTC_TIMESTAMP(6) THEN 'EXPIRED'
                         WHEN subscription.effective_from>UTC_TIMESTAMP(6) THEN 'SCHEDULED'
                         ELSE 'ACTIVE'
                       END AS effective_status,
                       subscription.version
                  FROM iam_tenant_subscription subscription
                  JOIN iam_tenant_package_version package_version ON package_version.id=subscription.package_version_id
                  JOIN iam_tenant_package package_record ON package_record.id=package_version.package_id
                 WHERE subscription.tenant_id=? AND subscription.deleted_at IS NULL
                 ORDER BY subscription.effective_from DESC
                """, (rs, row) -> new SubscriptionView(uuid(rs, "id"), uuid(rs, "tenant_id"),
                uuid(rs, "package_version_id"), rs.getString("package_name"), rs.getInt("version_no"),
                rs.getTimestamp("effective_from").toInstant(), rs.getTimestamp("effective_to").toInstant(),
                rs.getInt("user_limit"), rs.getString("effective_status"), rs.getLong("version")), bin(tenantId));
    }

    @Override
    public void subscribeTenant(Actor actor, UUID tenantId, SubscriptionCommand command) {
        requirePlatform(actor);
        if (command.packageVersionId() == null || command.effectiveFrom() == null || command.effectiveTo() == null
                || !command.effectiveTo().isAfter(command.effectiveFrom()) || command.userLimit() < 1) {
            throw new IllegalArgumentException("Invalid subscription");
        }
        transaction.executeWithoutResult(status -> {
            if (count("SELECT COUNT(*) FROM iam_tenant WHERE id=? AND deleted_at IS NULL", bin(tenantId)) != 1) {
                throw new IllegalArgumentException("Unknown tenant");
            }
            if (count("""
                    SELECT COUNT(*) FROM iam_tenant_package_version
                     WHERE id=? AND publish_status='PUBLISHED' AND deleted_at IS NULL
                    """, bin(command.packageVersionId())) != 1) {
                throw new IllegalArgumentException("Published package version is required");
            }
            if (count("""
                    SELECT COUNT(*) FROM iam_user WHERE tenant_id=? AND status IN ('ACTIVE','LOCKED')
                     AND deleted_at IS NULL
                    """, bin(tenantId)) > command.userLimit()) {
                throw new IllegalArgumentException("User limit is lower than the tenant's current active users");
            }
            jdbc.update("""
                    UPDATE iam_tenant_subscription SET status='TERMINATED', termination_reason='REPLACED',
                           version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE tenant_id=? AND status IN ('SCHEDULED','ACTIVE') AND deleted_at IS NULL
                       AND effective_from>=?
                    """, bin(actor.principalId()), bin(tenantId), Timestamp.from(command.effectiveFrom()));
            jdbc.update("""
                    UPDATE iam_tenant_subscription
                       SET effective_to=?, status=CASE WHEN ?<=UTC_TIMESTAMP(6) THEN 'TERMINATED' ELSE status END,
                           termination_reason=CASE WHEN ?<=UTC_TIMESTAMP(6) THEN 'REPLACED' ELSE termination_reason END,
                           version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE tenant_id=? AND status IN ('SCHEDULED','ACTIVE') AND deleted_at IS NULL
                       AND effective_from<? AND effective_to>?
                    """, Timestamp.from(command.effectiveFrom()), Timestamp.from(command.effectiveFrom()),
                    Timestamp.from(command.effectiveFrom()), bin(actor.principalId()), bin(tenantId),
                    Timestamp.from(command.effectiveFrom()), Timestamp.from(command.effectiveFrom()));
            jdbc.update("""
                    INSERT INTO iam_tenant_subscription
                    (id, tenant_id, package_version_id, effective_from, effective_to, user_limit, status,
                     created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(ids.nextId()), bin(tenantId), bin(command.packageVersionId()),
                    Timestamp.from(command.effectiveFrom()), Timestamp.from(command.effectiveTo()), command.userLimit(),
                    command.effectiveFrom().isAfter(Instant.now()) ? "SCHEDULED" : "ACTIVE",
                    bin(actor.principalId()), bin(actor.principalId()));
            jdbc.update("""
                    INSERT INTO iam_role_resource
                    (tenant_id, role_id, resource_id, status, created_at, created_by, updated_at, updated_by)
                    SELECT ?, role_record.id, package_resource.resource_id, 'ACTIVE', UTC_TIMESTAMP(6), ?,
                           UTC_TIMESTAMP(6), ?
                      FROM iam_role role_record
                      JOIN iam_package_resource package_resource ON package_resource.package_version_id=?
                     WHERE role_record.tenant_id=? AND role_record.role_code='TENANT_SUPER_ADMIN'
                       AND role_record.role_type='SYSTEM' AND role_record.deleted_at IS NULL
                    ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=UTC_TIMESTAMP(6),
                                            updated_by=VALUES(updated_by)
                    """, bin(tenantId), bin(actor.principalId()), bin(actor.principalId()),
                    bin(command.packageVersionId()), bin(tenantId));
            jdbc.update("""
                    INSERT INTO iam_tenant_menu_config
                    (tenant_id, resource_id, visible, created_at, created_by, updated_at, updated_by)
                    SELECT ?, resource_record.id, 0, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?
                      FROM iam_package_resource package_resource
                      JOIN iam_resource resource_record ON resource_record.id=package_resource.resource_id
                       AND resource_record.resource_type IN ('MENU','PAGE')
                       AND resource_record.status='ACTIVE' AND resource_record.deleted_at IS NULL
                      JOIN iam_resource_ui resource_ui ON resource_ui.resource_id=resource_record.id
                     WHERE package_resource.package_version_id=?
                    ON DUPLICATE KEY UPDATE updated_at=iam_tenant_menu_config.updated_at
                    """, bin(tenantId), bin(actor.principalId()), bin(actor.principalId()),
                    bin(command.packageVersionId()));
            bumpTenantPolicy(tenantId);
            audit(actor, "TENANT_SUBSCRIBE", "TENANT", tenantId);
        });
    }

    @Override
    public List<PackageView> packages(Actor actor) {
        requirePlatform(actor);
        return jdbc.query("""
                SELECT id, package_code, package_name, description, status, version
                  FROM iam_tenant_package WHERE deleted_at IS NULL ORDER BY package_code
                """, (rs, row) -> packageView(rs));
    }

    @Override
    public PackageView createPackage(Actor actor, PackageCommand command) {
        requirePlatform(actor);
        UUID id = ids.nextId();
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO iam_tenant_package
                    (id, package_code, package_name, description, status, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(id), normalizedCode(command.code()), required(command.name(), "name"),
                    blankToNull(command.description()), allowed(command.status(), APPLICATION_STATUSES, "status"),
                    bin(actor.principalId()), bin(actor.principalId()));
            audit(actor, "PACKAGE_CREATE", "PACKAGE", id);
        });
        return packageById(id);
    }

    @Override
    public PackageView updatePackage(Actor actor, UUID id, PackageCommand command) {
        requirePlatform(actor);
        transaction.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE iam_tenant_package SET package_name=?, description=?, status=?, version=version+1,
                           updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE id=? AND package_code=? AND version=? AND deleted_at IS NULL
                    """, required(command.name(), "name"), blankToNull(command.description()),
                    allowed(command.status(), APPLICATION_STATUSES, "status"), bin(actor.principalId()), bin(id),
                    normalizedCode(command.code()), command.version());
            requireChanged(changed);
            audit(actor, "PACKAGE_UPDATE", "PACKAGE", id);
        });
        return packageById(id);
    }

    @Override
    public List<PackageVersionView> packageVersions(Actor actor, UUID packageId) {
        requirePlatform(actor);
        return jdbc.query("""
                SELECT id, package_id, version_no, publish_status, default_user_limit, change_note, version
                  FROM iam_tenant_package_version WHERE package_id=? AND deleted_at IS NULL ORDER BY version_no DESC
                """, (rs, row) -> packageVersion(rs), bin(packageId));
    }

    @Override
    public PackageVersionView createPackageVersion(Actor actor, UUID packageId, PackageVersionCommand command) {
        requirePlatform(actor);
        if (command.versionNo() < 1 || command.defaultUserLimit() < 1) {
            throw new IllegalArgumentException("Invalid package version");
        }
        UUID id = ids.nextId();
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO iam_tenant_package_version
                    (id, package_id, version_no, publish_status, default_user_limit, change_note,
                     created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, 'DRAFT', ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(id), bin(packageId), command.versionNo(), command.defaultUserLimit(),
                    blankToNull(command.changeNote()), bin(actor.principalId()), bin(actor.principalId()));
            for (UUID resourceId : command.resourceIds()) {
                if (count("SELECT COUNT(*) FROM iam_resource WHERE id=? AND deleted_at IS NULL", bin(resourceId)) != 1) {
                    throw new IllegalArgumentException("Unknown package resource");
                }
                if (count("""
                        SELECT COUNT(*) FROM iam_resource r JOIN iam_application a ON a.id=r.application_id
                         WHERE r.id=? AND a.app_scope='TENANT' AND r.deleted_at IS NULL AND a.deleted_at IS NULL
                        """, bin(resourceId)) != 1) {
                    throw new IllegalArgumentException("Tenant package cannot contain platform resources");
                }
                jdbc.update("""
                        INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
                        VALUES (?, ?, UTC_TIMESTAMP(6), ?)
                        """, bin(id), bin(resourceId), bin(actor.principalId()));
            }
            audit(actor, "PACKAGE_VERSION_CREATE", "PACKAGE_VERSION", id);
        });
        return packageVersionById(id);
    }

    @Override
    public PackageVersionView publishPackageVersion(Actor actor, UUID id) {
        requirePlatform(actor);
        transaction.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE iam_tenant_package_version SET publish_status='PUBLISHED', published_at=UTC_TIMESTAMP(6),
                           published_by=?, version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE id=? AND publish_status='DRAFT' AND deleted_at IS NULL
                    """, bin(actor.principalId()), bin(actor.principalId()), bin(id));
            requireChanged(changed);
            audit(actor, "PACKAGE_VERSION_PUBLISH", "PACKAGE_VERSION", id);
        });
        return packageVersionById(id);
    }

    @Override
    public List<OrganizationView> organizations(Actor actor) {
        requireTenantPermission(actor, "iam:organization:read");
        return jdbc.query("""
                SELECT id, parent_id, org_code, org_name, org_type, path, sort_order, status, version
                  FROM iam_organization WHERE tenant_id=? AND deleted_at IS NULL ORDER BY path, sort_order, org_code
                """, (rs, row) -> organization(rs), bin(actor.tenantId()));
    }

    @Override
    public OrganizationView createOrganization(Actor actor, OrganizationCommand command) {
        requireTenantPermission(actor, "iam:organization:write");
        validateOrganization(command);
        UUID id = ids.nextId();
        transaction.executeWithoutResult(status -> {
            String parentPath = parentPath(actor.tenantId(), command.parentId());
            String path = parentPath + "/" + id;
            jdbc.update("""
                    INSERT INTO iam_organization
                    (id, tenant_id, parent_id, org_code, org_name, org_type, path, sort_order, status,
                     created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(id), bin(actor.tenantId()), bin(command.parentId()), normalizedCode(command.code()),
                    required(command.name(), "name"), allowed(command.type(), ORG_TYPES, "type"), path,
                    command.sortOrder(), allowed(command.status(), RESOURCE_STATUSES, "status"),
                    bin(actor.principalId()), bin(actor.principalId()));
            audit(actor, "ORGANIZATION_CREATE", "ORGANIZATION", id);
        });
        return organizationById(actor.tenantId(), id);
    }

    @Override
    public OrganizationView updateOrganization(Actor actor, UUID id, OrganizationCommand command) {
        requireTenantPermission(actor, "iam:organization:write");
        validateOrganization(command);
        transaction.executeWithoutResult(status -> {
            if (count("SELECT COUNT(*) FROM iam_organization WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
                    bin(actor.tenantId()), bin(id)) != 1) {
                throw new AccessDeniedException("Organization is outside the current tenant");
            }
            ensureNoOrganizationCycle(actor.tenantId(), id, command.parentId());
            String oldPath = jdbc.queryForObject("SELECT path FROM iam_organization WHERE tenant_id=? AND id=?",
                    String.class, bin(actor.tenantId()), bin(id));
            String newPath = parentPath(actor.tenantId(), command.parentId()) + "/" + id;
            int changed = jdbc.update("""
                    UPDATE iam_organization
                       SET parent_id=?, org_name=?, org_type=?, path=?, sort_order=?, status=?,
                           version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE tenant_id=? AND id=? AND org_code=? AND version=? AND deleted_at IS NULL
                    """, bin(command.parentId()), required(command.name(), "name"),
                    allowed(command.type(), ORG_TYPES, "type"), newPath, command.sortOrder(),
                    allowed(command.status(), RESOURCE_STATUSES, "status"), bin(actor.principalId()),
                    bin(actor.tenantId()), bin(id), normalizedCode(command.code()), command.version());
            requireChanged(changed);
            if (!oldPath.equals(newPath)) {
                jdbc.update("""
                        UPDATE iam_organization SET path=CONCAT(?, SUBSTRING(path, ?)),
                               version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                         WHERE tenant_id=? AND path LIKE CONCAT(?, '/%') AND deleted_at IS NULL
                        """, newPath, oldPath.length() + 1, bin(actor.principalId()), bin(actor.tenantId()), oldPath);
            }
            audit(actor, "ORGANIZATION_UPDATE", "ORGANIZATION", id);
        });
        return organizationById(actor.tenantId(), id);
    }

    @Override
    public List<UserView> users(Actor actor) {
        requireTenantPermission(actor, "iam:user:read");
        return jdbc.query("""
                SELECT id, username, display_name, status, security_version, version
                  FROM iam_user WHERE tenant_id=? AND deleted_at IS NULL ORDER BY username
                """, (rs, row) -> user(rs, actor.tenantId()), bin(actor.tenantId()));
    }

    @Override
    public UserView createUser(Actor actor, UserCommand command) {
        requireTenantPermission(actor, "iam:user:write");
        requireTenantPermission(actor, "iam:user:assign-role");
        requireTenantPermission(actor, "iam:user:reset-password");
        validateUser(command, true);
        UUID id = ids.nextId();
        UUID credentialId = ids.nextId();
        String hash = passwordHasher.hash(command.initialPassword());
        transaction.executeWithoutResult(status -> {
            requireUserCapacity(actor.tenantId(), null, command.status());
            jdbc.update("""
                    INSERT INTO iam_user
                    (id, tenant_id, username, display_name, status, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(id), bin(actor.tenantId()), normalizedUsername(command.username()),
                    required(command.displayName(), "displayName"), allowed(command.status(), USER_STATUSES, "status"),
                    bin(actor.principalId()), bin(actor.principalId()));
            jdbc.update("""
                    INSERT INTO iam_user_credential
                    (id, tenant_id, user_id, credential_type, password_hash, algorithm, algorithm_version,
                     password_changed_at, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'PASSWORD', ?, 'ARGON2ID', 1, UTC_TIMESTAMP(6), 'ACTIVE',
                            UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, bin(credentialId), bin(actor.tenantId()), bin(id), hash);
            assignUserRoles(actor, id, command.roleIds());
            assignUserOrganizations(actor, id, command.organizationIds());
            audit(actor, "USER_CREATE", "USER", id);
        });
        return userById(actor.tenantId(), id);
    }

    @Override
    public UserView updateUser(Actor actor, UUID id, UserCommand command) {
        requireTenantPermission(actor, "iam:user:write");
        requireTenantPermission(actor, "iam:user:assign-role");
        validateUser(command, false);
        transaction.executeWithoutResult(status -> {
            requireUserCapacity(actor.tenantId(), id, command.status());
            preserveLastTenantAdministrator(actor.tenantId(), id, command.status(), command.roleIds());
            int changed = jdbc.update("""
                    UPDATE iam_user SET display_name=?, status=?, security_version=security_version+1,
                           version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE tenant_id=? AND id=? AND username=? AND version=? AND deleted_at IS NULL
                    """, required(command.displayName(), "displayName"), allowed(command.status(), USER_STATUSES, "status"),
                    bin(actor.principalId()), bin(actor.tenantId()), bin(id), normalizedUsername(command.username()),
                    command.version());
            requireChanged(changed);
            assignUserRoles(actor, id, command.roleIds());
            assignUserOrganizations(actor, id, command.organizationIds());
            audit(actor, "USER_UPDATE", "USER", id);
        });
        return userById(actor.tenantId(), id);
    }

    @Override
    public void resetUserPassword(Actor actor, UUID id, PasswordResetCommand command) {
        requireTenantPermission(actor, "iam:user:reset-password");
        String password = command == null ? null : command.newPassword();
        if (password == null || password.length() < 14 || password.length() > 128) {
            throw new IllegalArgumentException("New password must contain between 14 and 128 characters");
        }
        String hash = passwordHasher.hash(password);
        transaction.executeWithoutResult(status -> {
            if (count("SELECT COUNT(*) FROM iam_user WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
                    bin(actor.tenantId()), bin(id)) != 1) throw new AccessDeniedException("User is outside the current tenant");
            requireChanged(jdbc.update("""
                    UPDATE iam_user_credential
                       SET password_hash=?, algorithm='ARGON2ID', algorithm_version=1, failed_attempts=0,
                           last_failed_at=NULL, locked_until=NULL, password_changed_at=UTC_TIMESTAMP(6),
                           status='ACTIVE', version=version+1, updated_at=UTC_TIMESTAMP(6)
                     WHERE tenant_id=? AND user_id=? AND credential_type='PASSWORD'
                    """, hash, bin(actor.tenantId()), bin(id)));
            jdbc.update("""
                    UPDATE iam_refresh_token refresh_token
                    JOIN iam_auth_session auth_session ON auth_session.id=refresh_token.session_id
                       SET refresh_token.revoked_at=COALESCE(refresh_token.revoked_at, UTC_TIMESTAMP(6)),
                           refresh_token.revoke_reason=COALESCE(refresh_token.revoke_reason, 'PASSWORD_RESET')
                     WHERE auth_session.principal_scope='TENANT' AND auth_session.tenant_id=?
                       AND auth_session.principal_id=? AND refresh_token.revoked_at IS NULL
                    """, bin(actor.tenantId()), bin(id));
            jdbc.update("""
                    UPDATE iam_oauth_authorization authorization_record
                    JOIN iam_auth_session auth_session ON auth_session.id=authorization_record.session_id
                       SET authorization_record.status='REVOKED',
                           authorization_record.revoked_at=COALESCE(authorization_record.revoked_at, UTC_TIMESTAMP(6)),
                           authorization_record.revoke_reason='PASSWORD_RESET',
                           authorization_record.version=authorization_record.version+1,
                           authorization_record.updated_at=UTC_TIMESTAMP(6)
                     WHERE auth_session.principal_scope='TENANT' AND auth_session.tenant_id=?
                       AND auth_session.principal_id=? AND authorization_record.status<>'REVOKED'
                    """, bin(actor.tenantId()), bin(id));
            jdbc.update("""
                    UPDATE iam_auth_session SET status='REVOKED', revoked_at=UTC_TIMESTAMP(6),
                           revoke_reason='PASSWORD_RESET', version=version+1
                     WHERE principal_scope='TENANT' AND tenant_id=? AND principal_id=? AND status='ACTIVE'
                    """, bin(actor.tenantId()), bin(id));
            jdbc.update("""
                    UPDATE iam_user SET security_version=security_version+1, version=version+1,
                           updated_at=UTC_TIMESTAMP(6), updated_by=? WHERE tenant_id=? AND id=?
                    """, bin(actor.principalId()), bin(actor.tenantId()), bin(id));
            audit(actor, "USER_PASSWORD_RESET", "USER", id);
        });
    }

    @Override
    public List<RoleView> roles(Actor actor) {
        requireTenantPermission(actor, "iam:role:read");
        return jdbc.query("""
                SELECT id, role_code, role_name, role_type, status, version
                  FROM iam_role WHERE tenant_id=? AND deleted_at IS NULL ORDER BY role_code
                """, (rs, row) -> role(rs, actor.tenantId()), bin(actor.tenantId()));
    }

    @Override
    public List<ResourceView> grantableResources(Actor actor) {
        requireTenantPermission(actor, "iam:role:read");
        List<ResourceView> entitled = jdbc.query("""
                SELECT DISTINCT r.id, r.application_id, r.parent_id, r.resource_code, r.resource_type,
                       r.permission_code, r.display_name, r.sort_order, r.status, r.version,
                       ui.route_key, ui.route_path, ui.icon_key,
                       CASE WHEN r.resource_type IN ('MENU','PAGE')
                            THEN COALESCE(ui.visible, 0) * COALESCE(menu_config.visible, 0)
                            ELSE 1 END visible,
                       COALESCE(ui.keep_alive, 0) keep_alive
                  FROM iam_tenant_subscription subscription
                  JOIN iam_package_resource pr ON pr.package_version_id=subscription.package_version_id
                  JOIN iam_resource r ON r.id=pr.resource_id
                  LEFT JOIN iam_resource_ui ui ON ui.resource_id=r.id
                  LEFT JOIN iam_tenant_menu_config menu_config
                    ON menu_config.tenant_id=subscription.tenant_id AND menu_config.resource_id=r.id
                 WHERE subscription.tenant_id=? AND subscription.status IN ('ACTIVE','SCHEDULED')
                   AND subscription.effective_from<=UTC_TIMESTAMP(6) AND subscription.effective_to>UTC_TIMESTAMP(6)
                   AND r.status='ACTIVE' AND r.deleted_at IS NULL
                 ORDER BY r.application_id, r.sort_order, r.resource_code
                """, (rs, row) -> resource(rs), bin(actor.tenantId()));
        Map<UUID, ResourceView> byId = new LinkedHashMap<>();
        entitled.forEach(resource -> byId.put(resource.id(), resource));
        return entitled.stream().filter(resource -> grantableThroughVisibleMenu(resource, byId)).toList();
    }

    @Override
    public List<TenantMenuView> tenantMenus(Actor actor) {
        requireTenantPermission(actor, "iam:menu:read");
        return jdbc.query("""
                SELECT DISTINCT r.id resource_id, r.application_id, a.app_code, a.app_name,
                       a.sort_order application_sort_order,
                       r.parent_id, r.resource_code, r.resource_type,
                       r.display_name original_display_name,
                       COALESCE(menu_config.display_name_override, r.display_name) display_name,
                       r.sort_order original_sort_order,
                       COALESCE(menu_config.sort_order_override, r.sort_order) sort_order,
                       ui.icon_key original_icon_key,
                       COALESCE(menu_config.icon_key_override, ui.icon_key) icon_key,
                       ui.visible platform_visible, COALESCE(menu_config.visible, 0) visible,
                       menu_config.parent_group_id,
                       CASE WHEN menu_config.resource_id IS NULL THEN 0 ELSE 1 END configured,
                       COALESCE(menu_config.version, 0) version
                  FROM iam_tenant_subscription subscription
                  JOIN iam_package_resource package_resource
                    ON package_resource.package_version_id=subscription.package_version_id
                  JOIN iam_resource r ON r.id=package_resource.resource_id
                   AND r.resource_type IN ('MENU','PAGE')
                  JOIN iam_resource_ui ui ON ui.resource_id=r.id
                  JOIN iam_application a ON a.id=r.application_id
                   AND a.app_scope='TENANT' AND a.status='ACTIVE' AND a.deleted_at IS NULL
                  LEFT JOIN iam_tenant_menu_config menu_config
                    ON menu_config.tenant_id=subscription.tenant_id AND menu_config.resource_id=r.id
                 WHERE subscription.tenant_id=? AND subscription.status IN ('ACTIVE','SCHEDULED')
                   AND subscription.effective_from<=UTC_TIMESTAMP(6)
                   AND subscription.effective_to>UTC_TIMESTAMP(6)
                   AND r.status='ACTIVE' AND r.deleted_at IS NULL
                 ORDER BY application_sort_order, r.sort_order, r.resource_code
                """, (rs, row) -> tenantMenu(rs), bin(actor.tenantId()));
    }

    @Override
    public TenantMenuView saveTenantMenu(Actor actor, UUID resourceId, TenantMenuCommand command) {
        requireTenantPermission(actor, "iam:menu:write");
        if (resourceId == null || command == null) throw new IllegalArgumentException("Menu configuration is required");
        requireEntitledNavigableResource(actor.tenantId(), resourceId);
        if (command.visible() && count("""
                SELECT COUNT(*) FROM iam_resource_ui WHERE resource_id=? AND visible=1
                """, bin(resourceId)) != 1) {
            throw new IllegalArgumentException("Platform-disabled menu cannot be enabled by a tenant");
        }
        validateMenuGroupForResource(actor.tenantId(), resourceId, command.parentGroupId());
        String displayName = optionalText(command.displayNameOverride(), "displayNameOverride", 128);
        String iconKey = optionalText(command.iconKeyOverride(), "iconKeyOverride", 128);
        transaction.executeWithoutResult(status -> {
            int existing = count("SELECT COUNT(*) FROM iam_tenant_menu_config WHERE tenant_id=? AND resource_id=?",
                    bin(actor.tenantId()), bin(resourceId));
            if (existing == 0) {
                if (command.version() != 0) throw new IllegalStateException("Menu configuration version is stale");
                jdbc.update("""
                        INSERT INTO iam_tenant_menu_config
                        (tenant_id, resource_id, display_name_override, icon_key_override,
                         sort_order_override, parent_group_id, visible, created_at, created_by, updated_at, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                        """, bin(actor.tenantId()), bin(resourceId), displayName, iconKey,
                        command.sortOrderOverride(), bin(command.parentGroupId()), command.visible(),
                        bin(actor.principalId()), bin(actor.principalId()));
            } else {
                int changed = jdbc.update("""
                        UPDATE iam_tenant_menu_config
                           SET display_name_override=?, icon_key_override=?, sort_order_override=?,
                               parent_group_id=?, visible=?, version=version+1,
                               updated_at=UTC_TIMESTAMP(6), updated_by=?
                         WHERE tenant_id=? AND resource_id=? AND version=?
                        """, displayName, iconKey, command.sortOrderOverride(), bin(command.parentGroupId()),
                        command.visible(), bin(actor.principalId()), bin(actor.tenantId()), bin(resourceId),
                        command.version());
                requireChanged(changed);
            }
            bumpTenantPolicy(actor.tenantId());
            audit(actor, "TENANT_MENU_SAVE", "RESOURCE", resourceId);
        });
        return tenantMenuById(actor.tenantId(), resourceId);
    }

    @Override
    public List<TenantMenuGroupView> tenantMenuGroups(Actor actor) {
        requireTenantPermission(actor, "iam:menu:read");
        return jdbc.query("""
                SELECT menu_group.id, menu_group.application_id, application_record.app_code,
                       application_record.app_name, menu_group.parent_id, menu_group.group_code,
                       menu_group.display_name, menu_group.icon_key, menu_group.sort_order,
                       menu_group.visible, menu_group.status, menu_group.version
                  FROM iam_tenant_menu_group menu_group
                  JOIN iam_application application_record ON application_record.id=menu_group.application_id
                 WHERE menu_group.tenant_id=? AND menu_group.deleted_at IS NULL
                 ORDER BY application_record.sort_order, menu_group.sort_order, menu_group.group_code
                """, (rs, row) -> tenantMenuGroup(rs), bin(actor.tenantId()));
    }

    @Override
    public TenantMenuGroupView createTenantMenuGroup(Actor actor, TenantMenuGroupCommand command) {
        requireTenantPermission(actor, "iam:menu:write");
        validateTenantMenuGroup(command);
        requireSubscribedApplication(actor.tenantId(), command.applicationId());
        validateMenuGroupParent(actor.tenantId(), command.applicationId(), command.parentId(), null);
        UUID id = ids.nextId();
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO iam_tenant_menu_group
                    (id, tenant_id, application_id, parent_id, group_code, display_name, icon_key,
                     sort_order, visible, status, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(id), bin(actor.tenantId()), bin(command.applicationId()), bin(command.parentId()),
                    normalizedCode(command.code()), required(command.displayName(), "displayName"),
                    optionalText(command.iconKey(), "iconKey", 128), command.sortOrder(), command.visible(),
                    allowed(command.status(), RESOURCE_STATUSES, "status"), bin(actor.principalId()),
                    bin(actor.principalId()));
            bumpTenantPolicy(actor.tenantId());
            audit(actor, "TENANT_MENU_GROUP_CREATE", "TENANT_MENU_GROUP", id);
        });
        return tenantMenuGroupById(actor.tenantId(), id);
    }

    @Override
    public TenantMenuGroupView updateTenantMenuGroup(Actor actor, UUID id, TenantMenuGroupCommand command) {
        requireTenantPermission(actor, "iam:menu:write");
        validateTenantMenuGroup(command);
        validateMenuGroupParent(actor.tenantId(), command.applicationId(), command.parentId(), id);
        transaction.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE iam_tenant_menu_group
                       SET parent_id=?, display_name=?, icon_key=?, sort_order=?, visible=?, status=?,
                           version=version+1, updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE tenant_id=? AND id=? AND application_id=? AND group_code=?
                       AND version=? AND deleted_at IS NULL
                    """, bin(command.parentId()), required(command.displayName(), "displayName"),
                    optionalText(command.iconKey(), "iconKey", 128), command.sortOrder(), command.visible(),
                    allowed(command.status(), RESOURCE_STATUSES, "status"), bin(actor.principalId()),
                    bin(actor.tenantId()), bin(id), bin(command.applicationId()), normalizedCode(command.code()),
                    command.version());
            requireChanged(changed);
            bumpTenantPolicy(actor.tenantId());
            audit(actor, "TENANT_MENU_GROUP_UPDATE", "TENANT_MENU_GROUP", id);
        });
        return tenantMenuGroupById(actor.tenantId(), id);
    }

    @Override
    public RoleView createRole(Actor actor, RoleCommand command) {
        requireTenantPermission(actor, "iam:role:write");
        requireTenantPermission(actor, "iam:role:grant");
        validateRole(command);
        if (!"CUSTOM".equalsIgnoreCase(command.type())) {
            throw new AccessDeniedException("System roles can only be created by IAM bootstrap or migrations");
        }
        UUID id = ids.nextId();
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO iam_role
                    (id, tenant_id, role_code, role_name, role_type, status, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(id), bin(actor.tenantId()), normalizedCode(command.code()), required(command.name(), "name"),
                    allowed(command.type(), ROLE_TYPES, "type"), allowed(command.status(), ROLE_STATUSES, "status"),
                    bin(actor.principalId()), bin(actor.principalId()));
            assignRoleResources(actor, id, command.resourceIds());
            bumpTenantPolicy(actor.tenantId());
            audit(actor, "ROLE_CREATE", "ROLE", id);
        });
        return roleById(actor.tenantId(), id);
    }

    @Override
    public RoleView updateRole(Actor actor, UUID id, RoleCommand command) {
        requireTenantPermission(actor, "iam:role:write");
        requireTenantPermission(actor, "iam:role:grant");
        validateRole(command);
        requireCustomRole(actor.tenantId(), id);
        transaction.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE iam_role SET role_name=?, status=?, version=version+1,
                           updated_at=UTC_TIMESTAMP(6), updated_by=?
                     WHERE tenant_id=? AND id=? AND role_code=? AND role_type=? AND version=? AND deleted_at IS NULL
                    """, required(command.name(), "name"), allowed(command.status(), ROLE_STATUSES, "status"),
                    bin(actor.principalId()), bin(actor.tenantId()), bin(id), normalizedCode(command.code()),
                    allowed(command.type(), ROLE_TYPES, "type"), command.version());
            requireChanged(changed);
            assignRoleResources(actor, id, command.resourceIds());
            bumpTenantPolicy(actor.tenantId());
            audit(actor, "ROLE_UPDATE", "ROLE", id);
        });
        return roleById(actor.tenantId(), id);
    }

    @Override
    public List<DataScopeView> dataScopes(Actor actor) {
        requireTenantPermission(actor, "iam:data-scope:read");
        return jdbc.query("""
                SELECT id, role_id, application_id, scope_key, scope_type, status, version
                  FROM iam_data_scope_policy WHERE tenant_id=? AND deleted_at IS NULL ORDER BY scope_key
                """, (rs, row) -> dataScope(rs), bin(actor.tenantId()));
    }

    @Override
    public DataScopeView saveDataScope(Actor actor, UUID id, DataScopeCommand command) {
        requireTenantPermission(actor, "iam:data-scope:write");
        UUID targetId = id == null ? ids.nextId() : id;
        transaction.executeWithoutResult(status -> {
            requireRoleOwned(actor.tenantId(), command.roleId());
            requireSubscribedApplication(actor.tenantId(), command.applicationId());
            if (id == null) {
                jdbc.update("""
                        INSERT INTO iam_data_scope_policy
                        (id, tenant_id, role_id, application_id, scope_key, scope_type, status,
                         created_at, created_by, updated_at, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                        """, bin(targetId), bin(actor.tenantId()), bin(command.roleId()), bin(command.applicationId()),
                        normalizedCode(command.scopeKey()), allowed(command.scopeType(), DATA_SCOPE_TYPES, "scopeType"),
                        allowed(command.status(), Set.of("ACTIVE", "INACTIVE"), "status"),
                        bin(actor.principalId()), bin(actor.principalId()));
            } else {
                int changed = jdbc.update("""
                        UPDATE iam_data_scope_policy SET scope_type=?, status=?, version=version+1,
                               updated_at=UTC_TIMESTAMP(6), updated_by=?
                         WHERE tenant_id=? AND id=? AND role_id=? AND application_id=? AND scope_key=?
                           AND version=? AND deleted_at IS NULL
                        """, allowed(command.scopeType(), DATA_SCOPE_TYPES, "scopeType"),
                        allowed(command.status(), Set.of("ACTIVE", "INACTIVE"), "status"), bin(actor.principalId()),
                        bin(actor.tenantId()), bin(id), bin(command.roleId()), bin(command.applicationId()),
                        normalizedCode(command.scopeKey()), command.version());
                requireChanged(changed);
            }
            bumpTenantPolicy(actor.tenantId());
            audit(actor, id == null ? "DATA_SCOPE_CREATE" : "DATA_SCOPE_UPDATE", "DATA_SCOPE", targetId);
        });
        return dataScopeById(actor.tenantId(), targetId);
    }

    @Override
    public List<SettingView> settings(Actor actor) {
        requireTenantPermission(actor, "iam:setting:read");
        return jdbc.query("""
                SELECT setting_key, CAST(value_json AS CHAR) value_json, version
                  FROM iam_tenant_setting WHERE tenant_id=? ORDER BY setting_key
                """, (rs, row) -> new SettingView(rs.getString("setting_key"), rs.getString("value_json"),
                rs.getLong("version")), bin(actor.tenantId()));
    }

    @Override
    public SettingView saveSetting(Actor actor, String key, SettingCommand command) {
        requireTenantPermission(actor, "iam:setting:write");
        String normalizedKey = normalizedCode(key);
        String json = required(command.valueJson(), "valueJson");
        transaction.executeWithoutResult(status -> {
            if (command.version() == 0 && count("SELECT COUNT(*) FROM iam_tenant_setting WHERE tenant_id=? AND setting_key=?",
                    bin(actor.tenantId()), normalizedKey) == 0) {
                jdbc.update("""
                        INSERT INTO iam_tenant_setting
                        (tenant_id, setting_key, value_json, created_at, created_by, updated_at, updated_by)
                        VALUES (?, ?, CAST(? AS JSON), UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                        """, bin(actor.tenantId()), normalizedKey, json, bin(actor.principalId()), bin(actor.principalId()));
            } else {
                int changed = jdbc.update("""
                        UPDATE iam_tenant_setting SET value_json=CAST(? AS JSON), version=version+1,
                               updated_at=UTC_TIMESTAMP(6), updated_by=?
                         WHERE tenant_id=? AND setting_key=? AND version=?
                        """, json, bin(actor.principalId()), bin(actor.tenantId()), normalizedKey, command.version());
                requireChanged(changed);
            }
            bumpTenantPolicy(actor.tenantId());
            audit(actor, "SETTING_SAVE", "SETTING", null);
        });
        return jdbc.queryForObject("""
                SELECT setting_key, CAST(value_json AS CHAR) value_json, version
                  FROM iam_tenant_setting WHERE tenant_id=? AND setting_key=?
                """, (rs, row) -> new SettingView(rs.getString("setting_key"), rs.getString("value_json"),
                rs.getLong("version")), bin(actor.tenantId()), normalizedKey);
    }

    @Override
    public List<DictionaryTypeView> dictionaryTypes(Actor actor) {
        if ("PLATFORM".equals(actor.scope())) {
            requirePlatform(actor);
            return jdbc.query("""
                    SELECT id, tenant_id, type_code, type_name, description, status, version
                      FROM iam_dictionary_type
                     WHERE owner_scope='PLATFORM' AND deleted_at IS NULL
                     ORDER BY type_code
                    """, (rs, row) -> dictionaryType(rs));
        }
        requireTenantPermission(actor, "iam:dictionary:read");
        return jdbc.query("""
                SELECT id, tenant_id, type_code, type_name, description, status, version
                  FROM iam_dictionary_type
                 WHERE owner_scope='TENANT' AND tenant_id=? AND deleted_at IS NULL
                 ORDER BY type_code
                """, (rs, row) -> dictionaryType(rs), bin(actor.tenantId()));
    }

    @Override
    public DictionaryTypeView createDictionaryType(Actor actor, DictionaryTypeCommand command) {
        validateDictionaryType(command);
        UUID id = ids.nextId();
        if ("PLATFORM".equals(actor.scope())) {
            requirePlatform(actor);
            transaction.executeWithoutResult(status -> {
                jdbc.update("""
                        INSERT INTO iam_dictionary_type
                        (id, owner_scope, owner_key, tenant_id, type_code, type_name, description, status,
                         created_at, created_by, updated_at, updated_by)
                        VALUES (?, 'PLATFORM', UUID_TO_BIN('00000000-0000-0000-0000-000000000000'), NULL,
                                ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                        """, bin(id), normalizedCode(command.code()), required(command.name(), "name"),
                        blankToNull(command.description()),
                        allowed(command.status(), RESOURCE_STATUSES, "status"),
                        bin(actor.principalId()), bin(actor.principalId()));
                bumpAllTenantPolicies();
                audit(actor, "DICTIONARY_TYPE_CREATE", "DICTIONARY_TYPE", id);
            });
        } else {
            requireTenantPermission(actor, "iam:dictionary:write");
            transaction.executeWithoutResult(status -> {
                jdbc.update("""
                        INSERT INTO iam_dictionary_type
                        (id, owner_scope, owner_key, tenant_id, type_code, type_name, description, status,
                         created_at, created_by, updated_at, updated_by)
                        VALUES (?, 'TENANT', ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                        """, bin(id), bin(actor.tenantId()), bin(actor.tenantId()),
                        normalizedCode(command.code()), required(command.name(), "name"),
                        blankToNull(command.description()),
                        allowed(command.status(), RESOURCE_STATUSES, "status"),
                        bin(actor.principalId()), bin(actor.principalId()));
                bumpTenantPolicy(actor.tenantId());
                audit(actor, "DICTIONARY_TYPE_CREATE", "DICTIONARY_TYPE", id);
            });
        }
        return dictionaryTypeById(actor, id);
    }

    @Override
    public DictionaryTypeView updateDictionaryType(Actor actor, UUID id, DictionaryTypeCommand command) {
        validateDictionaryType(command);
        requireDictionaryTypeAccess(actor, id, true);
        transaction.executeWithoutResult(status -> {
            int changed;
            if ("PLATFORM".equals(actor.scope())) {
                changed = jdbc.update("""
                        UPDATE iam_dictionary_type
                           SET type_name=?, description=?, status=?, version=version+1,
                               updated_at=UTC_TIMESTAMP(6), updated_by=?
                         WHERE id=? AND owner_scope='PLATFORM' AND tenant_id IS NULL
                           AND type_code=? AND version=? AND deleted_at IS NULL
                        """, required(command.name(), "name"), blankToNull(command.description()),
                        allowed(command.status(), RESOURCE_STATUSES, "status"), bin(actor.principalId()),
                        bin(id), normalizedCode(command.code()), command.version());
            } else {
                changed = jdbc.update("""
                        UPDATE iam_dictionary_type
                           SET type_name=?, description=?, status=?, version=version+1,
                               updated_at=UTC_TIMESTAMP(6), updated_by=?
                         WHERE id=? AND owner_scope='TENANT' AND tenant_id=?
                           AND type_code=? AND version=? AND deleted_at IS NULL
                        """, required(command.name(), "name"), blankToNull(command.description()),
                        allowed(command.status(), RESOURCE_STATUSES, "status"), bin(actor.principalId()),
                        bin(id), bin(actor.tenantId()), normalizedCode(command.code()), command.version());
            }
            requireChanged(changed);
            if ("PLATFORM".equals(actor.scope())) bumpAllTenantPolicies();
            else bumpTenantPolicy(actor.tenantId());
            audit(actor, "DICTIONARY_TYPE_UPDATE", "DICTIONARY_TYPE", id);
        });
        return dictionaryTypeById(actor, id);
    }

    @Override
    public List<DictionaryItemView> dictionaryItems(Actor actor, UUID typeId) {
        requireDictionaryTypeAccess(actor, typeId, false);
        return jdbc.query("""
                SELECT id, type_id, tenant_id, item_code, item_label, item_value, sort_order, status, version
                  FROM iam_dictionary_item
                 WHERE type_id=? AND deleted_at IS NULL
                 ORDER BY sort_order, item_code
                """, (rs, row) -> dictionaryItem(rs), bin(typeId));
    }

    @Override
    public DictionaryItemView createDictionaryItem(Actor actor, DictionaryItemCommand command) {
        validateDictionaryItem(command);
        UUID tenantId = requireDictionaryTypeAccess(actor, command.typeId(), true);
        UUID id = ids.nextId();
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO iam_dictionary_item
                    (id, type_id, tenant_id, item_code, item_label, item_value, sort_order, status,
                     created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    """, bin(id), bin(command.typeId()), bin(tenantId), normalizedCode(command.code()),
                    required(command.label(), "label"), blankToNull(command.value()), command.sortOrder(),
                    allowed(command.status(), RESOURCE_STATUSES, "status"),
                    bin(actor.principalId()), bin(actor.principalId()));
            if ("PLATFORM".equals(actor.scope())) bumpAllTenantPolicies();
            else bumpTenantPolicy(actor.tenantId());
            audit(actor, "DICTIONARY_ITEM_CREATE", "DICTIONARY_ITEM", id);
        });
        return dictionaryItemById(actor, id);
    }

    @Override
    public DictionaryItemView updateDictionaryItem(Actor actor, UUID id, DictionaryItemCommand command) {
        validateDictionaryItem(command);
        UUID tenantId = requireDictionaryTypeAccess(actor, command.typeId(), true);
        transaction.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    UPDATE iam_dictionary_item item
                    JOIN iam_dictionary_type type_record ON type_record.id=item.type_id
                       SET item.type_id=?, item.tenant_id=?, item.item_label=?, item.item_value=?,
                           item.sort_order=?, item.status=?, item.version=item.version+1,
                           item.updated_at=UTC_TIMESTAMP(6), item.updated_by=?
                     WHERE item.id=? AND type_record.id=? AND item.item_code=? AND item.version=?
                       AND item.deleted_at IS NULL AND type_record.deleted_at IS NULL
                    """, bin(command.typeId()), bin(tenantId), required(command.label(), "label"),
                    blankToNull(command.value()), command.sortOrder(),
                    allowed(command.status(), RESOURCE_STATUSES, "status"), bin(actor.principalId()),
                    bin(id), bin(command.typeId()), normalizedCode(command.code()), command.version());
            requireChanged(changed);
            if ("PLATFORM".equals(actor.scope())) bumpAllTenantPolicies();
            else bumpTenantPolicy(actor.tenantId());
            audit(actor, "DICTIONARY_ITEM_UPDATE", "DICTIONARY_ITEM", id);
        });
        return dictionaryItemById(actor, id);
    }

    @Override
    public List<AuditView> audits(Actor actor, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if ("PLATFORM".equals(actor.scope())) {
            requirePlatform(actor);
            return jdbc.query("""
                    SELECT id, tenant_id, actor_scope, actor_id, action, target_type, target_id, result, occurred_at
                      FROM iam_audit_log ORDER BY occurred_at DESC LIMIT ?
                    """, (rs, row) -> auditView(rs), safeLimit);
        }
        requireTenantPermission(actor, "iam:audit:read");
        return jdbc.query("""
                SELECT id, tenant_id, actor_scope, actor_id, action, target_type, target_id, result, occurred_at
                  FROM iam_audit_log WHERE tenant_id=? ORDER BY occurred_at DESC LIMIT ?
                """, (rs, row) -> auditView(rs), bin(actor.tenantId()), safeLimit);
    }

    private void requirePlatform(Actor actor) {
        if (!"PLATFORM".equals(actor.scope()) || count("""
                SELECT COUNT(*) FROM iam_platform_user
                 WHERE id=? AND platform_role='SUPER_ADMIN' AND status='ACTIVE' AND deleted_at IS NULL
                """, bin(actor.principalId())) != 1) {
            throw new AccessDeniedException("Platform super administrator is required");
        }
    }

    private void requireTenantPermission(Actor actor, String permission) {
        if (!"TENANT".equals(actor.scope()) || count("""
                SELECT COUNT(DISTINCT r.id)
                  FROM iam_user_role ur
                  JOIN iam_role role_record ON role_record.tenant_id=ur.tenant_id AND role_record.id=ur.role_id
                  JOIN iam_role_resource rr ON rr.tenant_id=ur.tenant_id AND rr.role_id=ur.role_id
                  JOIN iam_resource r ON r.id=rr.resource_id
                  JOIN iam_tenant_subscription subscription ON subscription.tenant_id=ur.tenant_id
                    AND subscription.status IN ('ACTIVE','SCHEDULED') AND subscription.effective_from<=UTC_TIMESTAMP(6)
                    AND subscription.effective_to>UTC_TIMESTAMP(6)
                  JOIN iam_package_resource pr ON pr.package_version_id=subscription.package_version_id
                    AND pr.resource_id=r.id
                 WHERE ur.tenant_id=? AND ur.user_id=? AND ur.status='ACTIVE'
                   AND ur.effective_from<=UTC_TIMESTAMP(6)
                   AND (ur.effective_to IS NULL OR ur.effective_to>UTC_TIMESTAMP(6))
                   AND role_record.status='ACTIVE' AND role_record.deleted_at IS NULL
                   AND rr.status='ACTIVE' AND r.status='ACTIVE' AND r.deleted_at IS NULL
                   AND r.permission_code=?
                """, bin(actor.tenantId()), bin(actor.principalId()), permission) < 1) {
            throw new AccessDeniedException("Permission denied: " + permission);
        }
    }

    private void assignUserRoles(Actor actor, UUID userId, List<UUID> roleIds) {
        for (UUID roleId : roleIds) requireAssignableRoleOwned(actor.tenantId(), roleId);
        jdbc.update("UPDATE iam_user_role SET status='INACTIVE', updated_at=UTC_TIMESTAMP(6), updated_by=? WHERE tenant_id=? AND user_id=?",
                bin(actor.principalId()), bin(actor.tenantId()), bin(userId));
        for (UUID roleId : roleIds) {
            jdbc.update("""
                    INSERT INTO iam_user_role
                    (tenant_id, user_id, role_id, status, effective_from, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    ON DUPLICATE KEY UPDATE status='ACTIVE', effective_from=UTC_TIMESTAMP(6), effective_to=NULL,
                                            updated_at=UTC_TIMESTAMP(6), updated_by=VALUES(updated_by)
                    """, bin(actor.tenantId()), bin(userId), bin(roleId), bin(actor.principalId()), bin(actor.principalId()));
        }
    }

    private void assignUserOrganizations(Actor actor, UUID userId, List<UUID> organizationIds) {
        for (UUID organizationId : organizationIds) {
            if (count("SELECT COUNT(*) FROM iam_organization WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
                    bin(actor.tenantId()), bin(organizationId)) != 1) {
                throw new AccessDeniedException("Organization is outside the current tenant");
            }
        }
        jdbc.update("UPDATE iam_user_organization SET status='INACTIVE', updated_at=UTC_TIMESTAMP(6), updated_by=? WHERE tenant_id=? AND user_id=?",
                bin(actor.principalId()), bin(actor.tenantId()), bin(userId));
        for (int index = 0; index < organizationIds.size(); index++) {
            UUID organizationId = organizationIds.get(index);
            jdbc.update("""
                    INSERT INTO iam_user_organization
                    (tenant_id, user_id, organization_id, is_primary, status, effective_from,
                     created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    ON DUPLICATE KEY UPDATE is_primary=VALUES(is_primary), status='ACTIVE',
                      effective_from=UTC_TIMESTAMP(6), effective_to=NULL, updated_at=UTC_TIMESTAMP(6),
                      updated_by=VALUES(updated_by)
                    """, bin(actor.tenantId()), bin(userId), bin(organizationId), index == 0,
                    bin(actor.principalId()), bin(actor.principalId()));
        }
    }

    private void assignRoleResources(Actor actor, UUID roleId, List<UUID> resourceIds) {
        requireRoleOwned(actor.tenantId(), roleId);
        jdbc.update("UPDATE iam_role_resource SET status='INACTIVE', updated_at=UTC_TIMESTAMP(6), updated_by=? WHERE tenant_id=? AND role_id=?",
                bin(actor.principalId()), bin(actor.tenantId()), bin(roleId));
        for (UUID resourceId : resourceIds) {
            if (count("""
                    SELECT COUNT(*) FROM iam_resource r
                    JOIN iam_package_resource pr ON pr.resource_id=r.id
                    JOIN iam_tenant_subscription subscription ON subscription.package_version_id=pr.package_version_id
                     AND subscription.tenant_id=? AND subscription.status IN ('ACTIVE','SCHEDULED')
                     AND subscription.effective_from<=UTC_TIMESTAMP(6) AND subscription.effective_to>UTC_TIMESTAMP(6)
                    WHERE r.id=? AND r.status='ACTIVE' AND r.deleted_at IS NULL
                    """, bin(actor.tenantId()), bin(resourceId)) < 1) {
                throw new AccessDeniedException("Resource is outside the tenant subscription");
            }
            jdbc.update("""
                    INSERT INTO iam_role_resource
                    (tenant_id, role_id, resource_id, status, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?)
                    ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=UTC_TIMESTAMP(6), updated_by=VALUES(updated_by)
                    """, bin(actor.tenantId()), bin(roleId), bin(resourceId), bin(actor.principalId()), bin(actor.principalId()));
        }
    }

    private void requireRoleOwned(UUID tenantId, UUID roleId) {
        if (count("SELECT COUNT(*) FROM iam_role WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
                bin(tenantId), bin(roleId)) != 1) throw new AccessDeniedException("Role is outside the current tenant");
    }

    private void requireAssignableRoleOwned(UUID tenantId, UUID roleId) {
        if (count("""
                SELECT COUNT(*) FROM iam_role WHERE tenant_id=? AND id=? AND status='ACTIVE' AND deleted_at IS NULL
                """, bin(tenantId), bin(roleId)) != 1) {
            throw new AccessDeniedException("Assignable role is outside the current tenant or inactive");
        }
    }

    private void requireCustomRole(UUID tenantId, UUID roleId) {
        if (count("""
                SELECT COUNT(*) FROM iam_role WHERE tenant_id=? AND id=? AND role_type='CUSTOM' AND deleted_at IS NULL
                """, bin(tenantId), bin(roleId)) != 1) {
            throw new AccessDeniedException("System roles are managed by IAM and cannot be edited here");
        }
    }

    private void requireUserCapacity(UUID tenantId, UUID excludedUserId, String targetStatus) {
        String status = allowed(targetStatus, USER_STATUSES, "status");
        if (!Set.of("ACTIVE", "LOCKED").contains(status)) return;
        List<Integer> limits = jdbc.queryForList("""
                SELECT user_limit FROM iam_tenant_subscription
                 WHERE tenant_id=? AND status IN ('ACTIVE','SCHEDULED')
                   AND effective_from<=UTC_TIMESTAMP(6) AND effective_to>UTC_TIMESTAMP(6)
                 ORDER BY effective_from DESC
                """, Integer.class, bin(tenantId));
        if (limits.size() != 1) throw new IllegalStateException("Tenant requires exactly one current subscription");
        int used = excludedUserId == null ? count("""
                SELECT COUNT(*) FROM iam_user WHERE tenant_id=? AND status IN ('ACTIVE','LOCKED')
                 AND deleted_at IS NULL
                """, bin(tenantId)) : count("""
                SELECT COUNT(*) FROM iam_user WHERE tenant_id=? AND id<>? AND status IN ('ACTIVE','LOCKED')
                 AND deleted_at IS NULL
                """, bin(tenantId), bin(excludedUserId));
        if (used >= limits.getFirst()) throw new IllegalStateException("Tenant subscription user limit has been reached");
    }

    private void preserveLastTenantAdministrator(UUID tenantId, UUID userId, String targetStatus,
                                                 List<UUID> targetRoleIds) {
        List<UUID> adminRoles = jdbc.query("""
                SELECT id FROM iam_role WHERE tenant_id=? AND role_code='TENANT_SUPER_ADMIN'
                 AND role_type='SYSTEM' AND deleted_at IS NULL
                """, (rs, row) -> uuid(rs, "id"), bin(tenantId));
        if (adminRoles.isEmpty()) return;
        UUID adminRoleId = adminRoles.getFirst();
        boolean isAdministrator = count("""
                SELECT COUNT(*) FROM iam_user_role WHERE tenant_id=? AND user_id=? AND role_id=? AND status='ACTIVE'
                 AND effective_from<=UTC_TIMESTAMP(6) AND (effective_to IS NULL OR effective_to>UTC_TIMESTAMP(6))
                """, bin(tenantId), bin(userId), bin(adminRoleId)) == 1;
        boolean remainsAdministrator = "ACTIVE".equalsIgnoreCase(targetStatus) && targetRoleIds.contains(adminRoleId);
        if (isAdministrator && !remainsAdministrator && count("""
                SELECT COUNT(DISTINCT user_record.id)
                  FROM iam_user user_record
                  JOIN iam_user_role user_role ON user_role.tenant_id=user_record.tenant_id
                   AND user_role.user_id=user_record.id AND user_role.role_id=? AND user_role.status='ACTIVE'
                 WHERE user_record.tenant_id=? AND user_record.id<>? AND user_record.status='ACTIVE'
                   AND user_record.deleted_at IS NULL
                   AND user_role.effective_from<=UTC_TIMESTAMP(6)
                   AND (user_role.effective_to IS NULL OR user_role.effective_to>UTC_TIMESTAMP(6))
                """, bin(adminRoleId), bin(tenantId), bin(userId)) == 0) {
            throw new IllegalStateException("The last active tenant administrator cannot be disabled or unassigned");
        }
    }

    private void requireSubscribedApplication(UUID tenantId, UUID applicationId) {
        if (applicationId == null || count("""
                SELECT COUNT(DISTINCT application_record.id)
                  FROM iam_application application_record
                  JOIN iam_resource resource_record ON resource_record.application_id=application_record.id
                   AND resource_record.status='ACTIVE' AND resource_record.deleted_at IS NULL
                  JOIN iam_package_resource package_resource ON package_resource.resource_id=resource_record.id
                  JOIN iam_tenant_subscription subscription
                    ON subscription.package_version_id=package_resource.package_version_id
                   AND subscription.tenant_id=? AND subscription.status IN ('ACTIVE','SCHEDULED')
                   AND subscription.effective_from<=UTC_TIMESTAMP(6)
                   AND subscription.effective_to>UTC_TIMESTAMP(6)
                 WHERE application_record.id=? AND application_record.app_scope='TENANT'
                   AND application_record.status='ACTIVE' AND application_record.deleted_at IS NULL
                """, bin(tenantId), bin(applicationId)) != 1) {
            throw new AccessDeniedException("Application is outside the tenant subscription");
        }
    }

    private void requireApplication(UUID applicationId) {
        if (applicationId == null || count("SELECT COUNT(*) FROM iam_application WHERE id=? AND deleted_at IS NULL",
                bin(applicationId)) != 1) throw new IllegalArgumentException("Unknown application");
    }

    /**
     * 校验字典类型归属，返回该字典绑定的租户ID；平台字典返回 null。
     * 字典项不能跨平台/租户边界迁移，避免共享枚举绕过租户隔离。
     */
    private UUID requireDictionaryTypeAccess(Actor actor, UUID typeId, boolean write) {
        if (typeId == null) throw new IllegalArgumentException("typeId is required");
        if ("PLATFORM".equals(actor.scope())) {
            requirePlatform(actor);
            if (count("""
                    SELECT COUNT(*) FROM iam_dictionary_type
                     WHERE id=? AND owner_scope='PLATFORM' AND tenant_id IS NULL AND deleted_at IS NULL
                    """, bin(typeId)) != 1) {
                throw new AccessDeniedException("Dictionary type is outside the platform boundary");
            }
            return null;
        }
        requireTenantPermission(actor, write ? "iam:dictionary:write" : "iam:dictionary:read");
        if (count("""
                SELECT COUNT(*) FROM iam_dictionary_type
                 WHERE id=? AND owner_scope='TENANT' AND tenant_id=? AND deleted_at IS NULL
                """, bin(typeId), bin(actor.tenantId())) != 1) {
            throw new AccessDeniedException("Dictionary type is outside the current tenant");
        }
        return actor.tenantId();
    }

    private String trustedRedirect(String raw) {
        URI uri;
        try { uri = URI.create(required(raw, "redirectUri")); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid redirect URI", exception); }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopback = allowInsecureLoopback && "http".equalsIgnoreCase(uri.getScheme()) && ("localhost".equalsIgnoreCase(uri.getHost())
                || "127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost()));
        if (!(https || loopback) || uri.getHost() == null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Redirect URI must use HTTPS or explicitly enabled loopback HTTP");
        }
        return uri.toString();
    }

    private void saveResourceUi(UUID resourceId, ResourceCommand command, boolean update) {
        String type = required(command.type(), "type").toUpperCase();
        boolean navigable = "MENU".equals(type) || "PAGE".equals(type);
        if (!navigable) {
            if (update) jdbc.update("DELETE FROM iam_resource_ui WHERE resource_id=?", bin(resourceId));
            return;
        }
        String routeKey = normalizedCode(command.routeKey());
        jdbc.update("""
                INSERT INTO iam_resource_ui
                (resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE route_key=VALUES(route_key), route_path=VALUES(route_path),
                  icon_key=VALUES(icon_key), visible=VALUES(visible), keep_alive=VALUES(keep_alive),
                  version=version+1, updated_at=UTC_TIMESTAMP(6)
                """, bin(resourceId), routeKey, blankToNull(command.routePath()), blankToNull(command.iconKey()),
                command.visible(), command.keepAlive());
    }

    private void validateParent(UUID applicationId, UUID parentId, UUID self) {
        if (parentId == null) return;
        if (parentId.equals(self) || count("""
                SELECT COUNT(*) FROM iam_resource WHERE application_id=? AND id=? AND deleted_at IS NULL
                """, bin(applicationId), bin(parentId)) != 1) throw new IllegalArgumentException("Invalid resource parent");
    }

    private String parentPath(UUID tenantId, UUID parentId) {
        if (parentId == null) return "";
        List<String> paths = jdbc.queryForList("""
                SELECT path FROM iam_organization WHERE tenant_id=? AND id=? AND deleted_at IS NULL
                """, String.class, bin(tenantId), bin(parentId));
        if (paths.size() != 1) throw new IllegalArgumentException("Invalid organization parent");
        return paths.getFirst();
    }

    private void ensureNoOrganizationCycle(UUID tenantId, UUID id, UUID parentId) {
        if (parentId == null) return;
        if (id.equals(parentId)) throw new IllegalArgumentException("Organization cannot parent itself");
        String currentPath = jdbc.queryForObject("SELECT path FROM iam_organization WHERE tenant_id=? AND id=?",
                String.class, bin(tenantId), bin(id));
        String candidatePath = parentPath(tenantId, parentId);
        if (candidatePath.equals(currentPath) || candidatePath.startsWith(currentPath + "/")) {
            throw new IllegalArgumentException("Organization cycle is not allowed");
        }
    }

    private void bumpTenantPolicy(UUID tenantId) {
        jdbc.update("UPDATE iam_tenant SET policy_version=policy_version+1, version=version+1, updated_at=UTC_TIMESTAMP(6) WHERE id=?",
                bin(tenantId));
    }

    private void bumpAllTenantPolicies() {
        jdbc.update("UPDATE iam_tenant SET policy_version=policy_version+1, version=version+1, updated_at=UTC_TIMESTAMP(6) WHERE deleted_at IS NULL");
    }

    private void audit(Actor actor, String action, String targetType, UUID targetId) {
        UUID id = ids.nextId();
        jdbc.update("""
                INSERT INTO iam_audit_log
                (id, tenant_id, actor_scope, actor_id, action, target_type, target_id,
                 request_id, event_seq, result, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'SUCCESS', UTC_TIMESTAMP(6))
                """, bin(id), bin(actor.tenantId()), actor.scope(), bin(actor.principalId()), action,
                targetType, bin(targetId), bin(ids.nextId()));
    }

    private ApplicationView applicationById(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, app_code, app_name, app_scope, app_type, icon_key, sort_order,
                       launch_mode, target_uri, status, version FROM iam_application WHERE id=?
                """, (rs, row) -> application(rs), bin(id));
    }

    private TenantView tenantById(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, tenant_code, company_name, status, policy_version, version
                  FROM iam_tenant WHERE id=?
                """, (rs, row) -> new TenantView(uuid(rs, "id"), rs.getString("tenant_code"),
                rs.getString("company_name"), rs.getString("status"), rs.getLong("policy_version"),
                rs.getLong("version")), bin(id));
    }

    private PackageView packageById(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, package_code, package_name, description, status, version
                  FROM iam_tenant_package WHERE id=?
                """, (rs, row) -> packageView(rs), bin(id));
    }

    private PackageVersionView packageVersionById(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, package_id, version_no, publish_status, default_user_limit, change_note, version
                  FROM iam_tenant_package_version WHERE id=?
                """, (rs, row) -> packageVersion(rs), bin(id));
    }

    private TenantMenuView tenantMenuById(UUID tenantId, UUID resourceId) {
        List<TenantMenuView> result = jdbc.query("""
                SELECT DISTINCT r.id resource_id, r.application_id, a.app_code, a.app_name,
                       r.parent_id, r.resource_code, r.resource_type,
                       r.display_name original_display_name,
                       COALESCE(menu_config.display_name_override, r.display_name) display_name,
                       r.sort_order original_sort_order,
                       COALESCE(menu_config.sort_order_override, r.sort_order) sort_order,
                       ui.icon_key original_icon_key,
                       COALESCE(menu_config.icon_key_override, ui.icon_key) icon_key,
                       ui.visible platform_visible, COALESCE(menu_config.visible, 0) visible,
                       menu_config.parent_group_id,
                       CASE WHEN menu_config.resource_id IS NULL THEN 0 ELSE 1 END configured,
                       COALESCE(menu_config.version, 0) version
                  FROM iam_tenant_subscription subscription
                  JOIN iam_package_resource package_resource
                    ON package_resource.package_version_id=subscription.package_version_id
                  JOIN iam_resource r ON r.id=package_resource.resource_id
                  JOIN iam_resource_ui ui ON ui.resource_id=r.id
                  JOIN iam_application a ON a.id=r.application_id
                  LEFT JOIN iam_tenant_menu_config menu_config
                    ON menu_config.tenant_id=subscription.tenant_id AND menu_config.resource_id=r.id
                 WHERE subscription.tenant_id=? AND r.id=?
                   AND subscription.status IN ('ACTIVE','SCHEDULED')
                   AND subscription.effective_from<=UTC_TIMESTAMP(6)
                   AND subscription.effective_to>UTC_TIMESTAMP(6)
                   AND r.resource_type IN ('MENU','PAGE')
                   AND r.status='ACTIVE' AND r.deleted_at IS NULL
                """, (rs, row) -> tenantMenu(rs), bin(tenantId), bin(resourceId));
        if (result.size() != 1) throw new AccessDeniedException("Menu is outside the tenant subscription");
        return result.getFirst();
    }

    private TenantMenuGroupView tenantMenuGroupById(UUID tenantId, UUID id) {
        return jdbc.queryForObject("""
                SELECT menu_group.id, menu_group.application_id, application_record.app_code,
                       application_record.app_name, menu_group.parent_id, menu_group.group_code,
                       menu_group.display_name, menu_group.icon_key, menu_group.sort_order,
                       menu_group.visible, menu_group.status, menu_group.version
                  FROM iam_tenant_menu_group menu_group
                  JOIN iam_application application_record ON application_record.id=menu_group.application_id
                 WHERE menu_group.tenant_id=? AND menu_group.id=? AND menu_group.deleted_at IS NULL
                """, (rs, row) -> tenantMenuGroup(rs), bin(tenantId), bin(id));
    }

    private ResourceView resourceById(UUID id) {
        return jdbc.queryForObject("""
                SELECT r.id, r.application_id, r.parent_id, r.resource_code, r.resource_type,
                       r.permission_code, r.display_name, r.sort_order, r.status, r.version,
                       ui.route_key, ui.route_path, ui.icon_key, ui.visible, ui.keep_alive
                  FROM iam_resource r LEFT JOIN iam_resource_ui ui ON ui.resource_id=r.id WHERE r.id=?
                """, (rs, row) -> resource(rs), bin(id));
    }

    private OrganizationView organizationById(UUID tenantId, UUID id) {
        return jdbc.queryForObject("""
                SELECT id, parent_id, org_code, org_name, org_type, path, sort_order, status, version
                  FROM iam_organization WHERE tenant_id=? AND id=?
                """, (rs, row) -> organization(rs), bin(tenantId), bin(id));
    }

    private UserView userById(UUID tenantId, UUID id) {
        return jdbc.queryForObject("""
                SELECT id, username, display_name, status, security_version, version
                  FROM iam_user WHERE tenant_id=? AND id=?
                """, (rs, row) -> user(rs, tenantId), bin(tenantId), bin(id));
    }

    private RoleView roleById(UUID tenantId, UUID id) {
        return jdbc.queryForObject("""
                SELECT id, role_code, role_name, role_type, status, version
                  FROM iam_role WHERE tenant_id=? AND id=?
                """, (rs, row) -> role(rs, tenantId), bin(tenantId), bin(id));
    }

    private DataScopeView dataScopeById(UUID tenantId, UUID id) {
        return jdbc.queryForObject("""
                SELECT id, role_id, application_id, scope_key, scope_type, status, version
                  FROM iam_data_scope_policy WHERE tenant_id=? AND id=?
                """, (rs, row) -> dataScope(rs), bin(tenantId), bin(id));
    }

    private DictionaryTypeView dictionaryTypeById(Actor actor, UUID id) {
        if ("PLATFORM".equals(actor.scope())) {
            return jdbc.queryForObject("""
                    SELECT id, tenant_id, type_code, type_name, description, status, version
                      FROM iam_dictionary_type WHERE id=? AND owner_scope='PLATFORM' AND tenant_id IS NULL
                    """, (rs, row) -> dictionaryType(rs), bin(id));
        }
        return jdbc.queryForObject("""
                SELECT id, tenant_id, type_code, type_name, description, status, version
                  FROM iam_dictionary_type WHERE id=? AND owner_scope='TENANT' AND tenant_id=?
                """, (rs, row) -> dictionaryType(rs), bin(id), bin(actor.tenantId()));
    }

    private DictionaryItemView dictionaryItemById(Actor actor, UUID id) {
        if ("PLATFORM".equals(actor.scope())) {
            return jdbc.queryForObject("""
                    SELECT item.id, item.type_id, item.tenant_id, item.item_code, item.item_label,
                           item.item_value, item.sort_order, item.status, item.version
                      FROM iam_dictionary_item item
                      JOIN iam_dictionary_type type_record ON type_record.id=item.type_id
                     WHERE item.id=? AND type_record.owner_scope='PLATFORM' AND type_record.tenant_id IS NULL
                    """, (rs, row) -> dictionaryItem(rs), bin(id));
        }
        return jdbc.queryForObject("""
                SELECT item.id, item.type_id, item.tenant_id, item.item_code, item.item_label,
                       item.item_value, item.sort_order, item.status, item.version
                  FROM iam_dictionary_item item
                  JOIN iam_dictionary_type type_record ON type_record.id=item.type_id
                 WHERE item.id=? AND type_record.owner_scope='TENANT' AND type_record.tenant_id=?
                """, (rs, row) -> dictionaryItem(rs), bin(id), bin(actor.tenantId()));
    }

    private ApplicationView application(ResultSet rs) throws SQLException {
        return new ApplicationView(uuid(rs, "id"), rs.getString("app_code"), rs.getString("app_name"),
                rs.getString("app_scope"), rs.getString("app_type"), rs.getString("icon_key"),
                rs.getInt("sort_order"), rs.getString("launch_mode"), rs.getString("target_uri"),
                rs.getString("status"), rs.getLong("version"));
    }

    private PackageView packageView(ResultSet rs) throws SQLException {
        return new PackageView(uuid(rs, "id"), rs.getString("package_code"), rs.getString("package_name"),
                rs.getString("description"), rs.getString("status"), rs.getLong("version"));
    }

    private PackageVersionView packageVersion(ResultSet rs) throws SQLException {
        UUID id = uuid(rs, "id");
        List<UUID> resourceIds = jdbc.queryForList("""
                SELECT resource_id FROM iam_package_resource WHERE package_version_id=? ORDER BY resource_id
                """, byte[].class, bin(id)).stream().map(UuidBinaryCodec::decode).toList();
        return new PackageVersionView(id, uuid(rs, "package_id"), rs.getInt("version_no"),
                rs.getString("publish_status"), rs.getInt("default_user_limit"), rs.getString("change_note"),
                rs.getLong("version"), resourceIds);
    }

    private ResourceView resource(ResultSet rs) throws SQLException {
        return new ResourceView(uuid(rs, "id"), uuid(rs, "application_id"), uuid(rs, "parent_id"),
                rs.getString("resource_code"), rs.getString("resource_type"), rs.getString("permission_code"),
                rs.getString("display_name"), rs.getInt("sort_order"), rs.getString("status"),
                rs.getString("route_key"), rs.getString("route_path"), rs.getString("icon_key"),
                rs.getBoolean("visible"), rs.getBoolean("keep_alive"), rs.getLong("version"));
    }

    private TenantMenuView tenantMenu(ResultSet rs) throws SQLException {
        return new TenantMenuView(uuid(rs, "resource_id"), uuid(rs, "application_id"),
                rs.getString("app_code"), rs.getString("app_name"), uuid(rs, "parent_id"),
                rs.getString("resource_code"), rs.getString("resource_type"),
                rs.getString("original_display_name"), rs.getString("display_name"),
                rs.getInt("original_sort_order"), rs.getInt("sort_order"),
                rs.getString("original_icon_key"), rs.getString("icon_key"),
                rs.getBoolean("platform_visible"), rs.getBoolean("visible"),
                uuid(rs, "parent_group_id"), rs.getBoolean("configured"), rs.getLong("version"));
    }

    private TenantMenuGroupView tenantMenuGroup(ResultSet rs) throws SQLException {
        return new TenantMenuGroupView(uuid(rs, "id"), uuid(rs, "application_id"),
                rs.getString("app_code"), rs.getString("app_name"), uuid(rs, "parent_id"),
                rs.getString("group_code"), rs.getString("display_name"), rs.getString("icon_key"),
                rs.getInt("sort_order"), rs.getBoolean("visible"), rs.getString("status"),
                rs.getLong("version"));
    }

    private OrganizationView organization(ResultSet rs) throws SQLException {
        return new OrganizationView(uuid(rs, "id"), uuid(rs, "parent_id"), rs.getString("org_code"),
                rs.getString("org_name"), rs.getString("org_type"), rs.getString("path"),
                rs.getInt("sort_order"), rs.getString("status"), rs.getLong("version"));
    }

    private UserView user(ResultSet rs, UUID tenantId) throws SQLException {
        UUID id = uuid(rs, "id");
        List<UUID> roleIds = jdbc.queryForList("""
                SELECT role_id FROM iam_user_role WHERE tenant_id=? AND user_id=? AND status='ACTIVE'
                  AND effective_from<=UTC_TIMESTAMP(6) AND (effective_to IS NULL OR effective_to>UTC_TIMESTAMP(6))
                """, byte[].class, bin(tenantId), bin(id)).stream().map(UuidBinaryCodec::decode).toList();
        List<UUID> organizationIds = jdbc.queryForList("""
                SELECT organization_id FROM iam_user_organization WHERE tenant_id=? AND user_id=? AND status='ACTIVE'
                  AND effective_from<=UTC_TIMESTAMP(6) AND (effective_to IS NULL OR effective_to>UTC_TIMESTAMP(6))
                ORDER BY is_primary DESC, organization_id
                """, byte[].class, bin(tenantId), bin(id)).stream().map(UuidBinaryCodec::decode).toList();
        return new UserView(id, rs.getString("username"), rs.getString("display_name"), rs.getString("status"),
                rs.getLong("security_version"), rs.getLong("version"), roleIds, organizationIds);
    }

    private RoleView role(ResultSet rs, UUID tenantId) throws SQLException {
        UUID id = uuid(rs, "id");
        List<UUID> resourceIds = jdbc.queryForList("""
                SELECT resource_id FROM iam_role_resource WHERE tenant_id=? AND role_id=? AND status='ACTIVE'
                """, byte[].class, bin(tenantId), bin(id)).stream().map(UuidBinaryCodec::decode).toList();
        return new RoleView(id, rs.getString("role_code"), rs.getString("role_name"), rs.getString("role_type"),
                rs.getString("status"), rs.getLong("version"), resourceIds);
    }

    private DataScopeView dataScope(ResultSet rs) throws SQLException {
        return new DataScopeView(uuid(rs, "id"), uuid(rs, "role_id"), uuid(rs, "application_id"),
                rs.getString("scope_key"), rs.getString("scope_type"), rs.getString("status"), rs.getLong("version"));
    }

    private DictionaryTypeView dictionaryType(ResultSet rs) throws SQLException {
        return new DictionaryTypeView(uuid(rs, "id"), uuid(rs, "tenant_id"), rs.getString("type_code"),
                rs.getString("type_name"), rs.getString("description"), rs.getString("status"),
                rs.getLong("version"));
    }

    private DictionaryItemView dictionaryItem(ResultSet rs) throws SQLException {
        return new DictionaryItemView(uuid(rs, "id"), uuid(rs, "type_id"), uuid(rs, "tenant_id"),
                rs.getString("item_code"), rs.getString("item_label"), rs.getString("item_value"),
                rs.getInt("sort_order"), rs.getString("status"), rs.getLong("version"));
    }

    private AuditView auditView(ResultSet rs) throws SQLException {
        Timestamp occurredAt = rs.getTimestamp("occurred_at");
        return new AuditView(uuid(rs, "id"), uuid(rs, "tenant_id"), rs.getString("actor_scope"),
                uuid(rs, "actor_id"), rs.getString("action"), rs.getString("target_type"),
                uuid(rs, "target_id"), rs.getString("result"), occurredAt.toInstant());
    }

    private List<ResourceView> navigationGroups(Actor actor, String applicationCode) {
        return jdbc.query("""
                SELECT menu_group.id, menu_group.application_id, menu_group.parent_id,
                       menu_group.group_code resource_code, 'MENU' resource_type,
                       NULL permission_code, menu_group.display_name, menu_group.sort_order,
                       menu_group.status, menu_group.version,
                       CONCAT('tenant.menu.group.', REPLACE(BIN_TO_UUID(menu_group.id), '-', '')) route_key,
                       NULL route_path, menu_group.icon_key, menu_group.visible, 0 keep_alive
                  FROM iam_tenant_menu_group menu_group
                  JOIN iam_application application_record ON application_record.id=menu_group.application_id
                 WHERE menu_group.tenant_id=? AND application_record.app_code=?
                   AND menu_group.status='ACTIVE' AND menu_group.deleted_at IS NULL
                 ORDER BY menu_group.sort_order, menu_group.group_code
                """, (rs, row) -> resource(rs), bin(actor.tenantId()), applicationCode);
    }

    private boolean grantableThroughVisibleMenu(ResourceView resource, Map<UUID, ResourceView> byId) {
        ResourceView current = resource;
        Set<UUID> visited = new java.util.HashSet<>();
        while (current != null && visited.add(current.id())) {
            if (("MENU".equals(current.type()) || "PAGE".equals(current.type())) && !current.visible()) return false;
            current = byId.get(current.parentId());
        }
        return true;
    }

    private void requireEntitledNavigableResource(UUID tenantId, UUID resourceId) {
        if (count("""
                SELECT COUNT(DISTINCT resource_record.id)
                  FROM iam_resource resource_record
                  JOIN iam_resource_ui resource_ui ON resource_ui.resource_id=resource_record.id
                  JOIN iam_package_resource package_resource ON package_resource.resource_id=resource_record.id
                  JOIN iam_tenant_subscription subscription
                    ON subscription.package_version_id=package_resource.package_version_id
                   AND subscription.tenant_id=? AND subscription.status IN ('ACTIVE','SCHEDULED')
                   AND subscription.effective_from<=UTC_TIMESTAMP(6)
                   AND subscription.effective_to>UTC_TIMESTAMP(6)
                 WHERE resource_record.id=? AND resource_record.resource_type IN ('MENU','PAGE')
                   AND resource_record.status='ACTIVE' AND resource_record.deleted_at IS NULL
                """, bin(tenantId), bin(resourceId)) != 1) {
            throw new AccessDeniedException("Menu is outside the tenant subscription");
        }
    }

    private void validateMenuGroupForResource(UUID tenantId, UUID resourceId, UUID groupId) {
        if (groupId == null) return;
        if (count("""
                SELECT COUNT(*)
                  FROM iam_tenant_menu_group menu_group
                  JOIN iam_resource resource_record ON resource_record.application_id=menu_group.application_id
                 WHERE menu_group.tenant_id=? AND menu_group.id=? AND resource_record.id=?
                   AND menu_group.status='ACTIVE' AND menu_group.deleted_at IS NULL
                """, bin(tenantId), bin(groupId), bin(resourceId)) != 1) {
            throw new AccessDeniedException("Menu group is outside the resource application");
        }
    }

    private void validateMenuGroupParent(UUID tenantId, UUID applicationId, UUID parentId, UUID self) {
        if (parentId == null) return;
        if (parentId.equals(self)) throw new IllegalArgumentException("Menu group cannot parent itself");
        UUID current = parentId;
        Set<UUID> visited = new java.util.HashSet<>();
        while (current != null) {
            if (!visited.add(current) || current.equals(self)) {
                throw new IllegalArgumentException("Menu group cycle is not allowed");
            }
            List<byte[]> parents = jdbc.queryForList("""
                    SELECT parent_id FROM iam_tenant_menu_group
                     WHERE tenant_id=? AND application_id=? AND id=? AND deleted_at IS NULL
                    """, byte[].class, bin(tenantId), bin(applicationId), bin(current));
            if (parents.size() != 1) throw new AccessDeniedException("Menu group parent is outside the application");
            current = UuidBinaryCodec.decode(parents.getFirst());
        }
    }

    private List<NavigationNode> tree(List<ResourceView> flat) {
        Map<UUID, MutableNode> nodes = new LinkedHashMap<>();
        flat.forEach(item -> nodes.put(item.id(), new MutableNode(item)));
        List<MutableNode> roots = new ArrayList<>();
        nodes.values().forEach(node -> {
            MutableNode parent = nodes.get(node.value.parentId());
            if (parent == null) roots.add(node); else parent.children.add(node);
        });
        Comparator<MutableNode> order = Comparator.comparingInt((MutableNode n) -> n.value.sortOrder())
                .thenComparing(n -> n.value.code());
        return roots.stream().sorted(order).map(node -> node.freeze(order)).toList();
    }

    private static final class MutableNode {
        private final ResourceView value;
        private final List<MutableNode> children = new ArrayList<>();
        private MutableNode(ResourceView value) { this.value = value; }
        private NavigationNode freeze(Comparator<MutableNode> order) {
            return new NavigationNode(value.id(), value.parentId(), value.code(), value.type(), value.displayName(),
                    value.permissionCode(), value.routeKey(), value.routePath(), value.iconKey(), value.sortOrder(),
                    value.visible(), value.keepAlive(), children.stream().sorted(order).map(n -> n.freeze(order)).toList());
        }
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return UuidBinaryCodec.decode(rs.getBytes(column));
    }

    private static byte[] bin(UUID value) { return UuidBinaryCodec.encode(value); }
    private static void requireChanged(int changed) {
        if (changed != 1) throw new IllegalStateException("Record changed concurrently or no longer exists");
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String optionalText(String value, String field, int maxLength) {
        String normalized = blankToNull(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }
    private static String normalizedCode(String value) {
        String code = required(value, "code").strip();
        if (!code.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("code contains unsupported characters");
        }
        return code;
    }
    private static String normalizedPermissionCode(String value) {
        String permission = blankToNull(value);
        if (permission != null && !permission.matches("[A-Za-z0-9*][A-Za-z0-9*._:-]{0,127}")) {
            throw new IllegalArgumentException("permissionCode contains unsupported characters");
        }
        return permission;
    }
    private static String normalizedUsername(String value) { return required(value, "username").strip().toLowerCase(); }
    private static String allowed(String value, Set<String> values, String field) {
        String normalized = required(value, field).toUpperCase();
        if (!values.contains(normalized)) throw new IllegalArgumentException("Invalid " + field);
        return normalized;
    }
    private void validateApplication(ApplicationCommand c) {
        required(c.code(), "code");
        required(c.name(), "name");
        String type = allowed(c.type(), APPLICATION_TYPES, "type");
        String mode = required(c.launchMode(), "launchMode").toUpperCase();
        String target = blankToNull(c.targetUri());
        boolean compatible = "INTERNAL".equals(type)
                ? Set.of("INTERNAL_ROUTE", "OIDC_CLIENT").contains(mode)
                : Set.of("EXTERNAL_URL", "FEISHU_DEEPLINK", "SSO_PROVIDER").contains(mode);
        if (!compatible) throw new IllegalArgumentException("Application type and launchMode are incompatible");
        if ("ACTIVE".equalsIgnoreCase(c.status()) && target == null) {
            throw new IllegalArgumentException("Active application requires targetUri");
        }
        if (target == null) return;
        if ("INTERNAL_ROUTE".equals(mode) && (!target.startsWith("/") || target.startsWith("//"))) {
            throw new IllegalArgumentException("Internal route must be an absolute application path");
        }
        if ("OIDC_CLIENT".equals(mode)) trustedRedirect(target);
        if ("EXTERNAL_URL".equals(mode)) {
            URI uri;
            try { uri = URI.create(target); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid external URL", exception); }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("External URL must use HTTPS");
            }
        }
    }
    private static void validateResource(ResourceCommand c) {
        if (c.applicationId() == null) throw new IllegalArgumentException("applicationId is required");
        required(c.code(), "code"); required(c.type(), "type"); required(c.displayName(), "displayName");
        String type = c.type().toUpperCase();
        if (("MENU".equals(type) || "PAGE".equals(type)) && (c.routeKey() == null || c.routeKey().isBlank())) {
            throw new IllegalArgumentException("Navigable resource requires routeKey");
        }
        if ("PAGE".equals(type)) {
            String path = blankToNull(c.routePath());
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                throw new IllegalArgumentException("Page resource requires an absolute routePath");
            }
        }
    }
    private static void validateTenantMenuGroup(TenantMenuGroupCommand c) {
        if (c == null || c.applicationId() == null) throw new IllegalArgumentException("applicationId is required");
        required(c.code(), "code");
        required(c.displayName(), "displayName");
        optionalText(c.iconKey(), "iconKey", 128);
    }
    private static void validateOrganization(OrganizationCommand c) {
        required(c.code(), "code"); required(c.name(), "name"); required(c.type(), "type");
    }
    private static void validateUser(UserCommand c, boolean creating) {
        required(c.username(), "username"); required(c.displayName(), "displayName");
        if (creating && (c.initialPassword() == null || c.initialPassword().length() < 14
                || c.initialPassword().length() > 128)) {
            throw new IllegalArgumentException("Initial password must contain between 14 and 128 characters");
        }
    }
    private static void validateRole(RoleCommand c) {
        required(c.code(), "code"); required(c.name(), "name"); required(c.type(), "type");
    }
    private static void validateDictionaryType(DictionaryTypeCommand c) {
        required(c.code(), "code");
        required(c.name(), "name");
    }
    private static void validateDictionaryItem(DictionaryItemCommand c) {
        if (c.typeId() == null) throw new IllegalArgumentException("typeId is required");
        required(c.code(), "code");
        required(c.label(), "label");
    }
}
