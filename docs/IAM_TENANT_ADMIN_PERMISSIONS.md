# Tenant Administrator Effective Permissions

## Contract

- `GET /api/v1/management/tenant/role-permissions/roles` returns `permissionMode: ALL_ENTITLED | EXPLICIT` on each role.
- Only the established `SYSTEM` role `TENANT_SUPER_ADMIN` uses `ALL_ENTITLED`. A custom role with the same code is not an administrator. Legacy role creation rejects that reserved code.
- Its `resourceIds` are the currently grantable tenant resource catalog, respecting tenant menu visibility. Saving a system role is rejected server-side.
- Ordinary roles keep explicit resource grants and can be edited through the existing role-permissions API. No user/role assignments are added automatically.
- Existing platform `SUPER_ADMIN` behavior remains unchanged; tenant administrators never acquire its wildcard or platform applications.

## Persistence And Runtime

V86 is additive. Do not modify applied V85.

1. Restore `order:write`, which current first-party order/shipment/payment/refund services require but V16 removed with obsolete DHB order sync. Bind to the established standard package only where the current sales-order page is already entitled. Custom package entitlements are not silently expanded.
2. Attach orphaned `ORDER_READ` to the current sales-order page. Correct ERP supply read/write resources from the incorrectly assigned sales-publishing API to the ERP menu. Parent lookup uses resource codes, not display names.
3. Create `iam_effective_tenant_role_resource`. It selects only active, undeleted tenant/role/resource/TENANT application and current, undeleted ACTIVE/SCHEDULED subscription package resources. System administrators receive that catalog dynamically; other roles additionally require an active explicit grant.
4. Persist currently entitled resources to existing active tenant administrator `iam_role_resource` rows. Preserve ordinary roles. New resources later added to a legally entitled package are effective automatically, without a per-user grant or another administrator grant migration.
5. Insert missing MENU/PAGE tenant configurations from existing UI visibility within current entitlements. Preserve all existing visibility, label and grouping overrides, including explicit hidden menus. Do not restore retired placeholder screens or service-only sync permissions.

Runtime consumers use the same effective view: `JdbcPortalAccessReader` (`/me`, applications, `/token/current`), tenant navigation and permission checks in `JdbcIamManagementStore`, and role/staff MyBatis permission checks. Active user/role membership remains mandatory. Revoking subscriptions, grants, users, roles, resources or applications removes access on the next read even if a persisted administrator baseline row remains.

JWT carries validated identity/session/version, not the effective permissions. Gateway calls IAM `/token/current` and signs the returned live permissions for downstream services. `/me` and Gateway consequently share the same current source rather than separate permission snapshots. This does not bypass BI employee/city data-scope guards.

V87 moves the existing `integration:dhb:read` and `integration:dhb:write` resource IDs from the disabled legacy application to the active supply-chain sync-center page. It preserves package membership and ordinary grants, backfills entitled administrator grants, and keeps the legacy application disabled. Four existing Order/ERP/CRM resource labels now describe their business read/write capabilities, not only historical synchronization. Applied V85/V86 are unchanged.

The administrator contract includes business writes, not only menu access and reads. Service-only dictionary/CRM/ERP projection writes, connector leases and cross-tenant discovery remain service identities' permissions; granting them to human administrators would not make those endpoints valid human operations.

## Verification And Deployment

- New tests: `TenantAdministratorEffectivePermissionsTest` (real V86 view/backfill/menu SQL, live reader, current-token contract, role/staff MyBatis guard SQL) and `MybatisPlusIamRolePermissionStoreTest` (permission mode, server read-only, ordinary explicit save and entitlement rejection).
- H2 test fixtures replace only MySQL UTC time expressions; these are SQL regression tests, not proof of MySQL migration or authenticated browser acceptance.
- Run tests and root verification serially in the main task. This worker did not run Maven.
- V86 requires the existing Flyway migrator to have `CREATE VIEW` as well as its normal DML rights. Verify grants read-only before restart; do not silently grant database privileges.
- After deploying V86 and restarting IAM, verify persisted administrator grants, `/me`, `/token/current`, navigation, role-permissions and staff endpoints. Preserve the ordinary-role database baseline; use automated tests when no real ordinary role is configured rather than claiming live verification.

## Shared DEV Acceptance, 2026-09-12

- Root `./mvnw verify` passed after V87, including eight `DhbSyncPermissionMigrationTest` cases. Docker-dependent integration tests were skipped, not passed.
- IAM restarted with the existing IDEA dev/local configuration. Flyway V86 and V87 succeeded; health returned UP.
- Read-only database comparison found 81 effective permission codes across 276 effective resources. The 280 persisted administrator grants include historical resources that remain ineffective. Missing menu configurations and broken resource parents were both zero; the ordinary-grant baseline was unchanged.
- Backend main-source permission-literal comparison found no remaining human-facing permission gap after distinguishing service-only guards and exception-reason strings. This is not a claim that every mutation endpoint was executed against business data.
- Authenticated Portal role configuration displayed automatic full permissions. The two DHB permissions and corrected ERP supply write label were present and selected. The UI exposed 165 currently visible grantable resources; that UI count intentionally differs from the full effective-resource count.
