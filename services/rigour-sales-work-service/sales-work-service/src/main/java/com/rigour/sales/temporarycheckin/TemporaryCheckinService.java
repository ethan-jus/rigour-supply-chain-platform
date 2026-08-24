package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAccessPolicy.AdminScope;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.AdminOptionsResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.AdminMediaStorageStats;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.DeleteMediaView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.TranscriptionView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.AdminScopeView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.AdminSubmissionPage;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.AdminSubmissionView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CompletedSubmissionView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CreateStoreRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CreateSubmissionRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.DraftSubmissionView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.LocationCommand;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.LocationContextView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.NearbyStoreView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.ResolveLocationRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.MediaUploadView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.OptionsResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.SalespersonOption;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.StoreView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.AdminSubmissionRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.ExportRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.GeocodeWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.MediaReference;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.MediaWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.StoreRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.StoreWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.SubmissionRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.SubmissionWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinReverseGeocoder.GeocodeResult;
import com.rigour.sales.application.port.out.AmapPoiClient;
import com.rigour.sales.application.port.out.AmapPoiException;
import com.rigour.shared.file.FileMetadata;
import com.rigour.shared.file.FileStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 临时打卡表单用例。该服务不读取请求中的租户或身份，只使用部署配置的固定租户；
 * submissionKey 只保存 SHA-256 摘要，明文仅在当次请求内使用。
 */
@Service
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
public class TemporaryCheckinService {

    private static final Logger log = LoggerFactory.getLogger(TemporaryCheckinService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_EXPORT_ROWS = 20_000;
    public static final String PRIVACY_NOTICE_VERSION = "2026-08-25-ai-v1";
    private static final int NEARBY_RADIUS_METERS = 2_000;
    private static final int NEARBY_LIMIT = 10;
    private static final Map<String, String> CITY_ADCODE_PREFIXES = Map.of(
            "北京", "11",
            "深圳", "4403",
            "杭州", "3301",
            "成都", "5101",
            "武汉", "4201",
            "西安", "6101");
    private static final Set<String> SUBMISSION_STATUSES = Set.of("DRAFT", "SUBMITTED");
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() { };

    private final TemporaryCheckinRepository repository;
    private final TemporaryCheckinProperties properties;
    private final FileStorage fileStorage;
    private final TemporaryCheckinReverseGeocoder reverseGeocoder;
    private final AmapPoiClient amapPoiClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final UUID tenantId;
    private final boolean aiEnabled;

    public TemporaryCheckinService(
            TemporaryCheckinRepository repository,
            TemporaryCheckinProperties properties,
            FileStorage fileStorage,
            TemporaryCheckinReverseGeocoder reverseGeocoder,
            AmapPoiClient amapPoiClient,
            ObjectMapper objectMapper,
            Clock clock,
            ObjectProvider<TemporaryCheckinAiClient> aiClientProvider) {
        this.repository = repository;
        this.properties = properties;
        this.fileStorage = fileStorage;
        this.reverseGeocoder = reverseGeocoder;
        this.amapPoiClient = amapPoiClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tenantId = properties.requireTenantId();
        this.aiEnabled = aiClientProvider.getIfAvailable() != null;
        validateConfiguration(properties);
    }

    public OptionsResponse options(String city) {
        String normalizedCity = optionalEnum(city, properties.getCities(), "city");
        List<String> configuredCities = properties.getCities();
        List<SalespersonOption> salespersons = repository.findSalespersons(tenantId, normalizedCity).stream()
                .filter(row -> configuredCities.contains(row.city()))
                .sorted(Comparator
                        .comparingInt((TemporaryCheckinRepository.SalespersonRow row) ->
                                configuredCities.indexOf(row.city()))
                        .thenComparingInt(TemporaryCheckinRepository.SalespersonRow::sortOrder)
                        .thenComparing(TemporaryCheckinRepository.SalespersonRow::name)
                        .thenComparing(TemporaryCheckinRepository.SalespersonRow::id))
                .map(row -> new SalespersonOption(row.id(), row.name(), row.city()))
                .toList();
        return new OptionsResponse(properties.getCities(), salespersons, properties.getStoreAttributes(),
                properties.getOperatingStatuses(), properties.getAreaRanges(), properties.getBusinessTypes(),
                properties.getIntendedBusinesses(), properties.getCooperationIntents(),
                properties.getStoreGrades(), properties.getStoreTags());
    }

    public List<StoreView> searchStores(String city, String query, Integer requestedLimit) {
        String normalizedCity = requiredEnum(city, properties.getCities(), "city");
        String normalizedQuery = required(query, "q", 128);
        if (normalizedQuery.length() < 2) throw TemporaryCheckinException.badRequest("q至少输入2个字符");
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 20) throw TemporaryCheckinException.badRequest("limit必须在1到20之间");
        String escaped = normalizedQuery.replace("=", "==").replace("%", "=%").replace("_", "=_");
        return repository.searchStores(tenantId, normalizedCity, escaped, limit).stream()
                .map(TemporaryCheckinService::storeView)
                .toList();
    }

    public LocationContextView resolveLocation(ResolveLocationRequest request) {
        if (request == null) throw TemporaryCheckinException.badRequest("location不能为空");
        String city = requiredEnum(request.city(), properties.getCities(), "city");
        NormalizedLocation location = normalizeLocation(request.location());
        GeocodeResult geocode = reverseGeocoder.resolve(location.longitude(), location.latitude());
        validateResolvedCity(city, geocode);

        List<NearbyStoreView> nearby = new ArrayList<>();
        repository.findLocatedStores(tenantId, city).stream()
                .map(store -> new StoreDistance(store, distanceMeters(
                        location.latitude(), location.longitude(), store.latitude(), store.longitude())))
                .filter(item -> item.distanceMeters() <= NEARBY_RADIUS_METERS)
                .sorted(Comparator.comparingDouble(StoreDistance::distanceMeters)
                        .thenComparing(item -> item.store().name()))
                .limit(NEARBY_LIMIT)
                .forEach(item -> nearby.add(new NearbyStoreView("REGISTERED", item.store().id(), null,
                        item.store().name(), item.store().city(), storeLocationSummary(item.store()),
                        BigDecimal.valueOf(item.distanceMeters()).setScale(0, java.math.RoundingMode.HALF_UP),
                        item.store().longitude(), item.store().latitude())));

        if (geocode.amapLongitude() != null && geocode.amapLatitude() != null) {
            try {
                Set<String> registeredNames = nearby.stream()
                        .map(item -> normalizeName(item.name())).collect(java.util.stream.Collectors.toSet());
                amapPoiClient.searchAround("", geocode.amapLongitude(), geocode.amapLatitude(),
                                NEARBY_RADIUS_METERS, 1, NEARBY_LIMIT)
                        .items().stream()
                        .filter(poi -> poi.name() != null && !poi.name().isBlank())
                        .filter(poi -> !registeredNames.contains(normalizeName(poi.name())))
                        .limit(NEARBY_LIMIT)
                        .forEach(poi -> nearby.add(new NearbyStoreView("AMAP_POI", null, poi.poiId(),
                                poi.name(), city, poi.address(), poi.distanceMeters(),
                                poi.longitude(), poi.latitude())));
            } catch (AmapPoiException ignored) {
                // 可读地址和本地门店仍然可用；高德 POI 暂时失败不阻断现场打卡。
            }
        }
        return new LocationContextView(geocode.status(), geocode.address(), geocode.formattedAddress(),
                geocode.adcode(), List.copyOf(nearby));
    }

    @Transactional
    public StoreView createStore(CreateStoreRequest request) {
        if (request == null || request.clientStoreId() == null || request.salespersonId() == null) {
            throw TemporaryCheckinException.badRequest("clientStoreId和salespersonId不能为空");
        }
        NormalizedStore normalized = normalizeStore(request);
        OptionalStore existing = existingStore(request.clientStoreId(), normalized);
        if (existing.present()) return storeView(existing.row());
        var salesperson = requireSalesperson(request.salespersonId(), normalized.city());

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        GeocodeResult geocode = reverseGeocoder.resolve(
                normalized.location().longitude(), normalized.location().latitude());
        validateResolvedCity(normalized.city(), geocode);
        GeocodeWrite geocodeWrite = new GeocodeWrite(geocode.status(), geocode.address(),
                geocode.formattedAddress(), geocode.adcode(), geocode.province(), geocode.city(),
                geocode.district(), geocode.township(), geocode.amapLongitude(), geocode.amapLatitude(),
                geocode.errorCode(), now);
        StoreWrite write = new StoreWrite(id, tenantId, request.clientStoreId(), normalized.city(),
                salesperson.id(), normalized.attribute(), normalized.name(), normalized.operatingStatus(),
                normalized.contactName(), normalized.contactPhone(), normalized.areaRange(),
                normalized.facilityCount(), writeJson(normalized.businessTypes()),
                writeJson(normalized.intendedBusinesses()), normalized.cooperationIntent(),
                normalized.storeGrade(), writeJson(normalized.tags()), normalized.location().longitude(),
                normalized.location().latitude(), normalized.location().accuracyMeters(),
                normalized.location().capturedAt(), normalized.location().note(), normalized.sourcePoiId(),
                normalized.sourcePoiName(), normalized.sourcePoiAddress(), normalized.sourcePoiLongitude(),
                normalized.sourcePoiLatitude(), geocodeWrite, now);
        try {
            repository.insertStore(write);
        } catch (DataIntegrityViolationException duplicate) {
            StoreRow concurrent = repository.findStoreByClientId(tenantId, request.clientStoreId())
                    .orElseThrow(() -> TemporaryCheckinException.conflict("门店创建冲突，请刷新后重试"));
            assertSameStore(concurrent, normalized);
            return storeView(concurrent);
        }
        return repository.findStore(tenantId, id).map(TemporaryCheckinService::storeView)
                .orElseThrow(() -> new IllegalStateException("门店写入后不可见"));
    }

    @Transactional
    public DraftSubmissionView createDraft(CreateSubmissionRequest request) {
        if (request == null || request.clientSubmissionId() == null || request.salespersonId() == null
                || request.storeId() == null) {
            throw TemporaryCheckinException.badRequest(
                    "clientSubmissionId、salespersonId和storeId不能为空");
        }
        String key = validateSubmissionKey(request.submissionKey());
        String keyHash = sha256Hex(key.getBytes(StandardCharsets.UTF_8));
        if (!Boolean.TRUE.equals(request.privacyAccepted())) {
            throw TemporaryCheckinException.badRequest("必须明确同意定位、照片、录音及转写说明");
        }
        if (!PRIVACY_NOTICE_VERSION.equals(request.privacyNoticeVersion())) {
            throw TemporaryCheckinException.badRequest("隐私提示版本已更新，请刷新页面后重新确认");
        }
        String city = requiredEnum(request.city(), properties.getCities(), "city");
        NormalizedSubmission normalized = new NormalizedSubmission(city, request.salespersonId(), request.storeId(),
                required(request.customerName(), "customerName", 128),
                optionalPhone(request.customerPhone(), "customerPhone"),
                requiredMultiline(request.visitResult(), "visitResult", 2000),
                normalizeLocation(request.location()), true, request.privacyNoticeVersion());
        SubmissionRow existing = repository.findSubmissionByClientId(tenantId, request.clientSubmissionId())
                .orElse(null);
        if (existing != null) {
            requireMatchingKey(existing, key);
            assertSameSubmission(existing, normalized);
            return new DraftSubmissionView(existing.id(), existing.status(), existing.createdAt());
        }
        var salesperson = requireSalesperson(request.salespersonId(), city);
        StoreRow store = repository.findStore(tenantId, request.storeId())
                .orElseThrow(() -> TemporaryCheckinException.notFound("门店不存在或已停用"));
        if (!city.equals(store.city())) throw TemporaryCheckinException.badRequest("门店与选择城市不一致");
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        GeocodeResult geocode = reverseGeocoder.resolve(
                normalized.location().longitude(), normalized.location().latitude());
        validateResolvedCity(city, geocode);
        GeocodeWrite geocodeWrite = new GeocodeWrite(geocode.status(), geocode.address(),
                geocode.formattedAddress(), geocode.adcode(), geocode.province(), geocode.city(),
                geocode.district(), geocode.township(), geocode.amapLongitude(), geocode.amapLatitude(),
                geocode.errorCode(), now);
        SubmissionWrite write = new SubmissionWrite(id, tenantId, request.clientSubmissionId(), keyHash,
                city, salesperson.id(), salesperson.name(), store.id(), store.name(),
                normalized.customerName(), normalized.customerPhone(), normalized.visitResult(),
                normalized.location().longitude(), normalized.location().latitude(),
                normalized.location().accuracyMeters(), normalized.location().capturedAt(),
                normalized.location().note(), geocodeWrite, normalized.privacyNoticeVersion(), now);
        try {
            repository.insertSubmission(write);
        } catch (DataIntegrityViolationException duplicate) {
            SubmissionRow concurrent = repository.findSubmissionByClientId(tenantId, request.clientSubmissionId())
                    .orElseThrow(() -> TemporaryCheckinException.conflict("草稿创建冲突，请重试"));
            requireMatchingKey(concurrent, key);
            assertSameSubmission(concurrent, normalized);
            return new DraftSubmissionView(concurrent.id(), concurrent.status(), concurrent.createdAt());
        }
        return new DraftSubmissionView(id, "DRAFT", now);
    }

    public MediaUploadView uploadMedia(UUID submissionId, String rawKind, String submissionKey, MultipartFile file) {
        if (submissionId == null) throw TemporaryCheckinException.badRequest("submissionId不能为空");
        MediaKind kind = MediaKind.parse(rawKind);
        SubmissionRow submission = requireSubmission(submissionId);
        requireMatchingKey(submission, submissionKey);
        if (!"DRAFT".equals(submission.status())) {
            throw TemporaryCheckinException.conflict("已提交的打卡不允许替换媒体");
        }
        ValidatedMedia validated = validateMedia(kind, file);
        MediaReference previous = media(submission, kind);
        if (previous.objectKey() != null && validated.sha256().equals(previous.sha256())) {
            return new MediaUploadView(submissionId, kind.pathValue, "DRAFT", validated.sha256(),
                    validated.bytes().length);
        }

        String objectKey = tenantId + "/temporary-sales-checkin/" + submissionId + "/"
                + kind.objectDirectory + "/" + validated.sha256() + validated.extension();
        Instant now = clock.instant();
        try {
            fileStorage.put(new FileMetadata(tenantId.toString(), objectKey, validated.originalFilename(),
                    validated.contentType(), validated.bytes().length, validated.sha256(),
                    OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC)),
                    new ByteArrayInputStream(validated.bytes()));
        } catch (RuntimeException exception) {
            throw TemporaryCheckinException.storage("媒体文件存储失败，请稍后重试");
        }
        int updated;
        try {
            updated = repository.updateMedia(tenantId, submissionId, kind.columnPrefix,
                    new MediaWrite(objectKey, validated.contentType(), validated.bytes().length,
                            validated.sha256(), validated.originalFilename()), now);
        } catch (RuntimeException exception) {
            // 不在请求内删除已写入对象：并发重试可能正在引用同一内容哈希键。
            // 孤儿对象后续应按数据库引用快照和保留期离线清理。
            throw exception;
        }
        if (updated != 1) {
            throw TemporaryCheckinException.conflict("草稿状态已变化，请刷新后重试");
        }
        if (previous.objectKey() != null && !previous.objectKey().equals(objectKey)) {
            try {
                fileStorage.delete(tenantId.toString(), previous.objectKey());
            } catch (RuntimeException exception) {
                log.warn("临时打卡旧媒体清理失败 submissionId={} kind={} reason={}",
                        submissionId, kind.pathValue, exception.getClass().getSimpleName());
            }
        }
        return new MediaUploadView(submissionId, kind.pathValue, "DRAFT", validated.sha256(),
                validated.bytes().length);
    }

    @Transactional
    public CompletedSubmissionView complete(UUID submissionId, String submissionKey) {
        SubmissionRow submission = requireSubmission(submissionId);
        requireMatchingKey(submission, submissionKey);
        if ("SUBMITTED".equals(submission.status())) {
            queueTranscriptionIfEligible(submission);
            return new CompletedSubmissionView(submission.id(), submission.status(), submission.submittedAt());
        }
        if (!hasMedia(submission.storefrontPhoto())) {
            throw TemporaryCheckinException.badRequest("请先上传门头照");
        }
        Instant submittedAt = clock.instant();
        if (repository.complete(tenantId, submissionId, submittedAt) != 1) {
            SubmissionRow current = requireSubmission(submissionId);
            if ("SUBMITTED".equals(current.status())) {
                queueTranscriptionIfEligible(current);
                return new CompletedSubmissionView(current.id(), current.status(), current.submittedAt());
            }
            throw TemporaryCheckinException.conflict("草稿状态已变化，请刷新后重试");
        }
        SubmissionRow completed = requireSubmission(submissionId);
        if (!"SUBMITTED".equals(completed.status())) {
            throw TemporaryCheckinException.conflict("草稿状态已变化，请刷新后重试");
        }
        queueTranscriptionIfEligible(completed);
        return new CompletedSubmissionView(completed.id(), completed.status(), completed.submittedAt());
    }

    public TranscriptionView requestAdminTranscription(AdminScope scope, UUID submissionId) {
        requireConfiguredScope(scope);
        if (!scope.allCities()) {
            throw TemporaryCheckinException.adminForbidden("只有总管理员可以重试录音转写");
        }
        if (!aiEnabled) {
            throw TemporaryCheckinException.conflict("录音转写服务尚未启用");
        }
        SubmissionRow submission = requireSubmission(submissionId);
        if (!hasMedia(submission.audio())) {
            throw TemporaryCheckinException.notFound("录音不存在或已删除");
        }
        if (!PRIVACY_NOTICE_VERSION.equals(submission.privacyNoticeVersion())) {
            throw TemporaryCheckinException.conflict("该记录未确认当前录音转写隐私提示，不能发起转写");
        }
        Instant now = clock.instant();
        if ("SUCCEEDED".equals(submission.transcriptionStatus())
                && "FAILED".equals(submission.summaryStatus())) {
            repository.requestSummary(tenantId, submissionId, now);
        } else {
            repository.requestTranscription(tenantId, submissionId, PRIVACY_NOTICE_VERSION, now);
        }
        SubmissionRow current = requireSubmission(submissionId);
        return new TranscriptionView(current.id(), current.transcriptionStatus(), current.summaryStatus());
    }

    public AdminOptionsResponse adminOptions(AdminScope scope) {
        String scopedCity = requireConfiguredScope(scope);
        List<String> cities = scope.allCities() ? properties.getCities() : List.of(scopedCity);
        List<SalespersonOption> salespersons = repository.findSalespersons(tenantId, scopedCity).stream()
                .filter(row -> cities.contains(row.city()))
                .map(row -> new SalespersonOption(row.id(), row.name(), row.city()))
                .toList();
        var stats = repository.mediaStorageStats(tenantId, scopedCity);
        return new AdminOptionsResponse(scopeView(scope), cities, salespersons,
                new AdminMediaStorageStats(stats.activeFiles(), stats.totalBytes(), stats.imageBytes(),
                        stats.audioBytes(), stats.oldestCreatedAt()));
    }

    public AdminSubmissionPage findAdminSubmissions(
            AdminScope scope, LocalDate from, LocalDate to, String city, UUID salespersonId,
            String status, String query, Integer requestedPage, Integer requestedSize) {
        AdminQuery filters = normalizeAdminQuery(scope, from, to, city, salespersonId, status, query);
        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? 20 : requestedSize;
        if (page < 0) throw TemporaryCheckinException.badRequest("page不能小于0");
        if (size < 1 || size > 100) {
            throw TemporaryCheckinException.badRequest("size必须在1到100之间");
        }
        long longOffset = (long) page * size;
        if (longOffset > Integer.MAX_VALUE) {
            throw TemporaryCheckinException.badRequest("分页范围过大");
        }
        long total = repository.countAdminSubmissions(tenantId, filters.from(), filters.toExclusive(),
                filters.city(), filters.salespersonId(), filters.status(), filters.escapedQuery());
        List<AdminSubmissionView> items = repository.findAdminSubmissions(
                        tenantId, filters.from(), filters.toExclusive(), filters.city(), filters.salespersonId(),
                        filters.status(), filters.escapedQuery(), (int) longOffset, size).stream()
                .map(TemporaryCheckinService::adminSubmissionView)
                .toList();
        long pageCount = total == 0 ? 0 : ((total - 1) / size) + 1;
        int totalPages = (int) Math.min(Integer.MAX_VALUE, pageCount);
        return new AdminSubmissionPage(scopeView(scope), items, total, total, page, size, totalPages);
    }

    public String exportCsv(
            AdminScope scope, LocalDate from, LocalDate to, String city, UUID salespersonId, String status) {
        AdminQuery filters = normalizeAdminQuery(scope, from, to, city, salespersonId, status, null);
        List<ExportRow> rows = repository.export(tenantId, filters.from(), filters.toExclusive(), filters.city(),
                filters.salespersonId(), filters.status(), MAX_EXPORT_ROWS + 1);
        if (rows.size() > MAX_EXPORT_ROWS) {
            throw TemporaryCheckinException.badRequest("导出超过20000条，请缩小日期或城市范围");
        }
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendCsv(csv, List.of("submission_id", "client_submission_id", "status", "city",
                "salesperson_id", "salesperson_name", "store_id", "store_name", "customer_name",
                "customer_phone", "visit_result", "longitude", "latitude", "accuracy_meters",
                "location_captured_at", "location_note", "location_address", "location_adcode",
                "storefront_photo", "wechat_screenshot", "audio", "transcription_status", "transcript",
                "summary_status", "summary", "created_at", "submitted_at"));
        for (ExportRow row : rows) {
            appendCsv(csv, List.of(value(row.id()), value(row.clientSubmissionId()), value(row.status()),
                    value(row.city()), value(row.salespersonId()), value(row.salespersonName()),
                    value(row.storeId()), value(row.storeName()), value(row.customerName()),
                    value(row.customerPhone()), value(row.visitResult()), value(row.longitude()),
                    value(row.latitude()), value(row.accuracyMeters()), value(row.locationCapturedAt()),
                    value(row.locationNote()), value(row.locationAddress()), value(row.locationAdcode()),
                    value(row.storefrontPhotoFilename()), value(row.wechatScreenshotFilename()),
                    value(row.audioFilename()), value(row.transcriptionStatus()), value(row.transcript()),
                    value(row.summaryStatus()), value(row.summaryText()), value(row.createdAt()),
                    value(row.submittedAt())));
        }
        return csv.toString();
    }

    public AdminMedia openAdminMedia(AdminScope scope, UUID submissionId, String rawKind) {
        String scopedCity = requireConfiguredScope(scope);
        MediaKind kind = MediaKind.parse(rawKind);
        MediaReference media = repository.findMedia(tenantId, submissionId, kind.columnPrefix, scopedCity)
                .orElseThrow(() -> TemporaryCheckinException.notFound("媒体文件不存在"));
        Supplier<InputStream> opener = () -> {
            try {
                return fileStorage.open(tenantId.toString(), media.objectKey());
            } catch (RuntimeException exception) {
                throw TemporaryCheckinException.storage("媒体文件读取失败");
            }
        };
        return new AdminMedia(opener, media.sizeBytes() == null ? 0 : media.sizeBytes(),
                media.contentType(), media.originalFilename());
    }

    public DeleteMediaView deleteAdminMedia(
            AdminScope scope, UUID submissionId, String rawKind, String rawReason) {
        requireConfiguredScope(scope);
        if (!scope.allCities()) {
            throw TemporaryCheckinException.adminForbidden("只有总管理员可以物理删除媒体文件");
        }
        MediaKind kind = MediaKind.parse(rawKind);
        String reason = requiredMultiline(rawReason, "reason", 512);
        MediaReference media = repository.findMedia(tenantId, submissionId, kind.columnPrefix, null)
                .orElse(null);
        if (media == null) {
            SubmissionRow submission = repository.findSubmission(tenantId, submissionId)
                    .orElseThrow(() -> TemporaryCheckinException.notFound("打卡记录不存在"));
            MediaReference current = media(submission, kind);
            if (current != null && current.deletedAt() != null) {
                return new DeleteMediaView(submissionId, kind.pathValue, "DELETED", current.deletedAt());
            }
            throw TemporaryCheckinException.notFound("媒体文件不存在");
        }
        try {
            fileStorage.delete(tenantId.toString(), media.objectKey());
        } catch (RuntimeException exception) {
            throw TemporaryCheckinException.storage("COS媒体物理删除失败，数据库未标记删除，请稍后重试");
        }
        Instant deletedAt = clock.instant();
        int updated = repository.markMediaDeleted(tenantId, submissionId, kind.columnPrefix,
                media.objectKey(), scope.username(), reason, deletedAt);
        if (updated == 0) {
            SubmissionRow current = requireSubmission(submissionId);
            MediaReference latest = media(current, kind);
            if (latest != null && latest.deletedAt() != null) {
                return new DeleteMediaView(submissionId, kind.pathValue, "DELETED", latest.deletedAt());
            }
            throw TemporaryCheckinException.conflict("媒体已删除但审计状态更新冲突，请立即联系管理员核对");
        }
        return new DeleteMediaView(submissionId, kind.pathValue, "DELETED", deletedAt);
    }

    private void queueTranscriptionIfEligible(SubmissionRow submission) {
        if (aiEnabled && "SUBMITTED".equals(submission.status()) && hasMedia(submission.audio())
                && PRIVACY_NOTICE_VERSION.equals(submission.privacyNoticeVersion())) {
            repository.requestTranscription(
                    tenantId, submission.id(), PRIVACY_NOTICE_VERSION, clock.instant());
        }
    }

    private static void validateResolvedCity(String expectedCity, GeocodeResult geocode) {
        if (geocode == null || !"RESOLVED".equals(geocode.status())) return;
        String expectedAdcodePrefix = CITY_ADCODE_PREFIXES.get(expectedCity);
        if (expectedAdcodePrefix != null && geocode.adcode() != null && !geocode.adcode().isBlank()) {
            if (!geocode.adcode().trim().startsWith(expectedAdcodePrefix)) {
                throw TemporaryCheckinException.badRequest("当前定位不在所选城市，请重新选择城市并定位");
            }
            return;
        }
        String actualCity = firstText(geocode.city(), geocode.province());
        if (actualCity != null && !normalizeCityName(expectedCity).equals(normalizeCityName(actualCity))) {
            throw TemporaryCheckinException.badRequest("当前定位不在所选城市，请重新选择城市并定位");
        }
    }

    private static String firstText(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null || second.isBlank() ? null : second;
    }

    private static String normalizeCityName(String city) {
        String normalized = city == null ? "" : city.trim();
        return normalized.endsWith("市") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private AdminQuery normalizeAdminQuery(
            AdminScope scope, LocalDate from, LocalDate to, String city, UUID salespersonId,
            String status, String query) {
        if (from != null && to != null && from.isAfter(to)) {
            throw TemporaryCheckinException.badRequest("from不能晚于to");
        }
        if (from != null && to != null && Duration.between(
                from.atStartOfDay(BUSINESS_ZONE), to.plusDays(1).atStartOfDay(BUSINESS_ZONE)).toDays() > 366) {
            throw TemporaryCheckinException.badRequest("导出日期范围不能超过366天");
        }
        String scopedCity = requireConfiguredScope(scope);
        String requestedCity = optionalEnum(city, properties.getCities(), "city");
        if (!scope.allCities() && requestedCity != null && !scopedCity.equals(requestedCity)) {
            throw TemporaryCheckinException.adminForbidden("城市账号不能访问其他城市数据");
        }
        String effectiveCity = scope.allCities() ? requestedCity : scopedCity;
        String normalizedStatus = optionalEnum(status, List.copyOf(SUBMISSION_STATUSES), "status");
        String normalizedQuery = optional(query, "q", 128);
        String escapedQuery = normalizedQuery == null ? null
                : normalizedQuery.replace("=", "==").replace("%", "=%").replace("_", "=_");
        Instant fromInstant = from == null ? null : from.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant toExclusive = to == null ? null : to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        return new AdminQuery(fromInstant, toExclusive, effectiveCity, salespersonId, normalizedStatus,
                escapedQuery);
    }

    private String requireConfiguredScope(AdminScope scope) {
        if (scope == null) {
            throw TemporaryCheckinException.adminForbidden("缺少后台身份范围");
        }
        if (scope.allCities()) return null;
        if (!properties.getCities().contains(scope.city())) {
            throw TemporaryCheckinException.adminForbidden("后台账号城市未启用");
        }
        return scope.city();
    }

    private static AdminScopeView scopeView(AdminScope scope) {
        return new AdminScopeView(scope.username(), scope.allCities(), scope.city());
    }

    private static AdminSubmissionView adminSubmissionView(AdminSubmissionRow row) {
        return new AdminSubmissionView(row.id(), row.status(), row.city(), row.salespersonId(),
                row.salespersonName(), row.storeId(), row.storeName(), row.customerName(), row.customerPhone(),
                row.visitResult(), row.longitude(), row.latitude(), row.accuracyMeters(),
                row.locationCapturedAt(), row.locationNote(), row.locationAddress(), row.locationAdcode(),
                row.storefrontPhotoAvailable(), row.wechatScreenshotAvailable(), row.audioAvailable(),
                row.storefrontPhotoDeletedAt(), row.wechatScreenshotDeletedAt(), row.audioDeletedAt(),
                row.transcriptionStatus(), row.transcript(), row.transcriptionErrorCode(),
                row.summaryStatus(), row.summaryText(), row.summaryErrorCode(),
                row.createdAt(), row.submittedAt());
    }

    private OptionalStore existingStore(UUID clientStoreId, NormalizedStore normalized) {
        StoreRow row = repository.findStoreByClientId(tenantId, clientStoreId).orElse(null);
        if (row == null) return new OptionalStore(false, null);
        assertSameStore(row, normalized);
        return new OptionalStore(true, row);
    }

    private void assertSameStore(StoreRow row, NormalizedStore normalized) {
        if (!Objects.equals(row.city(), normalized.city())
                || !Objects.equals(row.creatorSalespersonId(), normalized.salespersonId())
                || !Objects.equals(row.sourcePoiId(), normalized.sourcePoiId())
                || !Objects.equals(row.sourcePoiName(), normalized.sourcePoiName())
                || !Objects.equals(row.sourcePoiAddress(), normalized.sourcePoiAddress())
                || !nullableDecimalEquals(row.sourcePoiLongitude(), normalized.sourcePoiLongitude())
                || !nullableDecimalEquals(row.sourcePoiLatitude(), normalized.sourcePoiLatitude())
                || !Objects.equals(row.attribute(), normalized.attribute())
                || !Objects.equals(row.name(), normalized.name())
                || !Objects.equals(row.operatingStatus(), normalized.operatingStatus())
                || !Objects.equals(row.contactName(), normalized.contactName())
                || !Objects.equals(row.contactPhone(), normalized.contactPhone())
                || !Objects.equals(row.areaRange(), normalized.areaRange())
                || !Objects.equals(row.facilityCount(), normalized.facilityCount())
                || !jsonListEquals(row.businessTypesJson(), normalized.businessTypes())
                || !jsonListEquals(row.intendedBusinessesJson(), normalized.intendedBusinesses())
                || !Objects.equals(row.cooperationIntent(), normalized.cooperationIntent())
                || !Objects.equals(row.storeGrade(), normalized.storeGrade())
                || !jsonListEquals(row.tagsJson(), normalized.tags())
                || !decimalEquals(row.longitude(), normalized.location().longitude())
                || !decimalEquals(row.latitude(), normalized.location().latitude())
                || !decimalEquals(row.accuracyMeters(), normalized.location().accuracyMeters())
                || !sameInstant(row.locationCapturedAt(), normalized.location().capturedAt())
                || !Objects.equals(row.locationNote(), normalized.location().note())) {
            throw TemporaryCheckinException.conflict("clientStoreId已被不同门店数据使用");
        }
    }

    private static void assertSameSubmission(SubmissionRow row, NormalizedSubmission normalized) {
        NormalizedLocation location = normalized.location();
        if (!Objects.equals(row.city(), normalized.city())
                || !Objects.equals(row.salespersonId(), normalized.salespersonId())
                || !Objects.equals(row.storeId(), normalized.storeId())
                || !Objects.equals(row.customerName(), normalized.customerName())
                || !Objects.equals(row.customerPhone(), normalized.customerPhone())
                || !Objects.equals(row.visitResult(), normalized.visitResult())
                || !decimalEquals(row.longitude(), location.longitude())
                || !decimalEquals(row.latitude(), location.latitude())
                || !decimalEquals(row.accuracyMeters(), location.accuracyMeters())
                || !sameInstant(row.locationCapturedAt(), location.capturedAt())
                || !Objects.equals(row.locationNote(), location.note())
                || row.privacyAccepted() != normalized.privacyAccepted()
                || !Objects.equals(row.privacyNoticeVersion(), normalized.privacyNoticeVersion())) {
            throw TemporaryCheckinException.conflict("clientSubmissionId已被不同打卡数据使用");
        }
    }

    private NormalizedStore normalizeStore(CreateStoreRequest request) {
        String city = requiredEnum(request.city(), properties.getCities(), "city");
        String sourcePoiId = optional(request.sourcePoiId(), "sourcePoiId", 128);
        String sourcePoiName = optional(request.sourcePoiName(), "sourcePoiName", 256);
        String sourcePoiAddress = optional(request.sourcePoiAddress(), "sourcePoiAddress", 512);
        BigDecimal sourcePoiLongitude = optionalCoordinate(
                request.sourcePoiLongitude(), -180, 180, "sourcePoiLongitude");
        BigDecimal sourcePoiLatitude = optionalCoordinate(
                request.sourcePoiLatitude(), -90, 90, "sourcePoiLatitude");
        if ((sourcePoiLongitude == null) != (sourcePoiLatitude == null)) {
            throw TemporaryCheckinException.badRequest("高德门店候选经纬度必须同时提供");
        }
        if (sourcePoiId == null && (sourcePoiName != null || sourcePoiAddress != null
                || sourcePoiLongitude != null)) {
            throw TemporaryCheckinException.badRequest("高德门店候选缺少poiId");
        }
        return new NormalizedStore(city, request.salespersonId(),
                sourcePoiId, sourcePoiName, sourcePoiAddress, sourcePoiLongitude, sourcePoiLatitude,
                requiredEnum(request.attribute(), properties.getStoreAttributes(), "attribute"),
                required(request.name(), "name", 256),
                requiredEnum(request.operatingStatus(), properties.getOperatingStatuses(), "operatingStatus"),
                required(request.contactName(), "contactName", 128),
                optionalPhone(request.contactPhone(), "contactPhone"),
                requiredEnum(request.areaRange(), properties.getAreaRanges(), "areaRange"),
                validateFacilityCount(request.facilityCount()),
                normalizeList(request.businessTypes(), properties.getBusinessTypes(), "businessTypes", true),
                normalizeList(request.intendedBusinesses(), properties.getIntendedBusinesses(),
                        "intendedBusinesses", true),
                requiredEnum(request.cooperationIntent(), properties.getCooperationIntents(), "cooperationIntent"),
                optionalEnum(request.storeGrade(), properties.getStoreGrades(), "storeGrade"),
                normalizeList(request.tags(), properties.getStoreTags(), "tags", true),
                normalizeLocation(request.location()));
    }

    private TemporaryCheckinRepository.SalespersonRow requireSalesperson(UUID id, String city) {
        var salesperson = repository.findSalesperson(tenantId, id)
                .orElseThrow(() -> TemporaryCheckinException.notFound("销售不存在或已停用"));
        if (!city.equals(salesperson.city())) throw TemporaryCheckinException.badRequest("销售与选择城市不一致");
        return salesperson;
    }

    private SubmissionRow requireSubmission(UUID id) {
        return repository.findSubmission(tenantId, id)
                .orElseThrow(() -> TemporaryCheckinException.notFound("打卡草稿不存在"));
    }

    private void requireMatchingKey(SubmissionRow submission, String rawKey) {
        String key = validateSubmissionKey(rawKey);
        byte[] expected = submission.keyHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = sha256Hex(key.getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw TemporaryCheckinException.forbidden("提交密钥无效");
        }
    }

    private ValidatedMedia validateMedia(MediaKind kind, MultipartFile file) {
        if (file == null || file.isEmpty()) throw TemporaryCheckinException.badRequest("媒体文件不能为空");
        long limit = kind.maxBytes(properties);
        if (limit <= 0 || file.getSize() <= 0 || file.getSize() > limit) {
            throw TemporaryCheckinException.badRequest("媒体文件超过大小限制");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw TemporaryCheckinException.badRequest("媒体文件读取失败");
        }
        if (bytes.length == 0 || bytes.length > limit) {
            throw TemporaryCheckinException.badRequest("媒体文件超过大小限制");
        }
        DetectedMedia detected = kind.image ? detectImage(bytes) : detectAudio(bytes);
        String declared = normalizeContentType(file.getContentType());
        if (!declared.isEmpty() && !"application/octet-stream".equals(declared)
                && !declaredMatches(declared, detected.contentType())) {
            throw TemporaryCheckinException.badRequest("文件声明类型与实际内容不一致");
        }
        String original = safeFilename(file.getOriginalFilename(), kind.pathValue + detected.extension());
        validateFilenameExtension(original, detected);
        return new ValidatedMedia(bytes, detected.contentType(), detected.extension(), sha256Hex(bytes), original);
    }

    private NormalizedLocation normalizeLocation(LocationCommand location) {
        if (location == null || location.longitude() == null || location.latitude() == null
                || location.accuracyMeters() == null || location.capturedAt() == null) {
            throw TemporaryCheckinException.badRequest("定位经纬度、精度和采集时间不能为空");
        }
        if (location.longitude().compareTo(BigDecimal.valueOf(-180)) < 0
                || location.longitude().compareTo(BigDecimal.valueOf(180)) > 0
                || location.latitude().compareTo(BigDecimal.valueOf(-90)) < 0
                || location.latitude().compareTo(BigDecimal.valueOf(90)) > 0
                || location.accuracyMeters().signum() < 0
                || location.accuracyMeters().compareTo(BigDecimal.valueOf(10_000)) > 0) {
            throw TemporaryCheckinException.badRequest("定位坐标或精度无效");
        }
        Instant now = clock.instant();
        if (location.capturedAt().isAfter(now.plus(Duration.ofMinutes(10)))
                || location.capturedAt().isBefore(now.minus(Duration.ofDays(30)))) {
            throw TemporaryCheckinException.badRequest("定位采集时间超出允许范围");
        }
        return new NormalizedLocation(location.longitude(), location.latitude(), location.accuracyMeters(),
                location.capturedAt(), optional(location.note(), "location.note", 512));
    }

    private static DetectedMedia detectImage(byte[] bytes) {
        if (bytes.length >= 3 && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8
                && unsigned(bytes[2]) == 0xff) {
            return new DetectedMedia("image/jpeg", ".jpg");
        }
        if (bytes.length >= 8 && unsigned(bytes[0]) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
                && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a
                && bytes[6] == 0x1a && bytes[7] == 0x0a) {
            return new DetectedMedia("image/png", ".png");
        }
        if (bytes.length >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) {
            return new DetectedMedia("image/webp", ".webp");
        }
        if (bytes.length >= 12 && ascii(bytes, 4, "ftyp")) {
            String brand = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
            if (Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
                    .contains(brand)) {
                return new DetectedMedia("image/heic", ".heic");
            }
            if ("avif".equals(brand) || "avis".equals(brand)) {
                return new DetectedMedia("image/avif", ".avif");
            }
        }
        throw TemporaryCheckinException.badRequest("照片格式不支持或文件内容损坏");
    }

    private static DetectedMedia detectAudio(byte[] bytes) {
        if (bytes.length >= 4 && ascii(bytes, 0, "OggS")) {
            return new DetectedMedia("audio/ogg", ".ogg");
        }
        if (bytes.length >= 7 && unsigned(bytes[0]) == 0xff && (unsigned(bytes[1]) & 0xf6) == 0xf0) {
            return new DetectedMedia("audio/aac", ".aac");
        }
        if (bytes.length >= 12 && ascii(bytes, 4, "ftyp")) {
            return new DetectedMedia("audio/mp4", ".m4a");
        }
        if (bytes.length >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WAVE")) {
            return new DetectedMedia("audio/wav", ".wav");
        }
        if (bytes.length >= 6 && ascii(bytes, 0, "#!AMR\n")) {
            return new DetectedMedia("audio/amr", ".amr");
        }
        if (bytes.length >= 4 && unsigned(bytes[0]) == 0x1a && unsigned(bytes[1]) == 0x45
                && unsigned(bytes[2]) == 0xdf && unsigned(bytes[3]) == 0xa3) {
            return new DetectedMedia("audio/webm", ".webm");
        }
        if ((bytes.length >= 3 && ascii(bytes, 0, "ID3"))
                || (bytes.length >= 2 && unsigned(bytes[0]) == 0xff && (unsigned(bytes[1]) & 0xe0) == 0xe0)) {
            return new DetectedMedia("audio/mpeg", ".mp3");
        }
        throw TemporaryCheckinException.badRequest("录音格式不支持或文件内容损坏");
    }

    private static boolean declaredMatches(String declared, String detected) {
        if (declared.equals(detected)) return true;
        return ("image/jpg".equals(declared) && "image/jpeg".equals(detected))
                || (("audio/m4a".equals(declared) || "audio/x-m4a".equals(declared))
                && "audio/mp4".equals(detected))
                || ("audio/x-wav".equals(declared) && "audio/wav".equals(detected))
                || (("image/heif".equals(declared) || "image/heic-sequence".equals(declared)
                || "image/heif-sequence".equals(declared))
                && "image/heic".equals(detected));
    }

    private static String normalizeContentType(String value) {
        if (value == null) return "";
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String safeFilename(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "_").trim();
        if (normalized.isEmpty()) return fallback;
        if (normalized.length() <= 200) return normalized;
        int dot = normalized.lastIndexOf('.');
        if (dot > 0 && normalized.length() - dot <= 16) {
            String suffix = normalized.substring(dot);
            return normalized.substring(0, 200 - suffix.length()) + suffix;
        }
        return normalized.substring(0, 200);
    }

    private static void validateFilenameExtension(String filename, DetectedMedia detected) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return; // MediaRecorder Blob 在部分浏览器中只提供无扩展名的文件名。
        if (dot == filename.length() - 1) {
            throw TemporaryCheckinException.badRequest("文件扩展名与实际内容不一致");
        }
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        Set<String> allowed = switch (detected.contentType()) {
            case "image/jpeg" -> Set.of("jpg", "jpeg", "jfif");
            case "image/png" -> Set.of("png");
            case "image/webp" -> Set.of("webp");
            case "image/heic" -> Set.of("heic", "heif");
            case "image/avif" -> Set.of("avif");
            case "audio/aac" -> Set.of("aac");
            case "audio/mp4" -> Set.of("m4a", "mp4", "m4b");
            case "audio/wav" -> Set.of("wav", "wave");
            case "audio/amr" -> Set.of("amr");
            case "audio/webm" -> Set.of("webm");
            case "audio/mpeg" -> Set.of("mp3");
            case "audio/ogg" -> Set.of("ogg", "oga", "opus");
            default -> Set.of();
        };
        if (!allowed.contains(extension)) {
            throw TemporaryCheckinException.badRequest("文件扩展名与实际内容不一致");
        }
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("临时打卡选项序列化失败", exception);
        }
    }

    private boolean jsonListEquals(String json, List<String> expected) {
        if (json == null) return false;
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE).equals(expected);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String validateSubmissionKey(String value) {
        if (value == null || value.length() < 32 || value.length() > 256
                || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl)) {
            throw TemporaryCheckinException.badRequest("submissionKey必须是32到256位的随机字符串");
        }
        return value;
    }

    private static String requiredEnum(String value, List<String> allowed, String field) {
        String normalized = required(value, field, 64);
        if (!allowed.contains(normalized)) {
            throw TemporaryCheckinException.badRequest(field + "不在允许的下拉选项中");
        }
        return normalized;
    }

    private static String optionalEnum(String value, List<String> allowed, String field) {
        String normalized = optional(value, field, 64);
        if (normalized != null && !allowed.contains(normalized)) {
            throw TemporaryCheckinException.badRequest(field + "不在允许的下拉选项中");
        }
        return normalized;
    }

    private static List<String> normalizeList(
            List<String> values, List<String> allowed, String field, boolean required) {
        if (values == null || values.isEmpty()) {
            if (required) throw TemporaryCheckinException.badRequest(field + "至少选择1项");
            return List.of();
        }
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String normalized = requiredEnum(value, allowed, field);
            if (!unique.add(normalized)) throw TemporaryCheckinException.badRequest(field + "不能包含重复项");
        }
        List<String> ordered = new ArrayList<>();
        allowed.stream().filter(unique::contains).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    private static String validateFacilityCount(String value) {
        return required(value, "facilityCount", 128);
    }

    private static String optionalPhone(String value, String field) {
        String normalized = optional(value, field, 32);
        if (normalized != null && !normalized.matches("[0-9+()\\- ]{5,32}")) {
            throw TemporaryCheckinException.badRequest(field + "格式无效");
        }
        return normalized;
    }

    private static String required(String value, String field, int maxLength) {
        String normalized = optional(value, field, maxLength);
        if (normalized == null) throw TemporaryCheckinException.badRequest(field + "不能为空");
        return normalized;
    }

    private static String requiredMultiline(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) throw TemporaryCheckinException.badRequest(field + "不能为空");
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() > maxLength || normalized.chars()
                .anyMatch(character -> Character.isISOControl(character) && character != '\n')) {
            throw TemporaryCheckinException.badRequest(field + "长度或字符无效");
        }
        return normalized;
    }

    private static String optional(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength || normalized.chars().anyMatch(Character::isISOControl)) {
            throw TemporaryCheckinException.badRequest(field + "长度或字符无效");
        }
        return normalized;
    }

    private static BigDecimal optionalCoordinate(
            BigDecimal value, int minimum, int maximum, String field) {
        if (value == null) return null;
        if (value.compareTo(BigDecimal.valueOf(minimum)) < 0
                || value.compareTo(BigDecimal.valueOf(maximum)) > 0) {
            throw TemporaryCheckinException.badRequest(field + "无效");
        }
        return value.setScale(6, java.math.RoundingMode.HALF_UP);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private static MediaReference media(SubmissionRow submission, MediaKind kind) {
        return switch (kind) {
            case STOREFRONT_PHOTO -> submission.storefrontPhoto();
            case WECHAT_SCREENSHOT -> submission.wechatScreenshot();
            case AUDIO -> submission.audio();
        };
    }

    private static boolean hasMedia(MediaReference value) {
        return value != null && value.objectKey() != null && value.sha256() != null && value.sizeBytes() != null;
    }

    private static StoreView storeView(StoreRow row) {
        return new StoreView(row.id(), row.name(), row.city(), storeLocationSummary(row));
    }

    private static String storeLocationSummary(StoreRow row) {
        if (row.sourcePoiAddress() != null && !row.sourcePoiAddress().isBlank()) return row.sourcePoiAddress();
        if (row.locationAddress() != null && !row.locationAddress().isBlank()) return row.locationAddress();
        if (row.locationNote() != null && !row.locationNote().isBlank()) return row.locationNote();
        return "位置已采集";
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.replaceAll("[\\s·•()（）_-]", "").toLowerCase(Locale.ROOT);
    }

    private static double distanceMeters(
            BigDecimal latitude1, BigDecimal longitude1, BigDecimal latitude2, BigDecimal longitude2) {
        double lat1 = Math.toRadians(latitude1.doubleValue());
        double lat2 = Math.toRadians(latitude2.doubleValue());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(longitude2.doubleValue() - longitude1.doubleValue());
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 6_371_000d * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static boolean nullableDecimalEquals(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static boolean sameInstant(Instant left, Instant right) {
        return left != null && right != null
                && left.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                .equals(right.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        if (offset < 0 || offset + expected.length > bytes.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if (bytes[offset + index] != expected[index]) return false;
        }
        return true;
    }

    private static int unsigned(byte value) { return value & 0xff; }

    private static String value(Object value) { return value == null ? "" : String.valueOf(value); }

    private static void appendCsv(StringBuilder target, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) target.append(',');
            String value = values.get(index) == null ? "" : values.get(index);
            if (!value.isEmpty() && (value.charAt(0) == '=' || value.charAt(0) == '+'
                    || value.charAt(0) == '-' || value.charAt(0) == '@' || value.charAt(0) == '\t'
                    || value.charAt(0) == '\r' || value.charAt(0) == '\n')) {
                value = "'" + value;
            }
            target.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        target.append("\r\n");
    }

    private static void validateConfiguration(TemporaryCheckinProperties properties) {
        if (properties.getMaxStorefrontPhotoBytes() <= 0 || properties.getMaxWechatScreenshotBytes() <= 0
                || properties.getMaxAudioBytes() <= 0) {
            throw new IllegalStateException("临时打卡媒体大小限制必须大于0");
        }
        List<List<String>> lists = List.of(properties.getCities(), properties.getStoreAttributes(),
                properties.getOperatingStatuses(), properties.getAreaRanges(), properties.getBusinessTypes(),
                properties.getIntendedBusinesses(), properties.getCooperationIntents(),
                properties.getStoreGrades(), properties.getStoreTags());
        for (List<String> values : lists) {
            if (values.isEmpty() || values.stream().anyMatch(value -> value == null || value.isBlank())
                    || new LinkedHashSet<>(values).size() != values.size()) {
                throw new IllegalStateException("临时打卡下拉选项不能为空或重复");
            }
        }
    }

    public record AdminMedia(
            Supplier<InputStream> opener, long sizeBytes, String contentType, String originalFilename) {
        public InputStream open() {
            return opener.get();
        }
    }

    private record AdminQuery(
            Instant from, Instant toExclusive, String city, UUID salespersonId, String status,
            String escapedQuery) { }

    private record NormalizedLocation(
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters, Instant capturedAt, String note) { }

    private record NormalizedSubmission(
            String city, UUID salespersonId, UUID storeId, String customerName, String customerPhone,
            String visitResult, NormalizedLocation location, boolean privacyAccepted,
            String privacyNoticeVersion) { }

    private record NormalizedStore(
            String city, UUID salespersonId, String sourcePoiId, String sourcePoiName, String sourcePoiAddress,
            BigDecimal sourcePoiLongitude, BigDecimal sourcePoiLatitude,
            String attribute, String name, String operatingStatus,
            String contactName, String contactPhone, String areaRange, String facilityCount,
            List<String> businessTypes, List<String> intendedBusinesses, String cooperationIntent,
            String storeGrade, List<String> tags, NormalizedLocation location) { }

    private record StoreDistance(StoreRow store, double distanceMeters) { }

    private record OptionalStore(boolean present, StoreRow row) { }
    private record DetectedMedia(String contentType, String extension) { }
    private record ValidatedMedia(
            byte[] bytes, String contentType, String extension, String sha256, String originalFilename) { }

    private enum MediaKind {
        STOREFRONT_PHOTO("storefront-photo", "storefront_photo_", "photos/storefront", true),
        WECHAT_SCREENSHOT("wechat-screenshot", "wechat_screenshot_", "screenshots/wechat", true),
        AUDIO("audio", "audio_", "recordings/visit", false);

        private final String pathValue;
        private final String columnPrefix;
        private final String objectDirectory;
        private final boolean image;

        MediaKind(String pathValue, String columnPrefix, String objectDirectory, boolean image) {
            this.pathValue = pathValue;
            this.columnPrefix = columnPrefix;
            this.objectDirectory = objectDirectory;
            this.image = image;
        }

        static MediaKind parse(String value) {
            for (MediaKind kind : values()) {
                if (kind.pathValue.equals(value)) return kind;
            }
            throw TemporaryCheckinException.badRequest(
                    "kind仅支持storefront-photo、wechat-screenshot或audio");
        }

        long maxBytes(TemporaryCheckinProperties properties) {
            return switch (this) {
                case STOREFRONT_PHOTO -> properties.getMaxStorefrontPhotoBytes();
                case WECHAT_SCREENSHOT -> properties.getMaxWechatScreenshotBytes();
                case AUDIO -> properties.getMaxAudioBytes();
            };
        }
    }
}
