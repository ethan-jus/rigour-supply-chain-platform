# Sales check-in upgrade — 2026-09-07

Local implementation on `codex/sales-checkin-upgrade-20260907`, based on `3754d7200f427fed624209f31ade0ed2fc58e074`. Not committed, pushed or deployed. The original dirty `dev` checkout was preserved.

## Behavior

- A valid sales identity, authorized store, required visit fields, consent and a server-received storefront photo remain necessary. Missing, stale, low-accuracy or distant GPS observations do not block check-in; 300 m is a review threshold.
- Raw device timestamp, parsed capture time, receive time, coordinate source/system, accuracy and store anchor are kept separately. Unknown timestamps are not rewritten to the receive time. Existing historical records retain their legacy quality state.
- The public UI has three stages with one main action: store, conversation, photo/submit. Browser recording is always visible in the conversation stage, with an independent start/stop action and existing-file fallback. Camera entry waits for durable form storage; returning files are stored separately as Blobs. JPEG/PNG headers are checked before a bounded local preview; images above 4 Mi pixels and unknown headers use a saved-file fallback instead of automatic full-resolution decoding.
- A stable client submission ID, key and receipt lookup prevent duplicate create/complete retries. Only a matching successful receipt yields submitted state. Local storage, pending synchronization and submitted state are visibly distinct.
- IndexedDB retains up to 20 records for 7 days, with a 512 MiB total media allowance. Browser quota failures remain visible; optional-media storage failures do not stop a photo-complete visit. Storage is isolated by authenticated salesperson. Browser eviction is not preventable.
- Main completion precedes optional uploads. Audio/screenshots can be appended within the configurable 24-hour server window by the original authorized owner. Immutable business/photo/location data are not overwritten. In-flight supplements keep their original submission ID when the user starts another visit.
- Admin time/city/sales/store columns have whitelisted server-side ascending/descending sorting and stable ID tie-breaking. List, count and CSV use the same filters/order. Review requires a reason and appends an audited event. Deleted or pending-deletion visits cannot be reviewed or supplemented.
- Thumbnails, actual audio duration and compatible MP3 copies are prepared asynchronously with bounded worker concurrency, process timeouts and fenced database leases. UI uses one active player and never presents unknown duration as zero. Relative device/store positions and accuracy circles are rendered without sending coordinates to an external map service.

## Reported 190 MB WAV failure

Screenshots identify HONOR 100 Pro / MagicOS 9 / Android 15 and a 190.0 MB, 11:31 WAV rejected with Nginx 413. The exact browser, original file and production effective configuration remain unverified. This evidence corrects the earlier tentative Huawei/HarmonyOS identification and does not establish the camera-exit root cause.

All upload layers now agree: business audio 256 MiB; Spring multipart file 256 MB / request 260 MB, disk threshold 0B; public Nginx API 270m. Images remain 10 MiB, audio segment count 20 and total audio quota 1 GiB. `options.maxAudioBytes` is the minimum of business and actual Spring limits, reserving request overhead. Deployment overrides and additional proxy limits still require live inspection.

The client checks size before metadata/preview work. Definite 413 is persisted as non-retryable for that file; users can replace it. HTML proxy errors are not rendered. Optional uploads allow 20 minutes total and stop after 90 seconds without upload progress; they do not hold the main visit or next visit open. No full-file browser transcoding was introduced.

See [upload deployment guide](../services/rigour-sales-work-service/sales-work-service/deploy/UPLOAD_LIMITS.md).

## Schema and runtime

- Additive Flyway `V21__temporary_checkin_evidence_review.sql` and `V22__temporary_checkin_media_derivatives.sql`. No executed migration SQL was edited.
- `Dockerfile.temporary-checkin` includes ffmpeg/ffprobe and retains UID/GID 10001. MP3 generation requires `libmp3lame`; source media remain protected originals.
- Public asset version: `20260907-blue-workspace`; admin asset version: `20260907-visit-upgrade`.
- Deletion invalidates derived media. Lease-fenced workers cannot overwrite a newer result; if both database bookkeeping and object cleanup fail, an unreachable derived object may remain and needs operational cleanup. An independent object-cleanup outbox is not part of this change.
- Original media Range reads still use the existing stream/skip path; native COS ranged reads are a later optimization.
- Derivative subprocesses are limited by time and thread count; deployment container memory/CPU and temporary disk must be sized and monitored. This is not a separate hard-memory sandbox for every decoder.

## Validation

Run from repository root:

```sh
./mvnw verify
./mvnw -pl services/rigour-sales-work-service/sales-work-service -am verify
node services/rigour-sales-work-service/sales-work-service/src/test/js/sales-checkin-geolocation.test.cjs
node services/rigour-sales-work-service/sales-work-service/src/test/js/sales-checkin-recovery.test.cjs
node services/rigour-sales-work-service/sales-work-service/scripts/admin-browser-check.mjs
node services/rigour-sales-work-service/sales-work-service/scripts/nginx-upload-check.mjs
git diff --check
```

Observed scope:

- Whole-repository verify was attempted and stops at unchanged IAM V52 with MySQL `ck_iam_resource_status`. IAM source/config/pom paths have no task diff. The failure was not bypassed by editing migration history or disabling constraints. Whole-repository release gate remains open.
- Final sales-module verify completed at 2026-09-07 15:18:19 Asia/Shanghai: **154 tests, zero failures/errors/skips, BUILD SUCCESS**; log `/tmp/checkin-sales-verify-release.log`. This includes the 71 API tests and the page contracts; do not sum overlapping suites as independent tests.
- Large-file API tests use a real random-port Tomcat, MySQL and synthetic PCM WAV read from disk with a 384 MiB test JVM heap. 190 MiB and exactly 256 MiB pass; 257 MiB returns JSON 413; disguised image content returns 400. The storage test double consumes the entire stream; these tests are not real COS uploads.
- 32 Node behavioral checks cover GPS quality, durable recovery, camera activation, receipt identity, quota failure, cross-visit supplements, file size checks, progress/idle/overall timers and non-retryable 413.
- Admin checks: 20 geometry tests, 53 interaction checks each at actual desktop browser widths 1440 and 500, plus 15 layout assertions each in same-origin iframe viewports 390 and 320. The iframe tests are CSS layout tests, not mobile-device tests. CUA separately observed public and admin page scroll width equal viewport width at 390/320 after the final CSS correction.
- Real Nginx 1.18 loads the repository proxy template with `nginx -t`; 1/190/256 MiB synthetic multipart bodies fully reach a local counting upstream. Public 270 MiB+1, admin 30 MiB+1 and location/identity 64 KiB+1 are rejected without reaching the upstream: 7 checks. This does not replace Java format checking or mobile validation.
- CUA walked the actual public JS with local fixture APIs: no GPS → store → fields → test photo → reload/restore Blob → matching submission receipt → submitted record → next visit. Fixture APIs are deliberately not production/authentication verification.
- Final local image `checkin-upgrade-review:20260907-final` (linux/arm64) passed network-disabled, read-only-rootfs, UID/GID 10001 ffmpeg/ffprobe/MP3 smoke checks. Image ID `sha256:37a72a243f7256fc4c9a0049c392d6ee494f3f0cdd0b690fd7a2c26c0d31210a`; JAR SHA256 `47feb68a713b8f7a1386fd716a7e700e51d483adee80636447b00ee2ca2d6d9f` matches `/app/app.jar`. JAR copies of app.js/admin.css/storage.js match final source hashes. Record: `/tmp/checkin-upgrade-packaging-final-20260907.md`. No service/DB/production connection was started by the image smoke test; production AMD64 remains untested. Earlier non-final image is obsolete for this release.

## CSV time formatting follow-up — 2026-09-07 16:11 Asia/Shanghai

Admin CSV export now formats location capture, creation, submission, location receipt and review times as `yyyy-MM-dd HH:mm:ss` in `Asia/Shanghai` (24-hour clock, second precision). Missing times remain empty. The raw device timestamp evidence column is preserved verbatim.

Two targeted API integration tests passed with zero failures/errors/skips, covering CSV formatting, fractional-second truncation, midnight boundaries, empty values, raw timestamp preservation and existing filter/order behavior. Log: `/tmp/checkin-csv-time-tests.log`; `git diff --check` also passed.

This follow-up is source-only and not deployed. The image and JAR recorded above predate this change and must be rebuilt before release. The unchanged IAM whole-repository verification failure remains unresolved.

## Remaining acceptance

The original HONOR recording, its actual browser, camera return, mobile network interruption and phone process eviction need real-device validation. Native camera crashes and pre-return file loss cannot be proven fixed by desktop tests. Browser background recording or closed-page upload is not guaranteed.

PWA offline shell, WeCom native SDK, recent/favorite store recommendations, customer autofill and an admin upload flow outside the supplement window are not implemented in this iteration. SILK transcoding is not universally guaranteed. The admin map is relative evidence visualization, not a street basemap.

Production deployment requires its own authorization and paired application/Nginx rollout, live migration/health/version checks and an actual phone trial. Local preview servers are test fixtures and must never be deployed.

## Approved seven-screen implementation and new field feedback — 2026-09-07

User approved the blue/white designs in `拜访打卡完整设计-20260907` and authorized implementation, with **no deployment until local verification**. This section supersedes earlier UI descriptions and test totals above.

- Implemented home/store selection, conversation/browser recording, photo confirmation, receipt, own history, date sheet and own detail in the existing production static app. Local Tabler icons, app-style header/footer and mobile safe areas; no separate prototype framework.
- Own history uses authenticated server-side Shanghai `submittedAt` date bounds, pagination, stable ascending/descending time sorting and true totals. It supports today, day navigation, single/range selection and last seven days. Server reads are tenant/salesperson scoped. Media/detail reads work across the same person's devices; supplement writes still require the original device/key and server deadline.
- New successful submissions invalidate list caches while preserving date selection. Detail return retains date/filter/scroll; stale requests and identity changes cannot display another person's results.
- Field screenshot 1 already reports a saved visit/photo, with optional audio uncertain. It is not evidence that the whole visit failed. The client now explicitly separates the main receipt from each attachment; unknown audio results are rechecked against the original receipt before any retry. Received media reconcile even after the supplement deadline.
- Field screenshots 2/3 report returning to a homepage during upload on iOS, also in Quark. Whether this was process eviction, page reload or navigation remains unverified. No production logs or original device have been examined in this turn; do not claim a proven root cause or a confirmed real-device fix.
- Re-entry after identity/IndexedDB initialization queries the original stable submission ID. A matching submitted receipt ends the main flow without duplicate POST. Unknown/network errors keep the draft and do not become success. Explicitly attempted drafts may resume; ordinary drafts are not automatically submitted.
- Returning local draft reads now check owner, current-form fingerprint, sequence and active submission/recording both before and after asynchronous storage reads. A late restore cannot overwrite a different visit being submitted or newly edited.
- Large WAV duration is read from at most a 64 KiB header. Other audio above 16 MiB is not automatically decoded for duration; server-derived metadata remains authoritative. Local players preload none and pause before upload. Browser recording remains available; phone recorder upload is a fallback.
- Local storage opening/reads stop waiting after 10 seconds, metadata transactions after 15 seconds and Blob writes after 60 seconds. Timeouts abort/reset the transaction/connection; late events do not report success or continue writing. Browser data eviction and an operating system killing a page before camera return remain outside a webpage's control.
- Timestamp display and admin CSV use Shanghai `yyyy-MM-dd HH:mm:ss`. Raw device timestamps remain unchanged evidence.

### Current verification and remaining gate — local Playwright, 2026-09-07

The user explicitly authorized local Playwright. The earlier pending-permission and incomplete-click-through statements are superseded by this run. Nothing was deployed, committed, pushed, or written to production.

- Local Chrome via Playwright: **33 checks passed**, using native touch, the browser's real MediaRecorder with a synthetic microphone stream, and a fixture storefront photo. Tested consent, recording stop, native retake file chooser, real IndexedDB photo/audio reload, interrupted required upload, lost completion/audio responses, matching original receipt, direct receipt-to-history navigation, and an injected optional-audio Nginx-style HTML 413. The 413 leaves the main server record SUBMITTED, shows friendly actionable text, permits the next visit and does not blindly retry. Evidence: `docs/qa-20260907/public-browser-results.json`.
- Own history, 390/320px: **92 checks passed**. Server date filtering, pagination beyond 20 rows, ascending/descending order, calendar ranges, actual four-second audio playback, photo detail, direct success-to-detail-to-list, error retry and out-of-order response protection. Evidence: `docs/qa-20260907/history-check/README.md`.
- Official Playwright WebKit 26.5, 390/320px, independent persistent temporary profiles: **50 checks passed**. One native tap advances after typing; real photo bytes persist in IndexedDB and recover on reload; the original submission receipt and own history/detail close the loop. Evidence: `docs/qa-20260907/webkit/README.md`.
- WebKit's nonpersistent automation context separately passed **48 UI/fallback checks**, but its isolated native Blob/File-to-IndexedDB primitive fails. This is explicitly a runtime limitation of that test mode, not a claimed Safari device defect. Persistent-mode primitive and application recovery tests pass. The app correctly reports unsaved local attachments in the failing mode and still permits current-page upload.
- Browser-discovered fixes: keep unchanged action text nodes stable during input blur (WebKit otherwise cancels native click); remove inherited disabled semantics from the active recording control; initialize history when returning from a first direct receipt detail; wire the retake button to the original camera input; correct photo preview width, visible calendar close icons and approved-layout differences.
- Final Node behavior checks: **80 total** — 6 geolocation, 38 recovery/media, 22 own history and 14 storage lifecycle. Log `/tmp/sales-checkin-js-playwright-final.log`.
- Full sales-module `verify`: **158 tests, zero failures/errors/skips**, plus 24 dependency tests; BUILD SUCCESS at 20:00:27 Asia/Shanghai. Log `/tmp/sales-module-verify-playwright-final.log`. Following the final CSS refinement, all **11 page contracts** passed again with verify/repackaging. Log `/tmp/sales-page-contract-playwright-final.log`. All **33 static resources** in the final Jar match source SHA256; evidence `docs/qa-20260907/package-resources.json`.
- Seven approved screens were compared against actual 390×844 browser captures, with 320px responsive verification. Current source/capture comparisons, corrections and intentional browser-control differences are recorded in root `design-qa.md`.
- Whole-repository `./mvnw verify` remains blocked by the unchanged IAM V52 migration writing INACTIVE against an ACTIVE/DISABLED check constraint. The earlier root run produced 20 IAM context errors and skipped downstream modules; no historical migration or unrelated IAM code was changed. Log `/tmp/sales-upgrade-full-verify.log`. A sales-module pass is not a whole-repository pass.
- Local preview `http://127.0.0.1:8774/sales-checkin/` serves the actual worktree static files and in-memory sample APIs. Its fixture upload limit is 12 MiB; it does not replace the separately tested production application/proxy 256 MiB/270m limits. The fixture must not be deployed.
- Physical iPhone/Honor camera return, microphone permissions, soft keyboard, GPS and OS page eviction remain device acceptance gaps. The supplied field report does not establish the cause of the original iOS return-to-home incident. These local browser results do not prove that original real-device incident has been eliminated.
- The old Docker image predates this final UI and is obsolete for deployment. No production application, proxy, database or data were changed.

Technical background (not a diagnosis of the reported device): [MDN pagehide](https://developer.mozilla.org/en-US/docs/Web/API/Window/pagehide_event), [MDN visibilitychange](https://developer.mozilla.org/en-US/docs/Web/API/Document/visibilitychange_event), [WebKit page suspension](https://webkit.org/blog/8970/how-web-content-can-affect-power-usage/).
