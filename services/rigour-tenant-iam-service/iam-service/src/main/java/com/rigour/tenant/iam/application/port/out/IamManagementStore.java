package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.service.management.ManagementModels.*;
import java.util.List;
import java.util.UUID;

/** IAM管理控制台持久化端口；实现必须在数据库访问层强制租户和权限边界。 */
public interface IamManagementStore {
    List<ApplicationView> applications(Actor actor);
    ApplicationView createApplication(Actor actor, ApplicationCommand command);
    ApplicationView updateApplication(Actor actor, UUID id, ApplicationCommand command);
    List<OidcClientView> oidcClients(Actor actor);
    OidcClientView saveOidcClient(Actor actor, OidcClientCommand command);
    List<ResourceView> resources(Actor actor, UUID applicationId);
    ResourceView createResource(Actor actor, ResourceCommand command);
    ResourceView updateResource(Actor actor, UUID id, ResourceCommand command);
    List<NavigationNode> navigation(Actor actor, String applicationCode);
    List<TenantView> tenants(Actor actor);
    TenantView createTenant(Actor actor, TenantCommand command);
    TenantView updateTenant(Actor actor, UUID id, TenantCommand command);
    List<SubscriptionView> subscriptions(Actor actor, UUID tenantId);
    void subscribeTenant(Actor actor, UUID tenantId, SubscriptionCommand command);
    List<PackageView> packages(Actor actor);
    PackageView createPackage(Actor actor, PackageCommand command);
    PackageView updatePackage(Actor actor, UUID id, PackageCommand command);
    List<PackageVersionView> packageVersions(Actor actor, UUID packageId);
    PackageVersionView createPackageVersion(Actor actor, UUID packageId, PackageVersionCommand command);
    PackageVersionView publishPackageVersion(Actor actor, UUID id);
    List<OrganizationView> organizations(Actor actor);
    OrganizationView createOrganization(Actor actor, OrganizationCommand command);
    OrganizationView updateOrganization(Actor actor, UUID id, OrganizationCommand command);
    List<UserView> users(Actor actor);
    UserView createUser(Actor actor, UserCommand command);
    UserView updateUser(Actor actor, UUID id, UserCommand command);
    void resetUserPassword(Actor actor, UUID id, PasswordResetCommand command);
    List<RoleView> roles(Actor actor);
    List<ResourceView> grantableResources(Actor actor);
    RoleView createRole(Actor actor, RoleCommand command);
    RoleView updateRole(Actor actor, UUID id, RoleCommand command);
    List<DataScopeView> dataScopes(Actor actor);
    DataScopeView saveDataScope(Actor actor, UUID id, DataScopeCommand command);
    List<SettingView> settings(Actor actor);
    SettingView saveSetting(Actor actor, String key, SettingCommand command);
    List<DictionaryTypeView> dictionaryTypes(Actor actor);
    DictionaryTypeView createDictionaryType(Actor actor, DictionaryTypeCommand command);
    DictionaryTypeView updateDictionaryType(Actor actor, UUID id, DictionaryTypeCommand command);
    List<DictionaryItemView> dictionaryItems(Actor actor, UUID typeId);
    DictionaryItemView createDictionaryItem(Actor actor, DictionaryItemCommand command);
    DictionaryItemView updateDictionaryItem(Actor actor, UUID id, DictionaryItemCommand command);
    List<AuditView> audits(Actor actor, int limit);
}
