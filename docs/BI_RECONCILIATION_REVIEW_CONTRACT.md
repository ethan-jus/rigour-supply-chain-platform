# Feishu Reconciliation Review

## Integration Contract

- Portal component: `src/views/supply-chain/bi/components/BiReconciliationCenter.vue`; optional `from` and `to` ISO instant props. Main mounts this component; no IndexView edits belong to this implementation.
- API: `/api/v1/analytics/reconciliation-reviews`.
- `POST`: `{ batchId?, onlineCaptureId?, previousBatchId?, from, to, sourceExportedAt?, sourceDeclaredComplete }`. Exactly one of `batchId` and `onlineCaptureId` is required. Server loads actual saved source, business and BI facts. Clients do not submit calculated financial facts. The existing six-argument Java `Command` constructor remains supported.
- `GET`: most recent 30 local reviews created by this actor.
- `GET /{id}`: `kind=ORDER|SKU`, `status`, `city`, `sales`, `orderNo`, `keyword`, `page=1`, `pageSize=50` (max 100). Filtering precedes paging; summary remains the complete selected city/sales/order cohort, not just a page.
- Both paths require `BiDataScopeService.requireGlobalGovernance()` and `analytics:dashboard:read`. POST also requires `analytics:reconciliation:write`; IAM resource registration is owned by main. Saved reviews are additionally isolated by tenant and creator.
- Files use existing Integration `batch-preflight`; online reads use Integration's read-only capture, then pass its `id` as `onlineCaptureId`. BI itself neither fetches Feishu nor accesses credentials. Neither reconciliation path invokes import runs, replay, attachment compensation or Order writes.

## Data and Status Semantics

- `sourceVersion` records batch, filename, SHA-256, source URL if supplied, upload time, import status, raw count. Upload time is not an online modification watermark. `sourceExportedAt` is explicitly a user's declaration, not independently verified evidence.
- File `onlineStatus` remains `UNVERIFIED`; successful online evidence is `CAPTURED_NON_ATOMIC`. `SNAPSHOT_MATCH` means only the selected captured source and business/BI rows matched for supported fields. `ONLINE_MATCH` is never produced. Complete pagination is not a cross-page atomic snapshot and never certifies current or continuously latest Feishu data.
- `UNVERIFIED`: source absent, missing fields, incomplete export declaration, unresolved SKU or differing time scope. Unknown source amounts stay null, while a present legitimate numeric zero is compared normally.
- `DIFF`: supported comparable fields differ, or business and BI differ even if the external source is unknown. Missing records in an incomplete or unresolved source are not labeled as lost imports.
- `STALE`: source upload/declaration is over six hours old at capture. Uploaded files cannot independently prove online freshness even when recently uploaded.
- `EXCLUDED_REFUND`: explicitly zero-quantity refund/reversal orders are excluded according to the confirmed business policy. Their Raw amounts, including historical nonzero receipts, remain visible as audit evidence, not a missing-collection claim. No restore or removal action is offered.
- Version changes compare selected evidence, including online capture versus an older file, not live deletion or creation. A previous file must predate online capture start. Historical changes never trigger data restoration; missing records in filtered/incomplete evidence never become deletion claims.
- Capture contains FEISHU nondeleted, noncancelled business and BI orders. The same source-order union cohort is used for all layers: an order is included when any layer's business date is in the selected inclusive period. Cross-layer date membership differences are explicitly unverified. SKU follows the parent order cohort.
- Money uses original decimals with 0.01 yuan tolerance; UI rounds two decimals without modifying evidence. Order receipts are cumulative, not interval cashflow. SKU receipts are not allocated. Source units are kept separate and not silently converted using packaging assumptions.
- SKU matching prioritizes traceable source product records/codes or explicit SKU identity among captured order facts. Exact unique product/specification names, including Feishu descriptor syntax, locate candidates only; they do not certify an imported ERP association. Ambiguous/missing associations stay unverified. Repeated SKU lines are aggregated only for confirmed compatible units; money remains separate from quantity comparability. Unbound system lines retain their own original line identity instead of collapsing into a null-SKU group.
- ERP category/brand migration correctness is not certified by this review; category trees and real descendant filters are separately owned by Zeno. Nothing hardcodes the screenshot's category count.

## Unit and Association Evidence (2026-09-14)

- `Fact.unitEvidence`: `EXPLICIT` means a source line explicitly supplies a unit; `COLUMN_INFERRED` means only the selected quantity column implies boxes; `SYSTEM` means the stored Order/BI unit, not proof of the historical source transaction; `UNKNOWN`/`MIXED` cannot certify quantity conversion. A generic quantity field cannot borrow a different column's box label. ERP default units are not silently substituted for source transaction units.
- `Fact.associationEvidence`: `SOURCE_RECORD`/`SOURCE_CODE` represent unique captured reference/code matches, `EXACT_NAME_SPEC` is only a review candidate, `SYSTEM` is the stored product/SKU binding, and `UNLINKED`/`MIXED` remain unverified. Optional source record/product IDs and product codes retain traceability, not extra business report identifiers.
- `Row.financialStatus`, `quantityStatus`, and `associationStatus` are independently evaluated using `SNAPSHOT_MATCH`, `DIFF`, `UNVERIFIED`, or `EXCLUDED_REFUND`. Order-header quantity is `NOT_APPLICABLE` because mixed units cannot be treated as a verified sales volume. Row total status still includes child SKU uncertainty and source scope/freshness.
- Explicit financial differences remain visible even when units are unknown. Header money can match while children need unit/association review. Name-only candidates cannot certify SKU money or quantity comparisons. Inferred units do not produce a proven box/bucket mismatch.
- Saved historical JSON has null evidence/subcheck fields and remains readable. UI must label those fields not separately assessed, not derive them from the historical total status. Source-version change detection still compares raw quantities/units and values even when their business interpretation is unverified.
- No reconciliation endpoint changes an order, payment or ERP master. A confirmed repair is owned by Order and requires its separate preview/confirmation authorization. The 15 zero-quantity refunded source orders remain excluded under the previously confirmed business rule.

## Storage and Execution Boundary

`V11__bi_reconciliation_reviews.sql` adds a local immutable review record. Source/body extraction happens once in explicit POST capture under repeatable-read isolation, with tenant predicates and bounded reads. This is an explicit reconciliation capture job, not the normal dashboard read path. It is currently a synchronous bounded job; the maximum source order/line capture is 20,000 rows and each business/BI grain is independently bounded. Overflow fails instead of saving a partially passing snapshot.

Normal review GET/history requests read only `bi_reconciliation_review`; they never rerun cross-database reconciliation queries. The existing `source_batch_id` column stores the source identity, either the file batch ID or online capture ID; the immutable JSON distinguishes these without rewriting V11 or historical data. Existing legacy summary SQL is not migrated wholesale by this change. Its Governance result no longer calls missing/noncomparable Raw data PASS or calculates misleading source differences from zero sentinels; it directs source comparison to the versioned review center.

No migration, shared-DEV capture, historical business write, actual import or online API request is run as part of implementation. Production acceptance requires applying V11, assigning the write permission, restarting BI and exercising the real authenticated flow.

## Test Entry Points

- Backend: `BiReconciliationReviewServiceTest`, `BiReconciliationSourceNormalizerTest`, `BiReconciliationReviewRepositoryTest`, `BiReconciliationOnlineReviewServiceTest`, `BiReconciliationOnlineCaptureRepositoryTest`.
- Backend targeted command: `./mvnw -pl services/rg-scdp-bi/bi-server -am test -Dtest=BiReconciliationReviewServiceTest,BiReconciliationSourceNormalizerTest,BiReconciliationReviewRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false`.
- Portal: `pnpm exec vitest run tests/bi-reconciliation-center.test.ts tests/bi-reconciliation-review-api.test.ts`.
- Main coordinates all Maven invocations serially because other agents share `target` directories.

## Source Schema Audit (2026-09-12)

The capture mapper was checked against repository migrations and current domain code, not an invented H2 schema. This verifies the code contract, not the migration state or contents of a running database. No capture SQL change was needed for the suspected dictionary column mismatch.

- Settings owns the global dictionary and exposes `/api/v1/business-settings/dictionaries/resolve?dictionaryCode=PRODUCT_UNIT` for historical value resolution. BI must read this API, never JOIN Settings tables. An earlier source-schema audit verified column names but missed this service-boundary restriction; the two reconciliation cross-database dictionary joins have now been removed.
- ERP `InternalProductEntity`/`InternalProductVariantEntity`, Order line unit codes and Portal ERP/BI presentation use `PRODUCT_UNIT`. Capture reads the real complete mapping once, with the current tenant and a signed SERVICE identity holding only `business-settings:dict:read`; it does not invoke the write-oriented dictionary batch/sync client. Settings base URL follows existing `rigour.business-settings.base-url`, with `RIGOUR_BUSINESS_SETTINGS_BASE_URL`/localhost:26892 fallback.
- New `Fact.rawUnit` retains source unit text or original Order/BI code, `Fact.unitCode` retains its uniquely resolved dictionary code, and `Fact.unit` supplies the real dictionary label for business display. The old Fact constructor and saved JSON remain readable. Comparisons and merges use resolved codes, so two codes with the same label cannot falsely match. Source names resolve only by exact unique dictionary label, never by packaging assumptions or quantities. Unknown/ambiguous units retain raw text and a visible unverified reason; identical unknown strings do not constitute a matching review. Box and bucket remain different units, even when financial totals agree.
- Missing/unavailable/malformed PRODUCT_UNIT responses abort capture with HTTP 503 and `details[].reason=BI_PRODUCT_UNIT_DICTIONARY_UNAVAILABLE`; messages contain no upstream SQL/account/body. No extra database privilege or import is needed. GET/history reads saved labels and evidence only, so subsequent dictionary edits cannot change historical review results. Service manual construction must provide the explicit dictionary port; there is no test-only production fallback.
- Integration V18 defines the batch/raw fields used by `version`/`sourceRows`, including binary UUID tenant/batch keys, `created_at`, `row_json`, `raw_row_hash`, `projection_status` and `error_code`. Later dedup migrations add fields without renaming these. `created_at` remains upload time, not online modification time.
- Order V16 defines header/line amounts, quantities, unit and SKU snapshots, dates and deletion flags; V23 adds `source_system_code`/`source_order_no`; V30 renames the employee-name snapshot to the current `owner_employee_name_snapshot`. V31 permits incomplete line values, which remain unknown rather than guessed.
- CRM V6 defines the VARCHAR tenant/internal customer ID fields; V13 supplies `owner_employee_name_snapshot`. CRM area V1 defines BINARY tenant and the unique `(tenant_id, area_code)` key, preserved through V3/V12. The mapper's `UUID_TO_BIN` area lookup and customer primary-key lookup each match at most one row.
- BI V2/V5 define the header/line facts, including the deliberately retained `owner_staff_name` projection name; V6 adds `region_name`. Capture uses those local names, not the renamed Order/CRM employee column names.

## Online Evidence Contract (2026-09-14)

`Version` adds nullable `onlineEvidence`; its old seven-argument constructor and saved JSON without that field remain readable. For online versions `batchId` is null, `fileName` is the configured source display name, `uploadedAt` is retained as a compatibility timestamp equal to capture completion (not a file upload or source-modification time), and `importStatus` is `CAPTURED_NON_ATOMIC`. Use explicit evidence for UI provenance:

```json
{
  "captureId": "UUID",
  "sourceId": "configured-source-id",
  "sourceName": "source display name",
  "sourceUrl": "configured Feishu Base URL",
  "startedAt": "2026-09-14T01:00:00Z",
  "completedAt": "2026-09-14T01:01:00Z",
  "complete": true,
  "filtered": false,
  "pageCount": 3,
  "recordCount": 3,
  "atomic": false
}
```

- Only explicit POST reads `rigour_integration.integration_feishu_online_capture`, using current authenticated `tenant_id` and capture ID, `complete=TRUE`, and non-null `completed_at`. UTC DATETIME6 is decoded as UTC, not JVM local time. BI saves its own immutable review, isolated additionally by creator; GET/history never reads Integration.
- Missing database SELECT permission at this online-source read boundary returns HTTP 503, standard `code=SERVICE_UNAVAILABLE`, and stable `details[].reason=BI_ONLINE_SOURCE_READ_NOT_PROVISIONED`. The safe message asks an administrator to provision minimal source read access and retry the existing capture; it does not request write privileges or another Order import. SQL, table/account details and nested database exceptions are not returned. MySQL 1142/1143 with SQLSTATE 42000 are recognized only at this specific SELECT boundary; syntax errors, connectivity failures, missing tables and legacy file reads are not mislabeled. Failure happens before business reads or review persistence.
- Payload shape: `{tables:[{tableId,tableCode,viewId?,rows:[{recordId,fields,createdTime?,lastModifiedTime?}]}]}`. ORDER and LINE are mandatory and must be non-empty, with unique table codes/IDs and unique record IDs per table. Optional third table `FEISHU_PRODUCT` supplies source product lookup. `recordCount` includes all captured tables, including products. Empty/missing fields, missing/duplicate identities, missing tables, count mismatches or partial-table evidence fail before saving a review.
- Upper bound is 20,000 total source rows and 32MiB UTF-8 payload. SQL avoids transferring oversized LONGTEXT; BI verifies size before parsing and recomputes SHA-256 from the exact stored payload string. Invalid JSON, digest mismatch or overflow fails without creating a partially matching review. Integration canonicalizes the stored JSON; BI does not reorder arrays or reserialize before hashing.
- `complete` means persisted pagination completion, not absence of filters. `filtered` is the logical OR of persisted metadata and any payload table's nonempty view. `sourceDeclaredComplete` for online sources is derived from verified mandatory-table presence, complete pagination and absence of all table filters, never the client checkbox. `sourceExportedAt` is ignored for online sources. Portal may omit it and submit `sourceDeclaredComplete:false`.
- Rich text, formula and lookup scalar values are extracted only through `text`, `name`, `value` and ordered readable text segments. Multiple numeric lookup values are not concatenated into amounts. Bare `record_ids`, `link_record_ids`, user IDs and unknown structured values are not displayed as business names. Missing units remain unknown; no packaging conversion is invented.
- Online header DD order numbers are extracted from business fields; record IDs remain source identity. A line's unique order link can resolve only against headers in the same capture. A line's unique product link can resolve only against captured `FEISHU_PRODUCT`, taking its readable product name/specification/descriptor; then existing unique exact SKU matching applies. Missing product lookup is unverified, never filled from Order amount or guessed by price.
- Business date-only values and Excel serials (including integers) use Asia/Shanghai. Online epoch milliseconds are supported. Raw `lastModifiedTime` can supply evidence modification time, never order/business date. `createdTime` is preserved in captured source rows and never substitutes a missing business date.
- Zero-quantity refund exclusion and file reconciliation behavior are retained. This review does not automatically amend orders, receipts, SKU units or Feishu. Evidence over six hours old remains stale; even fresh evidence is a non-atomic capture window, not a continuously live result.

Implementation and tests are authored in this scoped adapter. Maven is deliberately delegated to the main thread for serialized execution; service restart, V22 application and authenticated current-source capture/reconciliation require separate live acceptance.

## Operator-Confirmed Order Evidence (2026-09-14)

- Order owns historical product repair: read context, create a bounded 15-minute preview, then explicitly confirm that same preview. V36 stores previews and applied line evidence in Order. Confirmation checks tenant, permissions, ownership, creator, expiry, current order/line revision, original fact fingerprint and current ERP candidate. Repeated confirmation is idempotent; line changes and the applied audit record commit or roll back together.
- Repair changes only product/SKU references and revision metadata. Original product descriptions/codes, quantity, stored unit, prices, discounts, receivables and payment/refund facts remain unchanged. Historical transaction-unit confirmation and standard quantity/conversion evidence are separate optional audited fields, never an automatic packaging conversion. Zero/negative-quantity and excluded refund lines cannot be repaired.
- BI explicitly reads `/api/v1/orders/sales/product-repair-evidence` during capture with the actual signed tenant caller and their roles/permissions. No SERVICE administrator impersonation and no direct Order repair-table SQL is introduced. The bounded read fails rather than accepting a truncated evidence page. Unavailable evidence adds a notice; it does not invent a confirmed association or change the original facts.
- `Fact.systemLineId` and `systemVariantId` identify the captured current line. Evidence must match the exact source namespace and capture reference, order, line, current variant, stored unit and unchanged transaction quantity. Ambiguous, superseded, duplicate or other-capture evidence cannot certify the comparison. Confirmations do not silently carry into a fresh Feishu capture.
- `OPERATOR_CONFIRMED` means a recorded human attestation, not machine verification of a source document. Source association and historical-unit evidence are independent. An inferred/missing source unit may use the uniquely confirmed `confirmedUnitCode` for comparison; explicit source units are not overwritten. `rawUnit` retains the original source marker for history, drill-down and export, and neither money nor quantity is recomputed.
- Portal keeps source/capture technical identifiers hidden. Captured source context alone does not request the optional source-identity confirmation; the operator must supply evidence or explicitly select that review. Business tables show money and independent assessment states; source values, quantity/unit evidence and provenance are available in details.
- Regression coverage includes Order workflow/reference adapter tests, BI repair evidence overlay/HTTP adapter tests, and Portal preview/confirmation tests. This verifies contracts and local test behavior; V36 application and a real authenticated preview still require live acceptance. No real repair was confirmed during this implementation.
