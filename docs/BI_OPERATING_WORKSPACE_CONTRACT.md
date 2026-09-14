# BI Operating Workspace Integration

## Ownership

New files only. No IndexView/cockpit-model, existing query/governance/report mapper, or IAM migration changes. Targets write V9 `bi_business_target`; current target-completion SQL already reads it directly, so parent should reload dashboard after save, without source ETL. V10 adds actions and immutable revision events. No Order/ERP/HR money or workflow status writes.

## HTTP (Gateway prefixes /api/v1)

- GET `/analytics/supply/dashboard/targets?month=2026-09&dimensionType=CITY&dimensionCode=BJ`
- PUT `/analytics/supply/dashboard/targets`: `{month,dimensionType,dimensionCode,dimensionName,metricCode,targetValue,remark,expectedRevision}`. New target version 0; existing target version from GET. Metrics: SALES_AMOUNT/PAID_AMOUNT/CONTACTED_CUSTOMER/COOPERATED_CUSTOMER. Values 0..999999999999.99, at most two decimals; customer counts integer. PUT returns saved target/revision.
- DELETE `/analytics/supply/dashboard/targets/{id}?revision=N` soft-deletes with revision event; duplicate/stale write HTTP409. Creating the same key after deletion restores a new revision, never overwrites active target via version0.
- GET `/analytics/supply/dashboard/actions?kind&businessRef&cityCode&employeeCode&assignee&status&page&pageSize` returns `{items,total,page,pageSize}`.
- POST `/analytics/supply/dashboard/actions`: `{kind,businessRef,businessLabel,cityCode,employeeCode,assignee,dueAt,note}`. kind COLLECTION/CUSTOMER/STOCK, canonical businessRef supplied from real drilldown context, assignee is responsible employee code, dueAt ISO UTC. Returns persisted action at OPEN/revision1.
- PUT `/analytics/supply/dashboard/actions/{id}`: `{assignee,dueAt,status,note,expectedRevision}`. OPEN -> IN_PROGRESS/DISMISSED; IN_PROGRESS -> RESOLVED/DISMISSED; RESOLVED/DISMISSED -> OPEN (reopen); active state may remain unchanged for notes/reassignment. Every change requires a note. Direct OPEN -> RESOLVED rejected.
- GET `/analytics/supply/dashboard/actions/{id}/events`: immutable revision history including previous/current status, assignee, deadline, actor/time and note. Latest revision first.

All endpoints require `analytics:dashboard:read`. Writes additionally require `analytics:targets:write` or `analytics:operations:write`. Main owns IAM permission resources and explicit grants. Tenant user only; no SERVICE/PLATFORM mutation. Shared `BiDataScopeService` validates lists and objects in application layer. SELF cannot edit city totals. Owner targets require actual BI city association; cross-city owner changes require every associated city to be authorized. New HR employees without BI city/sales association require verified projection, not free-form invented IDs. Stock tasks remain governance-only until warehouse scope is implemented.

Business references: CUSTOMER/COLLECTION accept `customer-code:<row.customerCode>` (unprefixed code also uses the exact BI customer-code-or-ID fallback). Backend resolves and persists canonical `customer-id:<actual customer_id>`. COLLECTION can also use `order-id:<actual order_id>`. STOCK uses `product-code:<actual product_code>` and requires an existing inventory fact. Input names/city/employee are not trusted: persisted values come from real tenant BI subjects. Missing or ambiguous subjects fail; assignee must have verified BI owner/city association. Current subject access is checked again for event reads and updates. `employeeCode` and `assignee` use the same canonical owner-staff/employee code as BI facts and sales-owner filter options, not IAM user ID. No free-text responsibility names are persisted.

Restricted list queries always exclude stock followups; CITY target lists exclude sales-owner totals spanning outside the selected city. Records are operational ownership snapshots, not a reassignment API; changed source ownership is revalidated before showing events or updating a task.

Explicit assignment grants the assigned SELF user access to this followup within their authorized city (list uses owner OR assignee). It does not grant underlying customer/order/financial API access. Managers may assign another verified employee in their permitted scope; SELF cannot assign outside their own scope. Current subject city is rechecked for followup history/mutation, preventing stale assignment from crossing city permissions.

## Portal Mount (owned component)

`src/views/supply-chain/bi/components/BiOperationsWorkbench.vue` embedded by parent in an existing drawer, never added as overview form/card.

Props: `initialTab: 'actions' | 'targets'` (default actions), `month: YYYY-MM`, `regionCode?: string`, `ownerStaffCode?: string`, `actionSeed?: {kind,businessRef,businessLabel,cityCode?,employeeCode?,assignee?,note?}`, `regions?: {value,label}[]`, `salesOwners?: {value,label}[]`.

Emits: `changed: {type:'targets'|'actions'}` after successful persistence only; `open-business: ActionView` for parent-owned safe business navigation. Component exposes `reload()`. Parent supplies real dimension option labels and canonical codes (or component reads existing BI filter options). Seed changes open action editor. Without seed, workbench lists persisted actions, not fabricated daily tasks. Parent can pass customer/order/stock exception context without rendering internal IDs in user labels.

On target `changed`, refresh completion/current dashboard; on action `changed`, refresh action counters if used. Do not interpret RESOLVED as financial collection or stock fulfilment. Runtime: deploy V9 then V10, restart BI, grant write permissions, and provide verified role scope projection via scope owner. Tests do not constitute shared DEV migrations or real operational data acceptance.

## Owned Files

Platform prefix: `services/rigour-analytics-bi-service/`.

- `analytics-bi-api/src/main/java/com/rigour/analytics/api/v1/AnalyticsOperatingWorkspaceApi.java`
- `analytics-bi-api/src/main/java/com/rigour/analytics/api/v1/model/OperatingWorkspaceModels.java`
- `analytics-bi-service/src/main/java/com/rigour/analytics/api/controller/AnalyticsOperatingWorkspaceController.java`
- `analytics-bi-service/src/main/java/com/rigour/analytics/application/service/OperatingWorkspaceService.java`
- `analytics-bi-service/src/main/java/com/rigour/analytics/application/port/out/OperatingWorkspaceStore.java`
- `analytics-bi-service/src/main/java/com/rigour/analytics/infrastructure/persistence/mapper/OperatingWorkspaceMapper.java`
- `analytics-bi-service/src/main/java/com/rigour/analytics/infrastructure/persistence/repository/MybatisOperatingWorkspaceRepository.java`
- `analytics-bi-service/src/main/resources/db/migration/V10__bi_operating_actions.sql`
- `analytics-bi-service/src/test/java/com/rigour/analytics/application/service/OperatingWorkspaceServiceTest.java`
- `analytics-bi-service/src/test/java/com/rigour/analytics/api/controller/OperatingWorkspaceApiTest.java`
- `analytics-bi-service/src/test/java/com/rigour/analytics/infrastructure/persistence/repository/OperatingWorkspaceRepositoryTest.java`
- `docs/BI_OPERATING_WORKSPACE_CONTRACT.md` (Platform root, this file)

Portal:

- `src/api/core/bi-operations.ts`
- `src/views/supply-chain/bi/components/BiOperationsWorkbench.vue`
- `tests/bi-operations-api.test.ts`
- `tests/bi-operations-workbench.test.ts`

All owned files are new and staged. Existing dirty files and other agents' files are untouched. Parent owns drawer mount, dashboard refresh, domain navigation, IAM resources and runtime acceptance.

## Verification (2026-09-12)

- Backend owned suites: OperatingWorkspaceServiceTest (15), OperatingWorkspaceRepositoryTest (7), OperatingWorkspaceApiTest (2): 24 passed, no skips. Repository tests execute real MyBatis SQL against the V9 target and V10 action/event schemas on isolated H2, including audit transaction rollback. H2 engine/collation clauses differ from MySQL; shared DEV migration is not claimed.
- Portal: `pnpm exec vitest run tests/bi-operations-api.test.ts tests/bi-operations-workbench.test.ts`: 9 passed. Scoped ESLint, `pnpm typecheck`, `pnpm build` passed; build retains existing large-chunk/dependency-comment warnings.
- Broader `./mvnw -pl services/rigour-analytics-bi-service/analytics-bi-service -am verify` was attempted but other concurrently edited comparison/scope/existing-analysis tests failed. Parent is coordinating final serial Maven validation; this document does not claim whole-module or whole-repository acceptance.
- Required runtime checks: apply V10 after V9, include shared scope migration/configuration, restart BI, explicitly grant read/write permissions, verify authorized month target editing and 409 conflict handling, register a real non-financial followup and verify its persisted history and scoped visibility in the authenticated drawer. No shared DEV business records were created for this subtask.
