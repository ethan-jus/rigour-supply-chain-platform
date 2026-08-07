package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.application.service.management.IamManagementService;
import com.rigour.tenant.iam.application.service.management.ManagementModels.*;
import com.rigour.tenant.iam.application.service.portal.PortalAccessQuery;
import com.rigour.tenant.iam.application.service.portal.PortalAccessService;
import com.rigour.shared.context.RequestContext;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** IAM V1管理与数据库驱动导航HTTP边界；权限和租户归属由应用仓储再次校验。 */
@RestController
@RequestMapping("/api/v1")
public final class IamManagementController {
    private static final Logger log = LoggerFactory.getLogger(IamManagementController.class);

    private final IamManagementService service;
    private final PortalAccessService portalAccessService;

    public IamManagementController(IamManagementService service, PortalAccessService portalAccessService) {
        this.service = service;
        this.portalAccessService = portalAccessService;
    }

    @GetMapping("/token/current")
    public CurrentTokenView validateCurrentToken() {
        Actor actor = currentActor();
        var user = portalAccessService.currentUser(
                new PortalAccessQuery(actor.scope(), actor.principalId(), actor.tenantId()));
        return new CurrentTokenView(user.roles(), user.permissions());
    }

    /** Gateway在线校验成功后使用的最小授权快照，不包含数据范围业务值。 */
    public record CurrentTokenView(Set<String> roles, Set<String> permissions) {
        public CurrentTokenView { roles = Set.copyOf(roles); permissions = Set.copyOf(permissions); }
    }

    @GetMapping("/portal/navigation/{applicationCode}")
    public List<NavigationNode> navigation(@PathVariable("applicationCode") String applicationCode) {
        Actor actor = currentActor();
        try {
            List<NavigationNode> result = service.navigation(actor, applicationCode);
            log.info("IAM导航加载成功 requestId={} scope={} principalId={} tenantId={} applicationCode={} rootCount={}",
                    RequestContext.getRequestId(), actor.scope(), actor.principalId(), actor.tenantId(),
                    applicationCode, result.size());
            return result;
        } catch (RuntimeException exception) {
            log.warn("IAM导航加载失败 requestId={} scope={} principalId={} tenantId={} applicationCode={} reason={}",
                    RequestContext.getRequestId(), actor.scope(), actor.principalId(), actor.tenantId(),
                    applicationCode, exception.getMessage());
            throw exception;
        }
    }

    @GetMapping("/management/platform/applications")
    public List<ApplicationView> applications() { return service.applications(currentActor()); }

    @PostMapping("/management/platform/applications")
    public ApplicationView createApplication(@RequestBody ApplicationCommand command) {
        return service.createApplication(currentActor(), command);
    }

    @PutMapping("/management/platform/applications/{id}")
    public ApplicationView updateApplication(@PathVariable("id") UUID id, @RequestBody ApplicationCommand command) {
        return service.updateApplication(currentActor(), id, command);
    }

    @GetMapping("/management/platform/oidc-clients")
    public List<OidcClientView> oidcClients() { return service.oidcClients(currentActor()); }

    @PostMapping("/management/platform/oidc-clients")
    public OidcClientView saveOidcClient(@RequestBody OidcClientCommand command) {
        return service.saveOidcClient(currentActor(), command);
    }

    @GetMapping("/management/platform/applications/{applicationId}/resources")
    public List<ResourceView> resources(@PathVariable("applicationId") UUID applicationId) {
        return service.resources(currentActor(), applicationId);
    }

    @PostMapping("/management/platform/resources")
    public ResourceView createResource(@RequestBody ResourceCommand command) {
        return service.createResource(currentActor(), command);
    }

    @PutMapping("/management/platform/resources/{id}")
    public ResourceView updateResource(@PathVariable("id") UUID id, @RequestBody ResourceCommand command) {
        return service.updateResource(currentActor(), id, command);
    }

    @GetMapping("/management/platform/tenants")
    public List<TenantView> tenants() { return service.tenants(currentActor()); }

    @PostMapping("/management/platform/tenants")
    public TenantView createTenant(@RequestBody TenantCommand command) {
        return service.createTenant(currentActor(), command);
    }

    @PutMapping("/management/platform/tenants/{id}")
    public TenantView updateTenant(@PathVariable("id") UUID id, @RequestBody TenantCommand command) {
        return service.updateTenant(currentActor(), id, command);
    }

    @GetMapping("/management/platform/tenants/{id}/subscriptions")
    public List<SubscriptionView> subscriptions(@PathVariable("id") UUID id) {
        return service.subscriptions(currentActor(), id);
    }

    @PostMapping("/management/platform/tenants/{id}/subscriptions")
    public ResponseEntity<Void> subscribeTenant(@PathVariable("id") UUID id, @RequestBody SubscriptionCommand command) {
        service.subscribeTenant(currentActor(), id, command);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/management/platform/packages")
    public List<PackageView> packages() { return service.packages(currentActor()); }

    @PostMapping("/management/platform/packages")
    public PackageView createPackage(@RequestBody PackageCommand command) {
        return service.createPackage(currentActor(), command);
    }

    @PutMapping("/management/platform/packages/{id}")
    public PackageView updatePackage(@PathVariable("id") UUID id, @RequestBody PackageCommand command) {
        return service.updatePackage(currentActor(), id, command);
    }

    @GetMapping("/management/platform/packages/{id}/versions")
    public List<PackageVersionView> packageVersions(@PathVariable("id") UUID id) {
        return service.packageVersions(currentActor(), id);
    }

    @PostMapping("/management/platform/packages/{id}/versions")
    public PackageVersionView createPackageVersion(@PathVariable("id") UUID id,
                                                   @RequestBody PackageVersionCommand command) {
        return service.createPackageVersion(currentActor(), id, command);
    }

    @PostMapping("/management/platform/package-versions/{id}/publish")
    public PackageVersionView publishPackageVersion(@PathVariable("id") UUID id) {
        return service.publishPackageVersion(currentActor(), id);
    }

    @GetMapping("/management/tenant/organizations")
    public List<OrganizationView> organizations() { return service.organizations(currentActor()); }

    @PostMapping("/management/tenant/organizations")
    public OrganizationView createOrganization(@RequestBody OrganizationCommand command) {
        return service.createOrganization(currentActor(), command);
    }

    @PutMapping("/management/tenant/organizations/{id}")
    public OrganizationView updateOrganization(@PathVariable("id") UUID id, @RequestBody OrganizationCommand command) {
        return service.updateOrganization(currentActor(), id, command);
    }

    @GetMapping("/management/tenant/users")
    public List<UserView> users() { return service.users(currentActor()); }

    @PostMapping("/management/tenant/users")
    public UserView createUser(@RequestBody UserCommand command) { return service.createUser(currentActor(), command); }

    @PutMapping("/management/tenant/users/{id}")
    public UserView updateUser(@PathVariable("id") UUID id, @RequestBody UserCommand command) {
        return service.updateUser(currentActor(), id, command);
    }

    @PostMapping("/management/tenant/users/{id}/password-reset")
    public ResponseEntity<Void> resetUserPassword(@PathVariable("id") UUID id, @RequestBody PasswordResetCommand command) {
        service.resetUserPassword(currentActor(), id, command);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/management/tenant/roles")
    public List<RoleView> roles() { return service.roles(currentActor()); }

    @GetMapping("/management/tenant/grantable-resources")
    public List<ResourceView> grantableResources() { return service.grantableResources(currentActor()); }

    @GetMapping("/management/tenant/menus")
    public List<TenantMenuView> tenantMenus() { return service.tenantMenus(currentActor()); }

    @PutMapping("/management/tenant/menus/{resourceId}")
    public TenantMenuView saveTenantMenu(@PathVariable("resourceId") UUID resourceId,
                                         @RequestBody TenantMenuCommand command) {
        return service.saveTenantMenu(currentActor(), resourceId, command);
    }

    @GetMapping("/management/tenant/menu-groups")
    public List<TenantMenuGroupView> tenantMenuGroups() { return service.tenantMenuGroups(currentActor()); }

    @PostMapping("/management/tenant/menu-groups")
    public TenantMenuGroupView createTenantMenuGroup(@RequestBody TenantMenuGroupCommand command) {
        return service.createTenantMenuGroup(currentActor(), command);
    }

    @PutMapping("/management/tenant/menu-groups/{id}")
    public TenantMenuGroupView updateTenantMenuGroup(@PathVariable("id") UUID id,
                                                     @RequestBody TenantMenuGroupCommand command) {
        return service.updateTenantMenuGroup(currentActor(), id, command);
    }

    @PostMapping("/management/tenant/roles")
    public RoleView createRole(@RequestBody RoleCommand command) { return service.createRole(currentActor(), command); }

    @PutMapping("/management/tenant/roles/{id}")
    public RoleView updateRole(@PathVariable("id") UUID id, @RequestBody RoleCommand command) {
        return service.updateRole(currentActor(), id, command);
    }

    @GetMapping("/management/tenant/data-scopes")
    public List<DataScopeView> dataScopes() { return service.dataScopes(currentActor()); }

    @PostMapping("/management/tenant/data-scopes")
    public DataScopeView createDataScope(@RequestBody DataScopeCommand command) {
        return service.saveDataScope(currentActor(), null, command);
    }

    @PutMapping("/management/tenant/data-scopes/{id}")
    public DataScopeView updateDataScope(@PathVariable("id") UUID id, @RequestBody DataScopeCommand command) {
        return service.saveDataScope(currentActor(), id, command);
    }

    @GetMapping("/management/tenant/settings")
    public List<SettingView> settings() { return service.settings(currentActor()); }

    @PutMapping("/management/tenant/settings/{key}")
    public SettingView saveSetting(@PathVariable("key") String key, @RequestBody SettingCommand command) {
        return service.saveSetting(currentActor(), key, command);
    }

    @GetMapping("/management/platform/dictionary-types")
    public List<DictionaryTypeView> platformDictionaryTypes() {
        return service.dictionaryTypes(currentActor());
    }

    @PostMapping("/management/platform/dictionary-types")
    public DictionaryTypeView createPlatformDictionaryType(@RequestBody DictionaryTypeCommand command) {
        return service.createDictionaryType(currentActor(), command);
    }

    @PutMapping("/management/platform/dictionary-types/{id}")
    public DictionaryTypeView updatePlatformDictionaryType(@PathVariable("id") UUID id,
                                                           @RequestBody DictionaryTypeCommand command) {
        return service.updateDictionaryType(currentActor(), id, command);
    }

    @GetMapping("/management/platform/dictionary-types/{typeId}/items")
    public List<DictionaryItemView> platformDictionaryItems(@PathVariable("typeId") UUID typeId) {
        return service.dictionaryItems(currentActor(), typeId);
    }

    @PostMapping("/management/platform/dictionary-items")
    public DictionaryItemView createPlatformDictionaryItem(@RequestBody DictionaryItemCommand command) {
        return service.createDictionaryItem(currentActor(), command);
    }

    @PutMapping("/management/platform/dictionary-items/{id}")
    public DictionaryItemView updatePlatformDictionaryItem(@PathVariable("id") UUID id,
                                                           @RequestBody DictionaryItemCommand command) {
        return service.updateDictionaryItem(currentActor(), id, command);
    }

    @GetMapping("/management/tenant/dictionary-types")
    public List<DictionaryTypeView> tenantDictionaryTypes() {
        return service.dictionaryTypes(currentActor());
    }

    @PostMapping("/management/tenant/dictionary-types")
    public DictionaryTypeView createTenantDictionaryType(@RequestBody DictionaryTypeCommand command) {
        return service.createDictionaryType(currentActor(), command);
    }

    @PutMapping("/management/tenant/dictionary-types/{id}")
    public DictionaryTypeView updateTenantDictionaryType(@PathVariable("id") UUID id,
                                                         @RequestBody DictionaryTypeCommand command) {
        return service.updateDictionaryType(currentActor(), id, command);
    }

    @GetMapping("/management/tenant/dictionary-types/{typeId}/items")
    public List<DictionaryItemView> tenantDictionaryItems(@PathVariable("typeId") UUID typeId) {
        return service.dictionaryItems(currentActor(), typeId);
    }

    @PostMapping("/management/tenant/dictionary-items")
    public DictionaryItemView createTenantDictionaryItem(@RequestBody DictionaryItemCommand command) {
        return service.createDictionaryItem(currentActor(), command);
    }

    @PutMapping("/management/tenant/dictionary-items/{id}")
    public DictionaryItemView updateTenantDictionaryItem(@PathVariable("id") UUID id,
                                                         @RequestBody DictionaryItemCommand command) {
        return service.updateDictionaryItem(currentActor(), id, command);
    }

    @GetMapping("/management/audits")
    public List<AuditView> audits(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return service.audits(currentActor(), limit);
    }

    private static Actor currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Access token is required");
        }
        var jwt = jwtAuthentication.getToken();
        String scope = jwt.getClaimAsString("principalScope");
        UUID principalId = UUID.fromString(jwt.getClaimAsString("principalId"));
        String tenant = jwt.getClaimAsString("tenantId");
        return new Actor(scope, principalId, tenant == null ? null : UUID.fromString(tenant));
    }
}
