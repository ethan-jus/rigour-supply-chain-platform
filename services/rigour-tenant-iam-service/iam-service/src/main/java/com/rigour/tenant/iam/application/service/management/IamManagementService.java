package com.rigour.tenant.iam.application.service.management;

import com.rigour.tenant.iam.application.port.out.IamManagementStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.*;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 管理控制台用例入口；协议层不直接依赖JDBC。 */
public final class IamManagementService {
    private final IamManagementStore store;

    public IamManagementService(IamManagementStore store) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
    }

    public List<ApplicationView> applications(Actor actor) { return store.applications(actor); }
    public ApplicationView createApplication(Actor actor, ApplicationCommand command) { return store.createApplication(actor, command); }
    public ApplicationView updateApplication(Actor actor, UUID id, ApplicationCommand command) { return store.updateApplication(actor, id, command); }
    public List<OidcClientView> oidcClients(Actor actor) { return store.oidcClients(actor); }
    public OidcClientView saveOidcClient(Actor actor, OidcClientCommand command) { return store.saveOidcClient(actor, command); }
    public List<ResourceView> resources(Actor actor, UUID applicationId) { return store.resources(actor, applicationId); }
    public ResourceView createResource(Actor actor, ResourceCommand command) { return store.createResource(actor, command); }
    public ResourceView updateResource(Actor actor, UUID id, ResourceCommand command) { return store.updateResource(actor, id, command); }
    public List<NavigationNode> navigation(Actor actor, String applicationCode) { return store.navigation(actor, applicationCode); }
    public List<TenantView> tenants(Actor actor) { return store.tenants(actor); }
    public TenantView createTenant(Actor actor, TenantCommand command) { return store.createTenant(actor, command); }
    public TenantView updateTenant(Actor actor, UUID id, TenantCommand command) { return store.updateTenant(actor, id, command); }
    public List<SubscriptionView> subscriptions(Actor actor, UUID tenantId) {
        return store.subscriptions(actor, tenantId);
    }
    public void subscribeTenant(Actor actor, UUID id, SubscriptionCommand command) { store.subscribeTenant(actor, id, command); }
    public List<PackageView> packages(Actor actor) { return store.packages(actor); }
    public PackageView createPackage(Actor actor, PackageCommand command) { return store.createPackage(actor, command); }
    public PackageView updatePackage(Actor actor, UUID id, PackageCommand command) { return store.updatePackage(actor, id, command); }
    public List<PackageVersionView> packageVersions(Actor actor, UUID packageId) { return store.packageVersions(actor, packageId); }
    public PackageVersionView createPackageVersion(Actor actor, UUID packageId, PackageVersionCommand command) {
        return store.createPackageVersion(actor, packageId, command);
    }
    public PackageVersionView publishPackageVersion(Actor actor, UUID id) { return store.publishPackageVersion(actor, id); }
    public List<OrganizationView> organizations(Actor actor) { return store.organizations(actor); }
    public OrganizationView createOrganization(Actor actor, OrganizationCommand command) { return store.createOrganization(actor, command); }
    public OrganizationView updateOrganization(Actor actor, UUID id, OrganizationCommand command) { return store.updateOrganization(actor, id, command); }
    public List<UserView> users(Actor actor) { return store.users(actor); }
    public UserView createUser(Actor actor, UserCommand command) { return store.createUser(actor, command); }
    public UserView updateUser(Actor actor, UUID id, UserCommand command) { return store.updateUser(actor, id, command); }
    public void resetUserPassword(Actor actor, UUID id, PasswordResetCommand command) {
        store.resetUserPassword(actor, id, command);
    }
    public List<RoleView> roles(Actor actor) { return store.roles(actor); }
    public List<ResourceView> grantableResources(Actor actor) { return store.grantableResources(actor); }
    public RoleView createRole(Actor actor, RoleCommand command) { return store.createRole(actor, command); }
    public RoleView updateRole(Actor actor, UUID id, RoleCommand command) { return store.updateRole(actor, id, command); }
    public List<DataScopeView> dataScopes(Actor actor) { return store.dataScopes(actor); }
    public DataScopeView saveDataScope(Actor actor, UUID id, DataScopeCommand command) { return store.saveDataScope(actor, id, command); }
    public List<SettingView> settings(Actor actor) { return store.settings(actor); }
    public SettingView saveSetting(Actor actor, String key, SettingCommand command) { return store.saveSetting(actor, key, command); }
    public List<DictionaryTypeView> dictionaryTypes(Actor actor) { return store.dictionaryTypes(actor); }
    public DictionaryTypeView createDictionaryType(Actor actor, DictionaryTypeCommand command) {
        return store.createDictionaryType(actor, command);
    }
    public DictionaryTypeView updateDictionaryType(Actor actor, UUID id, DictionaryTypeCommand command) {
        return store.updateDictionaryType(actor, id, command);
    }
    public List<DictionaryItemView> dictionaryItems(Actor actor, UUID typeId) {
        return store.dictionaryItems(actor, typeId);
    }
    public DictionaryItemView createDictionaryItem(Actor actor, DictionaryItemCommand command) {
        return store.createDictionaryItem(actor, command);
    }
    public DictionaryItemView updateDictionaryItem(Actor actor, UUID id, DictionaryItemCommand command) {
        return store.updateDictionaryItem(actor, id, command);
    }
    public List<AuditView> audits(Actor actor, int limit) { return store.audits(actor, limit); }
}
