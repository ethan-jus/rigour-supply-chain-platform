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
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.MediaDeleteView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.NearbyStoreView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.ResolveLocationRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.MediaUploadView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.OptionsResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.SalespersonOption;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.StoreView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.AdminSubmissionRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.ExportRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.CompletionRiskWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.GeocodeWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.IdentityRiskWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.MediaReference;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.MediaWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.StoreCheckinAnchorRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.StoreRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.StoreWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.SubmissionRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.SubmissionWrite;
import com.rigour.sales.temporarycheckin.TemporaryCheckinSalesIdentityService.AuthorizedRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinSalesIdentityService.RequestRiskFacts;
import com.rigour.sales.temporarycheckin.TemporaryCheckinSalesIdentityService.RiskSnapshot;
import com.rigour.sales.temporarycheckin.TemporaryCheckinReverseGeocoder.GeocodeResult;
import com.rigour.sales.application.port.out.AmapPoiClient;
import com.rigour.sales.application.port.out.AmapPoiException;
import com.rigour.shared.file.FileMetadata;
import com.rigour.shared.file.FileStorage;
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
import java.util.Arrays;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 临时打卡表单用例。租户只使用部署配置；销售身份由服务端验证后与设备绑定，
 * 不信任请求体里单独的 salespersonId。submissionKey 只保存 SHA-256 摘要。
 */
@Service
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
public class TemporaryCheckinService {

    private static final Duration MAX_FUTURE_LOCATION_CLOCK_SKEW = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(TemporaryCheckinService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_EXPORT_ROWS = 20_000;
    public static final String PRIVACY_NOTICE_VERSION = "2026-08-25-identity-v2";
    private static final int NEARBY_LIMIT = 20;
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
    private final TemporaryCheckinAdminAuthRepository adminAuthRepository;
    private final TemporaryCheckinProperties properties;
    private final FileStorage fileStorage;
    private final TemporaryCheckinReverseGeocoder reverseGeocoder;
    private final AmapPoiClient amapPoiClient;
    private final ObjectMapper objectMapper;
    private final TemporaryCheckinSalesIdentityService salesIdentityService;
    private final Clock clock;
    private final UUID tenantId;
    private final boolean aiEnabled;

    public TemporaryCheckinService(
            TemporaryCheckinRepository repository,
            TemporaryCheckinAdminAuthRepository adminAuthRepository,
            TemporaryCheckinProperties properties,
            FileStorage fileStorage,
            TemporaryCheckinReverseGeocoder reverseGeocoder,
            AmapPoiClient amapPoiClient,
            ObjectMapper objectMapper,
            TemporaryCheckinSalesIdentityService salesIdentityService,
            Clock clock,
            ObjectProvider<TemporaryCheckinAiClient> aiClientProvider) {
        this.repository = repository;
        this.adminAuthRepository = adminAuthRepository;
        this.properties = properties;
        this.fileStorage = fileStorage;
        this.reverseGeocoder = reverseGeocoder;
        this.amapPoiClient = amapPoiClient;
        this.objectMapper = objectMapper;
        this.salesIdentityService = salesIdentityService;
        this.clock = clock;
        this.tenantId = properties.requireTenantId();
        this.aiEnabled = aiClientProvider.getIfAvailable() != null;
        validateConfiguration(properties);
    }

    public OptionsResponse options(String city) {
        List<String> configuredCities = activeCities();
        String normalizedCity = optionalEnum(city, configuredCities, "city");
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
        return new OptionsResponse(configuredCities, salespersons, properties.getStoreAttributes(),
                properties.getOperatingStatuses(), properties.getAreaRanges(), properties.getBusinessTypes(),
                properties.getIntendedBusinesses(), properties.getCooperationIntents(),
                properties.getStoreGrades(), properties.getStoreTags());
    }

    public List<StoreView> searchStores(String city, String query, Integer requestedLimit) {
        List<String> configuredCities = activeCities();
        String normalizedCity = requiredEnum(city, configuredCities, "city");
        String normalizedQuery = required(query, "q", 128);
        if (normalizedQuery.length() < 2) throw TemporaryCheckinException.badRequest("q至少输入2个字符");
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 20) throw TemporaryCheckinException.badRequest("limit必须在1到20之间");
        String escaped = normalizedQuery.replace("=", "==").replace("%", "=%").replace("_", "=_");
        return repository.searchStores(tenantId, normalizedCity, escaped, limit).stream()
                .filter(store -> hasCompleteStoreProfile(store, configuredCities))
                .filter(TemporaryCheckinService::hasValidStoreCoordinates)
                .map(TemporaryCheckinService::storeView)
                .toList();
    }

    public LocationContextView resolveLocation(ResolveLocationRequest request) {
        if (request == null) throw TemporaryCheckinException.badRequest("location不能为空");
        List<String> configuredCities = activeCities();
        String city = requiredEnum(request.city(), configuredCities, "city");
        NormalizedLocation location = normalizeLocation(request.location());
        String query = optional(request.q(), "q", 64);
        if (query != null && query.length() < 2) {
            throw TemporaryCheckinException.badRequest("q至少输入2个字符");
        }
        String normalizedNameQuery = normalizeName(query);
        if (query != null && normalizedNameQuery.isEmpty()) {
            throw TemporaryCheckinException.badRequest("q必须包含可搜索的门店名称字符");
        }
        GeocodeResult geocode = reverseGeocoder.resolve(location.longitude(), location.latitude());
        CityMatch cityMatch = cityMatch(city, geocode);
        int maxDistanceMeters = properties.getMaxCheckinDistanceMeters();
        int maxAccuracyMeters = properties.getMaxCheckinAccuracyMeters();
        int maxLocationAgeMinutes = properties.getMaxLocationAgeMinutes();
        boolean accuracyAccepted = hasAcceptableAccuracy(location.accuracyMeters());
        boolean freshnessAccepted = hasAcceptableFreshness(location);
        if (!Boolean.TRUE.equals(cityMatch.matched())) {
            return new LocationContextView(geocode.status(), geocode.address(), geocode.formattedAddress(),
                    geocode.adcode(), cityMatch.matched(), cityMatch.resolvedCity(), cityMatch.message(),
                    maxDistanceMeters, maxAccuracyMeters, maxLocationAgeMinutes,
                    accuracyAccepted, freshnessAccepted, List.of());
        }
        if (!accuracyAccepted) {
            return new LocationContextView(geocode.status(), geocode.address(), geocode.formattedAddress(),
                    geocode.adcode(), cityMatch.matched(), cityMatch.resolvedCity(),
                    accuracyMessage(location.accuracyMeters()), maxDistanceMeters, maxAccuracyMeters,
                    maxLocationAgeMinutes, false, freshnessAccepted, List.of());
        }
        if (!freshnessAccepted) {
            return new LocationContextView(geocode.status(), geocode.address(), geocode.formattedAddress(),
                    geocode.adcode(), cityMatch.matched(), cityMatch.resolvedCity(),
                    freshnessMessage(location), maxDistanceMeters, maxAccuracyMeters,
                    maxLocationAgeMinutes, true, false, List.of());
        }

        List<StoreRow> registeredStores = repository.findActiveStoresByCity(tenantId, city);
        Map<UUID, StoreCheckinAnchorRow> fallbackAnchors = repository
                .findFirstAcceptableSubmittedStoreAnchors(tenantId, city, maxAccuracyMeters).stream()
                .collect(java.util.stream.Collectors.toMap(
                        StoreCheckinAnchorRow::storeId, anchor -> anchor, (first, ignored) -> first));
        List<NearbyStoreView> registeredNearby = registeredStores.stream()
                .filter(store -> hasCompleteStoreProfile(store, configuredCities))
                .filter(store -> query == null || normalizeName(store.name()).contains(normalizedNameQuery))
                .map(store -> new StoreWithAnchor(store,
                        checkinAnchor(store, fallbackAnchors.get(store.id()))))
                .filter(item -> item.anchor() != null)
                .map(item -> new StoreDistance(item.store(), item.anchor(),
                        distanceToAnchor(location, geocode, item.anchor())))
                .filter(item -> item.distanceMeters() != null
                        && item.distanceMeters() <= maxDistanceMeters)
                .sorted(Comparator.comparingDouble(StoreDistance::distanceMeters)
                        .thenComparing(item -> item.store().name()))
                .limit(NEARBY_LIMIT)
                .map(item -> new NearbyStoreView("REGISTERED", item.store().id(), null,
                        item.store().name(), item.store().city(), registeredStoreLocationSummary(
                                item.store(), item.anchor()),
                        null, null, null, item.anchor().source(), true, "CHECK_IN"))
                .toList();

        List<NearbyStoreView> poiNearby = List.of();
        if (hasValidCoordinates(geocode.amapLongitude(), geocode.amapLatitude())) {
            try {
                Set<String> registeredPoiIds = registeredStores.stream()
                        .map(StoreRow::sourcePoiId)
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .collect(java.util.stream.Collectors.toSet());
                Set<String> registeredNames = registeredNearby.stream()
                        .map(item -> normalizeName(item.name())).collect(java.util.stream.Collectors.toSet());
                poiNearby = amapPoiClient.searchAround(query == null ? "" : query,
                                geocode.amapLongitude(), geocode.amapLatitude(),
                                maxDistanceMeters, 1, NEARBY_LIMIT)
                        .items().stream()
                        .filter(poi -> poi.name() != null && !poi.name().isBlank())
                        .filter(poi -> hasText(poi.poiId()))
                        .filter(poi -> !registeredPoiIds.contains(poi.poiId().trim()))
                        .filter(poi -> !registeredNames.contains(normalizeName(poi.name())))
                        .filter(poi -> hasValidCoordinates(poi.longitude(), poi.latitude()))
                        .map(poi -> new PoiDistance(poi, distanceMeters(
                                geocode.amapLatitude(), geocode.amapLongitude(),
                                poi.latitude(), poi.longitude())))
                        .filter(item -> item.distanceMeters() <= maxDistanceMeters)
                        .sorted(Comparator.comparingDouble(PoiDistance::distanceMeters)
                                .thenComparing(item -> item.poi().name()))
                        .limit(NEARBY_LIMIT)
                        .map(item -> new NearbyStoreView("AMAP_POI", null, item.poi().poiId(),
                                item.poi().name(), city, item.poi().address(),
                                BigDecimal.valueOf(item.distanceMeters())
                                        .setScale(0, java.math.RoundingMode.HALF_UP),
                                item.poi().longitude(), item.poi().latitude(), "AMAP_POI", false,
                                "COMPLETE_STORE_PROFILE"))
                        .toList();
            } catch (AmapPoiException ignored) {
                // 可读地址和本地门店仍然可用；高德 POI 暂时失败不阻断现场打卡。
            }
        }
        // 有 POI 时为新店补录保留席位，避免 20 条已录入门店将其全部挤掉。
        int reservedPoiSlots = Math.min(poiNearby.size(), query == null ? 5 : 10);
        int registeredSlots = Math.min(registeredNearby.size(), NEARBY_LIMIT - reservedPoiSlots);
        int poiSlots = Math.min(poiNearby.size(), NEARBY_LIMIT - registeredSlots);
        List<NearbyStoreView> nearby = new ArrayList<>(NEARBY_LIMIT);
        nearby.addAll(registeredNearby.subList(0, registeredSlots));
        nearby.addAll(poiNearby.subList(0, poiSlots));
        return new LocationContextView(geocode.status(), geocode.address(), geocode.formattedAddress(),
                geocode.adcode(), cityMatch.matched(), cityMatch.resolvedCity(), cityMatch.message(),
                maxDistanceMeters, maxAccuracyMeters, maxLocationAgeMinutes,
                true, true, List.copyOf(nearby));
    }

    @Transactional
    public StoreView createStore(CreateStoreRequest request, TemporaryCheckinRequestFacts requestFacts) {
        if (request == null || request.clientStoreId() == null || request.salespersonId() == null) {
            throw TemporaryCheckinException.badRequest("clientStoreId和salespersonId不能为空");
        }
        AuthorizedRequest identity = salesIdentityService.requireSalesperson(request.salespersonId(), requestFacts);
        NormalizedStore normalized = normalizeStore(request);
        var salesperson = identity.salesperson();
        if (!normalized.city().equals(salesperson.city())) {
            throw TemporaryCheckinException.badRequest("销售与选择城市不一致");
        }
        OptionalStore existing = existingStore(request.clientStoreId(), normalized);
        if (existing.present()) return storeView(existing.row());
        requireAcceptableCurrentLocation(normalized.location());

        Instant now = clock.instant();
        GeocodeResult geocode = reverseGeocoder.resolve(
                normalized.location().longitude(), normalized.location().latitude());
        validateResolvedCity(normalized.city(), geocode);
        normalized = verifyNearbyPoi(normalized, geocode);
        OptionalStore existingPoi = existingPoiStore(normalized);
        if (existingPoi.present()) return storeView(existingPoi.row());
        GeocodeWrite geocodeWrite = new GeocodeWrite(geocode.status(), geocode.address(),
                geocode.formattedAddress(), geocode.adcode(), geocode.province(), geocode.city(),
                geocode.district(), geocode.township(), geocode.amapLongitude(), geocode.amapLatitude(),
                geocode.errorCode(), now);
        StoreRow boundImportedStore = bindUniqueImportedStore(normalized, geocodeWrite, now);
        if (boundImportedStore != null) return storeView(boundImportedStore);

        UUID id = UUID.randomUUID();
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
            StoreRow concurrentByClient = repository.findStoreByClientIdForUpdate(
                            tenantId, request.clientStoreId())
                    .orElse(null);
            if (concurrentByClient != null) {
                assertSameStore(concurrentByClient, normalized);
                return storeView(concurrentByClient);
            }
            OptionalStore concurrentByPoi = existingPoiStoreForUpdate(normalized);
            if (concurrentByPoi.present()) return storeView(concurrentByPoi.row());
            throw TemporaryCheckinException.conflict("门店创建冲突，请刷新后重试");
        }
        return repository.findStore(tenantId, id).map(TemporaryCheckinService::storeView)
                .orElseThrow(() -> new IllegalStateException("门店写入后不可见"));
    }

    @Transactional
    public DraftSubmissionView createDraft(
            CreateSubmissionRequest request, TemporaryCheckinRequestFacts requestFacts) {
        if (request == null || request.clientSubmissionId() == null || request.salespersonId() == null
                || request.storeId() == null) {
            throw TemporaryCheckinException.badRequest(
                    "clientSubmissionId、salespersonId和storeId不能为空");
        }
        String key = validateSubmissionKey(request.submissionKey());
        AuthorizedRequest identity = salesIdentityService.requireSalesperson(request.salespersonId(), requestFacts);
        String keyHash = sha256Hex(key.getBytes(StandardCharsets.UTF_8));
        if (!Boolean.TRUE.equals(request.privacyAccepted())) {
            throw TemporaryCheckinException.badRequest("必须明确同意定位、照片、录音及转写说明");
        }
        if (!PRIVACY_NOTICE_VERSION.equals(request.privacyNoticeVersion())) {
            throw TemporaryCheckinException.badRequest("隐私提示版本已更新，请刷新页面后重新确认");
        }
        String city = requiredEnum(request.city(), activeCities(), "city");
        NormalizedSubmission normalized = new NormalizedSubmission(city, request.salespersonId(), request.storeId(),
                required(request.customerName(), "customerName", 128),
                optionalPhone(request.customerPhone(), "customerPhone"),
                requiredMultiline(request.visitResult(), "visitResult", 2000),
                normalizeLocation(request.location()), true, request.privacyNoticeVersion());
        var salesperson = identity.salesperson();
        if (!city.equals(salesperson.city())) {
            throw TemporaryCheckinException.badRequest("销售与选择城市不一致");
        }
        SubmissionRow existing = repository.findSubmissionByClientId(tenantId, request.clientSubmissionId())
                .orElse(null);
        if (existing != null) {
            salesIdentityService.requireSubmission(
                    existing.salespersonId(), existing.deviceTokenHash(), requestFacts);
            requireMatchingKey(existing, key);
            assertSameSubmission(existing, normalized);
            return new DraftSubmissionView(existing.id(), existing.status(), existing.createdAt());
        }
        requireAcceptableCurrentLocation(normalized.location());
        StoreRow store = repository.findStore(tenantId, request.storeId())
                .orElseThrow(() -> TemporaryCheckinException.notFound("门店不存在或已停用"));
        if (!city.equals(store.city())) {
            throw TemporaryCheckinException.badRequest("门店与选择城市不一致");
        }
        GeocodeResult geocode = reverseGeocoder.resolve(
                normalized.location().longitude(), normalized.location().latitude());
        validateResolvedCity(city, geocode);
        requireEligibleCheckinStore(city, store, normalized.location(), geocode);
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        GeocodeWrite geocodeWrite = new GeocodeWrite(geocode.status(), geocode.address(),
                geocode.formattedAddress(), geocode.adcode(), geocode.province(), geocode.city(),
                geocode.district(), geocode.township(), geocode.amapLongitude(), geocode.amapLatitude(),
                geocode.errorCode(), now);
        RiskSnapshot risk = salesIdentityService.evaluateRisk(identity);
        RequestRiskFacts riskFacts = risk.requestFacts();
        IdentityRiskWrite identityWrite = new IdentityRiskWrite(identity.identityMethod(), identity.verifiedAt(),
                salesperson.credentialVersion(), identity.deviceTokenHash(),
                riskFacts == null ? null : riskFacts.ipHash(),
                riskFacts == null ? null : riskFacts.ipNetworkHash(),
                riskFacts == null ? null : riskFacts.ipMasked(),
                riskFacts == null ? null : riskFacts.userAgentHash(),
                riskFacts == null ? null : riskFacts.userAgentSummary(),
                risk.level(), writeJson(risk.flags()), risk.evaluatedAt());
        SubmissionWrite write = new SubmissionWrite(id, tenantId, request.clientSubmissionId(), keyHash,
                city, salesperson.id(), salesperson.name(), store.id(), store.name(),
                normalized.customerName(), normalized.customerPhone(), normalized.visitResult(),
                normalized.location().longitude(), normalized.location().latitude(),
                normalized.location().accuracyMeters(), normalized.location().capturedAt(),
                normalized.location().note(), geocodeWrite, normalized.privacyNoticeVersion(), identityWrite, now);
        try {
            repository.insertSubmission(write);
        } catch (DataIntegrityViolationException duplicate) {
            SubmissionRow concurrent = repository.findSubmissionByClientId(tenantId, request.clientSubmissionId())
                    .orElseThrow(() -> TemporaryCheckinException.conflict("草稿创建冲突，请重试"));
            salesIdentityService.requireSubmission(
                    concurrent.salespersonId(), concurrent.deviceTokenHash(), requestFacts);
            requireMatchingKey(concurrent, key);
            assertSameSubmission(concurrent, normalized);
            return new DraftSubmissionView(concurrent.id(), concurrent.status(), concurrent.createdAt());
        }
        return new DraftSubmissionView(id, "DRAFT", now);
    }

    @Transactional
    public MediaUploadView uploadMedia(
            UUID submissionId, String rawKind, String submissionKey, MultipartFile file,
            TemporaryCheckinRequestFacts requestFacts) {
        if (submissionId == null) throw TemporaryCheckinException.badRequest("submissionId不能为空");
        MediaKind kind = MediaKind.parse(rawKind);
        SubmissionRow submission = repository.findSubmissionForUpdate(tenantId, submissionId)
                .orElseThrow(() -> TemporaryCheckinException.notFound("打卡草稿不存在"));
        salesIdentityService.requireSubmission(
                submission.salespersonId(), submission.deviceTokenHash(), requestFacts);
        requireMatchingKey(submission, submissionKey);
        if (!"DRAFT".equals(submission.status())) {
            throw TemporaryCheckinException.conflict("已提交的打卡不允许替换媒体");
        }
        ValidatedMedia validated = validateMedia(kind, file);
        MediaReference previous = media(submission, kind);
        if (previous.objectKey() != null && previous.deletedAt() == null
                && validated.sha256().equals(previous.sha256())) {
            return new MediaUploadView(submissionId, kind.pathValue, "DRAFT", validated.sha256(),
                    validated.sizeBytes());
        }

        String objectKey = tenantId + "/temporary-sales-checkin/" + submissionId + "/"
                + kind.objectDirectory + "/" + validated.sha256() + validated.extension();
        Instant now = clock.instant();
        try (InputStream content = validated.file().getInputStream()) {
            fileStorage.put(new FileMetadata(tenantId.toString(), objectKey, validated.originalFilename(),
                    validated.contentType(), validated.sizeBytes(), validated.sha256(),
                    OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC)),
                    content);
        } catch (IOException exception) {
            throw TemporaryCheckinException.storage("媒体文件读取失败，请重新选择后上传");
        } catch (RuntimeException exception) {
            throw TemporaryCheckinException.storage("媒体文件存储失败，请稍后重试");
        }
        int updated;
        try {
            updated = repository.updateMedia(tenantId, submissionId, kind.columnPrefix,
                    new MediaWrite(objectKey, validated.contentType(), validated.sizeBytes(),
                            validated.sha256(), validated.originalFilename()), now);
        } catch (RuntimeException exception) {
            cleanupUnreferencedObject(objectKey, submissionId, kind, "数据库更新异常");
            throw exception;
        }
        if (updated != 1) {
            cleanupUnreferencedObject(objectKey, submissionId, kind, "数据库未接受更新");
            throw TemporaryCheckinException.conflict("草稿状态已变化，请刷新后重试");
        }
        registerMediaObjectLifecycle(submissionId, kind, objectKey, previous.objectKey());
        return new MediaUploadView(submissionId, kind.pathValue, "DRAFT", validated.sha256(),
                validated.sizeBytes());
    }

    private void registerMediaObjectLifecycle(
            UUID submissionId, MediaKind kind, String newObjectKey, String previousObjectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            if (previousObjectKey != null && !previousObjectKey.equals(newObjectKey)) {
                cleanupUnreferencedObject(previousObjectKey, submissionId, kind, "旧媒体替换完成");
            }
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (previousObjectKey != null && !previousObjectKey.equals(newObjectKey)) {
                    cleanupUnreferencedObject(previousObjectKey, submissionId, kind, "旧媒体替换完成");
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    cleanupUnreferencedObject(newObjectKey, submissionId, kind, "数据库事务未提交");
                }
            }
        });
    }

    private void cleanupUnreferencedObject(
            String objectKey, UUID submissionId, MediaKind kind, String reason) {
        try {
            fileStorage.delete(tenantId.toString(), objectKey);
        } catch (RuntimeException exception) {
            log.warn("临时打卡未引用媒体清理失败 submissionId={} kind={} reason={} error={}",
                    submissionId, kind.pathValue, reason, exception.getClass().getSimpleName());
        }
    }

    @Transactional
    public MediaDeleteView deleteDraftMedia(
            UUID submissionId, String rawKind, String submissionKey,
            TemporaryCheckinRequestFacts requestFacts) {
        if (submissionId == null) throw TemporaryCheckinException.badRequest("submissionId不能为空");
        MediaKind kind = MediaKind.parse(rawKind);
        SubmissionRow submission = repository.findSubmissionForUpdate(tenantId, submissionId)
                .orElseThrow(() -> TemporaryCheckinException.notFound("打卡草稿不存在"));
        salesIdentityService.requireSubmission(
                submission.salespersonId(), submission.deviceTokenHash(), requestFacts);
        requireMatchingKey(submission, submissionKey);
        if (!"DRAFT".equals(submission.status())) {
            throw TemporaryCheckinException.conflict("已提交的打卡不允许删除媒体");
        }
        MediaReference current = media(submission, kind);
        if (current == null || current.objectKey() == null) {
            return new MediaDeleteView(submissionId, kind.pathValue, "DELETED");
        }
        try {
            fileStorage.delete(tenantId.toString(), current.objectKey());
        } catch (RuntimeException exception) {
            throw TemporaryCheckinException.storage("媒体文件物理删除失败，请稍后重试");
        }
        int updated = repository.clearDraftMedia(
                tenantId, submissionId, kind.columnPrefix, current.objectKey(), clock.instant());
        if (updated != 1) {
            throw TemporaryCheckinException.conflict("媒体引用清理冲突，请立即联系管理员核对");
        }
        return new MediaDeleteView(submissionId, kind.pathValue, "DELETED");
    }

    @Transactional
    public CompletedSubmissionView complete(
            UUID submissionId, String submissionKey, TemporaryCheckinRequestFacts requestFacts) {
        SubmissionRow submission = requireSubmission(submissionId);
        AuthorizedRequest identity = salesIdentityService.requireSubmission(
                submission.salespersonId(), submission.deviceTokenHash(), requestFacts);
        requireMatchingKey(submission, submissionKey);
        if ("SUBMITTED".equals(submission.status())) {
            queueTranscriptionIfEligible(submission);
            return new CompletedSubmissionView(submission.id(), submission.status(), submission.submittedAt());
        }
        if (!hasMedia(submission.storefrontPhoto())) {
            throw TemporaryCheckinException.badRequest("请先上传门头照");
        }
        Instant submittedAt = clock.instant();
        RiskSnapshot risk = salesIdentityService.evaluateRisk(identity);
        RequestRiskFacts facts = risk.requestFacts();
        CompletionRiskWrite completionRisk = new CompletionRiskWrite(
                facts == null ? null : facts.ipHash(), facts == null ? null : facts.ipNetworkHash(),
                facts == null ? null : facts.ipMasked(), facts == null ? null : facts.userAgentHash(),
                facts == null ? null : facts.userAgentSummary(), risk.level(), writeJson(risk.flags()),
                risk.evaluatedAt());
        if (repository.complete(tenantId, submissionId, submittedAt, completionRisk) != 1) {
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
        List<String> cities = scope.allCities() ? activeCities() : List.of(scopedCity);
        List<SalespersonOption> salespersons = repository.findSalespersons(tenantId, scopedCity).stream()
                .filter(row -> cities.contains(row.city()))
                .map(row -> new SalespersonOption(row.id(), row.name(), row.city()))
                .toList();
        var stats = repository.mediaStorageStats(tenantId, scopedCity);
        return new AdminOptionsResponse(scopeView(scope), cities, salespersons,
                new AdminMediaStorageStats(stats.activeFiles(), stats.totalBytes(), stats.imageBytes(),
                        stats.audioBytes(), stats.oldestCreatedAt()), aiEnabled);
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
                .map(this::adminSubmissionView)
                .toList();
        long pageCount = total == 0 ? 0 : ((total - 1) / size) + 1;
        int totalPages = (int) Math.min(Integer.MAX_VALUE, pageCount);
        return new AdminSubmissionPage(scopeView(scope), items, total, total, page, size, totalPages);
    }

    public String exportCsv(
            AdminScope scope, LocalDate from, LocalDate to, String city, UUID salespersonId,
            String status, String query) {
        AdminQuery filters = normalizeAdminQuery(scope, from, to, city, salespersonId, status, query);
        List<ExportRow> rows = repository.export(tenantId, filters.from(), filters.toExclusive(), filters.city(),
                filters.salespersonId(), filters.status(), filters.escapedQuery(), MAX_EXPORT_ROWS + 1);
        if (rows.size() > MAX_EXPORT_ROWS) {
            throw TemporaryCheckinException.badRequest("导出超过20000条，请缩小日期或城市范围");
        }
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendCsv(csv, List.of("submission_id", "client_submission_id", "status", "city",
                "salesperson_id", "salesperson_name", "store_id", "store_name",
                "visit_ordinal", "visit_type", "revisit_number", "customer_name", "customer_phone", "visit_result",
                "longitude", "latitude", "accuracy_meters",
                "location_captured_at", "location_note", "location_address", "location_adcode",
                "identity_method", "submitted_ip_masked", "user_agent_summary", "risk_level", "risk_flags",
                "storefront_photo", "wechat_screenshot", "audio", "transcription_status", "transcript",
                "summary_status", "summary", "created_at", "submitted_at"));
        for (ExportRow row : rows) {
            appendCsv(csv, List.of(value(row.id()), value(row.clientSubmissionId()), value(row.status()),
                    value(row.city()), value(row.salespersonId()), value(row.salespersonName()),
                    value(row.storeId()), value(row.storeName()), value(row.visitOrdinal()),
                    value(visitType(row.visitOrdinal())), value(revisitNumber(row.visitOrdinal())),
                    value(row.customerName()),
                    value(row.customerPhone()), value(row.visitResult()), value(row.longitude()),
                    value(row.latitude()), value(row.accuracyMeters()), value(row.locationCapturedAt()),
                    value(row.locationNote()), value(row.locationAddress()), value(row.locationAdcode()),
                    value(row.identityMethod()), value(row.submittedIpMasked()), value(row.userAgentSummary()),
                    value(row.riskLevel()), value(String.join("|", safeRiskFlags(row.riskFlagsJson()))),
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

    private void requireEligibleCheckinStore(
            String city, StoreRow store, NormalizedLocation checkinLocation, GeocodeResult geocode) {
        if (!city.equals(store.city())) {
            throw TemporaryCheckinException.badRequest("门店与选择城市不一致");
        }
        if (!hasCompleteStoreProfile(store, activeCities())) {
            throw TemporaryCheckinException.badRequest("门店基础资料不完整，请先补全门店信息");
        }
        CheckinAnchor anchor = checkinAnchor(store, null);
        if (anchor == null) {
            StoreCheckinAnchorRow fallback = repository.findFirstAcceptableSubmittedStoreAnchor(
                    tenantId, store.id(), city, properties.getMaxCheckinAccuracyMeters()).orElse(null);
            anchor = checkinAnchor(store, fallback);
        }
        if (anchor == null) {
            throw TemporaryCheckinException.badRequest("门店缺少有效定位，请先补录门店定位");
        }
        Double distance = distanceToAnchor(checkinLocation, geocode, anchor);
        if (distance == null) {
            throw TemporaryCheckinException.badRequest("当前定位无法完成高德门店距离校验，请重新定位后再试");
        }
        int maximum = properties.getMaxCheckinDistanceMeters();
        if (distance > maximum) {
            throw TemporaryCheckinException.badRequest("当前定位距离门店约"
                    + (long) Math.ceil(distance) + "米，超过允许的" + maximum + "米，请到店后重新定位");
        }
    }

    /**
     * 高德门店使用服务端复核的 GCJ-02 POI 坐标；普通门店使用 WGS84 门店坐标。
     * 两者都不可用时，才候选首次符合精度要求的已提交拜访 WGS84 坐标作为固定锚点。
     * 该坐标是匿名自报数据，不静默回写门店档案，不能被解释为可信门店位置或考勤证据。
     */
    private CheckinAnchor checkinAnchor(StoreRow store, StoreCheckinAnchorRow fallback) {
        if (hasText(store.sourcePoiId())
                && hasValidCoordinates(store.sourcePoiLongitude(), store.sourcePoiLatitude())) {
            return new CheckinAnchor(store.sourcePoiLongitude(), store.sourcePoiLatitude(),
                    null, "AMAP_POI", CoordinateSystem.GCJ02);
        }
        if (hasUsableStoreCoordinates(store)) {
            return new CheckinAnchor(store.longitude(), store.latitude(),
                    store.accuracyMeters(), "STORE_LOCATION", CoordinateSystem.WGS84);
        }
        if (fallback == null || !hasValidCoordinates(fallback.longitude(), fallback.latitude())
                || !hasAcceptableAccuracy(fallback.accuracyMeters())) {
            return null;
        }
        return new CheckinAnchor(fallback.longitude(), fallback.latitude(), fallback.accuracyMeters(),
                "FIRST_SUBMITTED_VISIT", CoordinateSystem.WGS84);
    }

    private static Double distanceToAnchor(
            NormalizedLocation location, GeocodeResult geocode, CheckinAnchor anchor) {
        if (anchor.coordinateSystem() == CoordinateSystem.GCJ02) {
            if (geocode == null
                    || !hasValidCoordinates(geocode.amapLongitude(), geocode.amapLatitude())) {
                return null;
            }
            return distanceMeters(geocode.amapLatitude(), geocode.amapLongitude(),
                    anchor.latitude(), anchor.longitude());
        }
        return distanceMeters(location.latitude(), location.longitude(),
                anchor.latitude(), anchor.longitude());
    }

    private boolean hasAcceptableAccuracy(BigDecimal accuracyMeters) {
        return accuracyMeters != null && accuracyMeters.signum() >= 0
                && accuracyMeters.compareTo(BigDecimal.valueOf(properties.getMaxCheckinAccuracyMeters())) <= 0;
    }

    private void requireAcceptableCurrentLocation(NormalizedLocation location) {
        if (!hasAcceptableAccuracy(location.accuracyMeters())) {
            throw TemporaryCheckinException.badRequest(accuracyMessage(location.accuracyMeters()));
        }
        if (!hasAcceptableFreshness(location)) {
            throw TemporaryCheckinException.badRequest(freshnessMessage(location));
        }
    }

    private boolean hasAcceptableFreshness(NormalizedLocation location) {
        Instant now = clock.instant();
        return !location.capturedAt().isBefore(
                now.minus(Duration.ofMinutes(properties.getMaxLocationAgeMinutes())))
                && !location.capturedAt().isAfter(now.plus(MAX_FUTURE_LOCATION_CLOCK_SKEW));
    }

    private String freshnessMessage(NormalizedLocation location) {
        if (location.capturedAt().isAfter(clock.instant().plus(MAX_FUTURE_LOCATION_CLOCK_SKEW))) {
            return "定位采集时间晚于服务器时间超过2分钟，请校准手机时间并重新定位";
        }
        return "定位采集时间已超过" + properties.getMaxLocationAgeMinutes()
                + "分钟，请重新定位后提交";
    }

    private String accuracyMessage(BigDecimal accuracyMeters) {
        return "当前定位精度约"
                + accuracyMeters.setScale(0, java.math.RoundingMode.CEILING).toPlainString()
                + "米，超过允许的" + properties.getMaxCheckinAccuracyMeters()
                + "米，请到室外或开阔处重新定位";
    }

    private boolean hasCompleteStoreProfile(StoreRow store, List<String> configuredCities) {
        return store != null
                && "ACTIVE".equals(store.status())
                && configuredCities.contains(store.city())
                && properties.getStoreAttributes().contains(store.attribute())
                && hasText(store.name())
                && properties.getOperatingStatuses().contains(store.operatingStatus())
                && hasText(store.contactName())
                && properties.getAreaRanges().contains(store.areaRange())
                && hasText(store.facilityCount())
                && hasConfiguredJsonList(store.businessTypesJson(), properties.getBusinessTypes())
                && hasConfiguredJsonList(store.intendedBusinessesJson(), properties.getIntendedBusinesses())
                && properties.getCooperationIntents().contains(store.cooperationIntent())
                && hasConfiguredJsonList(store.tagsJson(), properties.getStoreTags());
    }

    private boolean hasConfiguredJsonList(String json, List<String> allowed) {
        if (!hasText(json)) return false;
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST_TYPE);
            return values != null && !values.isEmpty()
                    && values.stream().allMatch(value -> value != null && allowed.contains(value))
                    && new HashSet<>(values).size() == values.size();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hasValidStoreCoordinates(StoreRow store) {
        return store != null && hasValidCoordinates(store.longitude(), store.latitude());
    }

    private boolean hasUsableStoreCoordinates(StoreRow store) {
        return hasValidStoreCoordinates(store) && hasAcceptableAccuracy(store.accuracyMeters());
    }

    private static boolean hasValidCoordinates(BigDecimal longitude, BigDecimal latitude) {
        if (longitude == null || latitude == null
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
            return false;
        }
        return longitude.signum() != 0 || latitude.signum() != 0;
    }

    private static void validateResolvedCity(String expectedCity, GeocodeResult geocode) {
        if (geocode == null || !"RESOLVED".equals(geocode.status())) {
            throw TemporaryCheckinException.badRequest("当前定位地址解析失败，请重新定位后再提交");
        }
        CityMatch match = cityMatch(expectedCity, geocode);
        if (Boolean.FALSE.equals(match.matched())) {
            throw TemporaryCheckinException.badRequest("当前定位不在所选城市，请重新选择城市并定位");
        }
    }

    private static CityMatch cityMatch(String expectedCity, GeocodeResult geocode) {
        if (geocode == null || !"RESOLVED".equals(geocode.status())) {
            return new CityMatch(null, null, "地址暂未解析，定位坐标已记录");
        }
        String actualCity = firstText(geocode.city(), geocode.province());
        String resolvedCity = actualCity == null ? null : normalizeCityName(actualCity);
        String expectedAdcodePrefix = CITY_ADCODE_PREFIXES.get(expectedCity);
        if (expectedAdcodePrefix != null && geocode.adcode() != null && !geocode.adcode().isBlank()) {
            if (!geocode.adcode().trim().startsWith(expectedAdcodePrefix)) {
                return new CityMatch(false, resolvedCity, cityMismatchMessage(resolvedCity));
            }
            return new CityMatch(true, resolvedCity, null);
        }
        if (actualCity != null && !normalizeCityName(expectedCity).equals(normalizeCityName(actualCity))) {
            return new CityMatch(false, resolvedCity, cityMismatchMessage(resolvedCity));
        }
        return new CityMatch(true, resolvedCity, null);
    }

    private static String cityMismatchMessage(String resolvedCity) {
        return resolvedCity == null
                ? "当前定位不在所选城市，请切换到实际城市后重新定位"
                : "当前位置在" + resolvedCity + "，请将城市切换为" + resolvedCity + "后重新定位";
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
        String requestedCity = optionalEnum(city, activeCities(), "city");
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
        if (!activeCities().contains(scope.city())) {
            throw TemporaryCheckinException.adminForbidden("后台账号城市未启用");
        }
        return scope.city();
    }

    /**
     * 创建门店不信任浏览器回传的 POI 坐标和摘要：用服务端转换的 GCJ-02
     * 坐标重新查询高德，并且只接受指定距离内的精确 poiId。
     */
    private NormalizedStore verifyNearbyPoi(NormalizedStore store, GeocodeResult geocode) {
        if (!hasValidCoordinates(geocode.amapLongitude(), geocode.amapLatitude())) {
            throw TemporaryCheckinException.badRequest("当前定位无法完成附近门店校验，请重新定位后再试");
        }
        String keyword = store.sourcePoiId() == null ? store.name() : store.sourcePoiName();
        AmapPoiClient.NearbyPoiPage page;
        try {
            page = amapPoiClient.searchAround(keyword, geocode.amapLongitude(), geocode.amapLatitude(),
                    properties.getMaxCheckinDistanceMeters(), 1, NEARBY_LIMIT);
        } catch (AmapPoiException exception) {
            throw TemporaryCheckinException.badRequest("附近门店校验暂不可用，请稍后重新定位再试");
        }
        List<PoiDistance> candidates = page.items().stream()
                .filter(poi -> hasText(poi.poiId()) && hasText(poi.name()))
                .filter(poi -> hasValidCoordinates(poi.longitude(), poi.latitude()))
                .map(poi -> new PoiDistance(poi, distanceMeters(
                        geocode.amapLatitude(), geocode.amapLongitude(),
                        poi.latitude(), poi.longitude())))
                .filter(item -> item.distanceMeters() <= properties.getMaxCheckinDistanceMeters())
                .toList();
        if (store.sourcePoiId() == null) {
            String requestedName = normalizeName(store.name());
            PoiDistance matching = candidates.stream()
                    .filter(item -> requestedName.equals(normalizeName(item.poi().name())))
                    .findFirst().orElse(null);
            if (matching != null) {
                throw TemporaryCheckinException.conflict("附近已有高德门店“"
                        + matching.poi().name() + "”，请从附近门店下拉列表选择后补全资料");
            }
            return store;
        }
        PoiDistance selected = candidates.stream()
                .filter(item -> store.sourcePoiId().equals(item.poi().poiId().trim()))
                .findFirst()
                .orElseThrow(() -> TemporaryCheckinException.badRequest(
                        "所选高德门店不在当前位置的允许距离内，请重新定位后选择"));
        if (!normalizeName(store.sourcePoiName()).equals(normalizeName(selected.poi().name()))) {
            throw TemporaryCheckinException.badRequest("高德门店候选信息已变化，请重新定位后从下拉列表选择");
        }
        return withVerifiedPoi(store, selected.poi());
    }

    private static NormalizedStore withVerifiedPoi(
            NormalizedStore store, AmapPoiClient.NearbyPoi poi) {
        return new NormalizedStore(store.city(), store.salespersonId(),
                required(poi.poiId(), "sourcePoiId", 128), required(poi.name(), "sourcePoiName", 256),
                optional(poi.address(), "sourcePoiAddress", 512),
                poi.longitude().setScale(6, java.math.RoundingMode.HALF_UP),
                poi.latitude().setScale(6, java.math.RoundingMode.HALF_UP),
                store.attribute(), required(poi.name(), "name", 256), store.operatingStatus(), store.contactName(),
                store.contactPhone(), store.areaRange(), store.facilityCount(), store.businessTypes(),
                store.intendedBusinesses(), store.cooperationIntent(), store.storeGrade(), store.tags(),
                store.location());
    }

    /**
     * 仅当用户明确选择了服务端复核的 POI 时，才将唯一同名的无定位导入门店原子绑定。
     * 手填路径不能凭名称吞并历史门店，必须改选 POI 或由管理员核对。
     * 不覆盖飞书导入的业务资料；多条同名时不做猜测。
     */
    private StoreRow bindUniqueImportedStore(
            NormalizedStore store, GeocodeWrite geocode, Instant now) {
        Set<String> matchingNames = new HashSet<>();
        matchingNames.add(normalizeName(store.name()));
        if (store.sourcePoiName() != null) matchingNames.add(normalizeName(store.sourcePoiName()));
        matchingNames.remove("");
        List<StoreRow> matches = repository.findActiveStoresByCityForUpdate(tenantId, store.city()).stream()
                .filter(row -> matchingNames.contains(normalizeName(row.name())))
                .toList();
        if (store.sourcePoiId() == null && !matches.isEmpty()) {
            throw TemporaryCheckinException.conflict(
                    "已存在同名历史门店，手工录入不能自动绑定；请从附近门店选择高德POI或联系管理员核对");
        }
        if (matches.size() > 1) {
            throw TemporaryCheckinException.conflict(
                    "发现多条同名历史门店，无法安全自动绑定；请联系管理员合并后重试");
        }
        if (matches.isEmpty()) return null;
        StoreRow existing = matches.getFirst();
        if (hasText(existing.sourcePoiId())) {
            if (Objects.equals(existing.sourcePoiId(), store.sourcePoiId())) return existing;
            throw TemporaryCheckinException.conflict("同名门店已绑定其他高德门店，请联系管理员核对");
        }
        StoreCheckinAnchorRow fallback = repository.findFirstAcceptableSubmittedStoreAnchor(
                tenantId, existing.id(), store.city(), properties.getMaxCheckinAccuracyMeters())
                .orElse(null);
        CheckinAnchor existingAnchor = checkinAnchor(existing, fallback);
        if (existingAnchor != null) {
            throw TemporaryCheckinException.conflict(
                    "同名门店已有可用定位，请直接选择已有门店打卡；如不是同一家店请联系管理员核对");
        }
        int updated;
        try {
            updated = repository.bindLocationAndOptionalVerifiedPoi(
                    tenantId, existing.id(), store.sourcePoiId(), store.sourcePoiName(),
                    store.sourcePoiAddress(), store.sourcePoiLongitude(), store.sourcePoiLatitude(),
                    store.location().longitude(), store.location().latitude(), store.location().accuracyMeters(),
                    store.location().capturedAt(), store.location().note(), geocode, now);
        } catch (DataIntegrityViolationException duplicatePoi) {
            OptionalStore concurrent = existingPoiStoreForUpdate(store);
            if (concurrent.present()) return concurrent.row();
            throw TemporaryCheckinException.conflict("高德门店绑定冲突，请刷新后重试");
        }
        if (updated != 1) {
            throw TemporaryCheckinException.conflict("历史门店状态已变化，请刷新后重试");
        }
        return repository.findStore(tenantId, existing.id())
                .orElseThrow(() -> new IllegalStateException("门店绑定后不可见"));
    }

    private static AdminScopeView scopeView(AdminScope scope) {
        return new AdminScopeView(scope.username(), scope.allCities(), scope.city());
    }

    private AdminSubmissionView adminSubmissionView(AdminSubmissionRow row) {
        return new AdminSubmissionView(row.id(), row.status(), row.city(), row.salespersonId(),
                row.salespersonName(), row.storeId(), row.storeName(), row.visitOrdinal(),
                visitType(row.visitOrdinal()), revisitNumber(row.visitOrdinal()),
                row.customerName(), row.customerPhone(),
                row.visitResult(), row.longitude(), row.latitude(), row.accuracyMeters(),
                row.locationCapturedAt(), row.locationNote(), row.locationAddress(), row.locationAdcode(),
                row.identityMethod(), row.submittedIpMasked(), row.userAgentSummary(), row.riskLevel(),
                safeRiskFlags(row.riskFlagsJson()),
                row.storefrontPhotoAvailable(), row.wechatScreenshotAvailable(), row.audioAvailable(),
                row.storefrontPhotoDeletedAt(), row.wechatScreenshotDeletedAt(), row.audioDeletedAt(),
                row.transcriptionStatus(), row.transcript(), row.transcriptionErrorCode(),
                row.summaryStatus(), row.summaryText(), row.summaryErrorCode(),
                row.createdAt(), row.submittedAt());
    }

    private List<String> safeRiskFlags(String json) {
        if (!hasText(json)) return List.of();
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST_TYPE);
            if (values == null || values.isEmpty()) return List.of();
            return values.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty() && value.length() <= 64)
                    .distinct()
                    .limit(20)
                    .toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private static String visitType(Long visitOrdinal) {
        if (visitOrdinal == null) return null;
        return visitOrdinal == 1 ? "FIRST_VISIT" : "REVISIT";
    }

    private static Long revisitNumber(Long visitOrdinal) {
        if (visitOrdinal == null) return null;
        return Math.max(0, visitOrdinal - 1);
    }

    private OptionalStore existingStore(UUID clientStoreId, NormalizedStore normalized) {
        StoreRow row = repository.findStoreByClientId(tenantId, clientStoreId).orElse(null);
        if (row == null) return new OptionalStore(false, null);
        assertSameStore(row, normalized);
        return new OptionalStore(true, row);
    }

    private OptionalStore existingPoiStore(NormalizedStore normalized) {
        if (normalized.sourcePoiId() == null) return new OptionalStore(false, null);
        StoreRow row = repository.findStoreBySourcePoiId(tenantId, normalized.sourcePoiId()).orElse(null);
        return reusablePoiStore(row, normalized);
    }

    private static OptionalStore reusablePoiStore(StoreRow row, NormalizedStore normalized) {
        if (row == null) return new OptionalStore(false, null);
        if (!"ACTIVE".equals(row.status())) {
            throw TemporaryCheckinException.conflict("该高德门店已录入但已停用，请联系管理员");
        }
        if (!Objects.equals(row.city(), normalized.city())) {
            throw TemporaryCheckinException.conflict("该高德门店已录入到其他城市，请联系管理员");
        }
        return new OptionalStore(true, row);
    }

    private OptionalStore existingPoiStoreForUpdate(NormalizedStore normalized) {
        if (normalized.sourcePoiId() == null) return new OptionalStore(false, null);
        StoreRow row = repository.findStoreBySourcePoiIdForUpdate(tenantId, normalized.sourcePoiId())
                .orElse(null);
        return reusablePoiStore(row, normalized);
    }

    private void assertSameStore(StoreRow row, NormalizedStore normalized) {
        boolean verifiedPoiRequest = normalized.sourcePoiId() != null;
        if (!Objects.equals(row.city(), normalized.city())
                || !Objects.equals(row.creatorSalespersonId(), normalized.salespersonId())
                || !Objects.equals(row.sourcePoiId(), normalized.sourcePoiId())
                || (!verifiedPoiRequest && (!Objects.equals(row.sourcePoiName(), normalized.sourcePoiName())
                        || !Objects.equals(row.sourcePoiAddress(), normalized.sourcePoiAddress())
                        || !nullableDecimalEquals(row.sourcePoiLongitude(), normalized.sourcePoiLongitude())
                        || !nullableDecimalEquals(row.sourcePoiLatitude(), normalized.sourcePoiLatitude())))
                || !Objects.equals(row.attribute(), normalized.attribute())
                // POI 名称和摘要由服务端高德复核后固化，不与原始浏览器快照比较。
                || (!verifiedPoiRequest && !Objects.equals(row.name(), normalized.name()))
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
        String city = requiredEnum(request.city(), activeCities(), "city");
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
        if (sourcePoiId != null && sourcePoiName == null) {
            throw TemporaryCheckinException.badRequest("高德门店候选缺少门店名称");
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
        MessageDigest digest = sha256Digest();
        MediaSignatureProbe probe = new MediaSignatureProbe(!kind.image);
        long observedSize = 0;
        try (InputStream input = file.getInputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                observedSize += read;
                if (observedSize > limit) {
                    throw TemporaryCheckinException.badRequest("媒体文件超过大小限制");
                }
                digest.update(buffer, 0, read);
                probe.accept(buffer, read);
            }
        } catch (IOException exception) {
            throw TemporaryCheckinException.badRequest("媒体文件读取失败");
        }
        if (observedSize == 0) {
            throw TemporaryCheckinException.badRequest("媒体文件超过大小限制");
        }
        // 手机文件选择器经常返回空、vendor、自相矛盾的 MIME 和临时文件名；这些值可由客户端伪造，
        // 不能作为安全边界。按接口种类检查实际文件特征，并始终使用探测出的规范 MIME/扩展名存储。
        DetectedMedia detected = kind.image ? detectImage(probe.prefix()) : detectAudio(probe);
        String original = canonicalMediaFilename(
                safeFilename(file.getOriginalFilename(), kind.pathValue + detected.extension()),
                detected.extension());
        return new ValidatedMedia(file, observedSize, detected.contentType(), detected.extension(),
                HexFormat.of().formatHex(digest.digest()), original);
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
        if (location.capturedAt().isAfter(now.plus(Duration.ofMinutes(10)))) {
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

    private static DetectedMedia detectAudio(MediaSignatureProbe probe) {
        byte[] bytes = probe.prefix();
        if (bytes.length >= 4 && ascii(bytes, 0, "OggS")
                && probe.oggAudio && !probe.oggVideo) {
            return new DetectedMedia("audio/ogg", ".ogg");
        }
        if (bytes.length >= 7 && unsigned(bytes[0]) == 0xff && (unsigned(bytes[1]) & 0xf6) == 0xf0) {
            return new DetectedMedia("audio/aac", ".aac");
        }
        if (bytes.length >= 12 && ascii(bytes, 4, "ftyp")
                && probe.mp4Audio && !probe.mp4Video) {
            return new DetectedMedia("audio/mp4", ".m4a");
        }
        if (bytes.length >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WAVE")) {
            return new DetectedMedia("audio/wav", ".wav");
        }
        if ((bytes.length >= 6 && ascii(bytes, 0, "#!AMR\n"))
                || (bytes.length >= 9 && ascii(bytes, 0, "#!AMR-WB\n"))) {
            return new DetectedMedia("audio/amr", ".amr");
        }
        if (bytes.length >= 4 && unsigned(bytes[0]) == 0x1a && unsigned(bytes[1]) == 0x45
                && unsigned(bytes[2]) == 0xdf && unsigned(bytes[3]) == 0xa3
                && probe.webmAudio && !probe.webmVideo) {
            return new DetectedMedia("audio/webm", ".webm");
        }
        if ((bytes.length >= 10 && ascii(bytes, 0, "ID3"))
                || (bytes.length >= 2 && unsigned(bytes[0]) == 0xff && (unsigned(bytes[1]) & 0xe0) == 0xe0)) {
            return new DetectedMedia("audio/mpeg", ".mp3");
        }
        throw TemporaryCheckinException.badRequest("录音格式不支持或文件内容损坏");
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

    private static String canonicalMediaFilename(String filename, String extension) {
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        if (dot == 0 || base.isBlank()) base = "media";
        base = base.replaceAll("[. ]+$", "").trim();
        if (base.isBlank()) base = "media";
        int maxBaseLength = Math.max(1, 200 - extension.length());
        if (base.length() > maxBaseLength) base = base.substring(0, maxBaseLength);
        return base + extension;
    }

    private static boolean containsMp4Handler(byte[] bytes, String handler) {
        for (int offset = 0; offset + 16 <= bytes.length; offset++) {
            if (ascii(bytes, offset, "hdlr") && ascii(bytes, offset + 12, handler)) return true;
        }
        return false;
    }

    private static boolean containsAnyAscii(byte[] bytes, String... values) {
        for (String value : values) {
            if (containsAscii(bytes, value)) return true;
        }
        return false;
    }

    private static boolean containsAscii(byte[] bytes, String value) {
        for (int offset = 0; offset + value.length() <= bytes.length; offset++) {
            if (ascii(bytes, offset, value)) return true;
        }
        return false;
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
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
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
        return value != null && value.objectKey() != null && value.sha256() != null
                && value.sizeBytes() != null && value.deletedAt() == null;
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

    /** 注册门店候选只返回门店档案摘要，不暴露距离校验所用的精确锚点。 */
    private static String registeredStoreLocationSummary(StoreRow store, CheckinAnchor anchor) {
        String summary = storeLocationSummary(store);
        if ("FIRST_SUBMITTED_VISIT".equals(anchor.source()) && "位置已采集".equals(summary)) {
            return "已有拜访定位（仅用于到店距离校验）";
        }
        return summary;
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

    /**
     * 运行期城市以后台维护的启用目录为准；旧环境尚未建立目录数据时，
     * 回退到部署配置以保证原有六城表单仍可用。
     */
    private List<String> activeCities() {
        List<String> cities = adminAuthRepository.listActiveCities(tenantId);
        return cities.isEmpty() ? List.copyOf(properties.getCities()) : List.copyOf(cities);
    }

    private static void validateConfiguration(TemporaryCheckinProperties properties) {
        if (properties.getMaxStorefrontPhotoBytes() <= 0 || properties.getMaxWechatScreenshotBytes() <= 0
                || properties.getMaxAudioBytes() <= 0) {
            throw new IllegalStateException("临时打卡媒体大小限制必须大于0");
        }
        if (properties.getMaxCheckinDistanceMeters() < 50
                || properties.getMaxCheckinDistanceMeters() > 10_000) {
            throw new IllegalStateException("临时打卡距离门禁必须在50到10000米之间");
        }
        if (properties.getMaxCheckinAccuracyMeters() < 10
                || properties.getMaxCheckinAccuracyMeters() > 5_000) {
            throw new IllegalStateException("临时打卡定位精度门禁必须在10到5000米之间");
        }
        if (properties.getMaxLocationAgeMinutes() < 1
                || properties.getMaxLocationAgeMinutes() > 1_440) {
            throw new IllegalStateException("临时打卡定位新鲜度必须在1到1440分钟之间");
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

    private record CheckinAnchor(
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters, String source,
            CoordinateSystem coordinateSystem) { }
    private record StoreWithAnchor(StoreRow store, CheckinAnchor anchor) { }
    private record StoreDistance(StoreRow store, CheckinAnchor anchor, Double distanceMeters) { }
    private record PoiDistance(AmapPoiClient.NearbyPoi poi, double distanceMeters) { }
    private record CityMatch(Boolean matched, String resolvedCity, String message) { }

    private record OptionalStore(boolean present, StoreRow row) { }

    private enum CoordinateSystem { WGS84, GCJ02 }

    private record DetectedMedia(String contentType, String extension) { }

    /**
     * 以固定小窗口扫描常见媒体特征，避免把最大100MB的录音整体放入JVM堆。
     * 该探测用于兼容手机选择器元数据，不替代完整编解码校验。
     */
    private static final class MediaSignatureProbe {
        private static final int PREFIX_BYTES = 32;
        private static final int OVERLAP_BYTES = 15;

        private final byte[] prefix = new byte[PREFIX_BYTES];
        private final byte[] tail = new byte[OVERLAP_BYTES];
        private final boolean scanAudioContainer;
        private int prefixLength;
        private int tailLength;
        private boolean oggAudio;
        private boolean oggVideo;
        private boolean mp4Audio;
        private boolean mp4Video;
        private boolean webmAudio;
        private boolean webmVideo;

        private MediaSignatureProbe(boolean scanAudioContainer) {
            this.scanAudioContainer = scanAudioContainer;
        }

        void accept(byte[] bytes, int length) {
            int prefixCopy = Math.min(length, PREFIX_BYTES - prefixLength);
            if (prefixCopy > 0) {
                System.arraycopy(bytes, 0, prefix, prefixLength, prefixCopy);
                prefixLength += prefixCopy;
            }
            if (!scanAudioContainer) return;

            byte[] scan = new byte[tailLength + length];
            System.arraycopy(tail, 0, scan, 0, tailLength);
            System.arraycopy(bytes, 0, scan, tailLength, length);
            if (prefixLength >= 4 && ascii(prefix, 0, "OggS")) {
                oggAudio |= containsAnyAscii(scan, "OpusHead", "vorbis", "Speex", "fLaC");
                oggVideo |= containsAscii(scan, "theora");
            } else if (prefixLength >= 12 && ascii(prefix, 4, "ftyp")) {
                mp4Audio |= containsMp4Handler(scan, "soun");
                mp4Video |= containsMp4Handler(scan, "vide");
            } else if (prefixLength >= 4 && unsigned(prefix[0]) == 0x1a && unsigned(prefix[1]) == 0x45
                    && unsigned(prefix[2]) == 0xdf && unsigned(prefix[3]) == 0xa3) {
                webmAudio |= containsAnyAscii(scan, "A_OPUS", "A_VORBIS", "A_AAC", "A_FLAC", "A_MPEG/L3");
                webmVideo |= containsAnyAscii(scan, "V_VP8", "V_VP9", "V_AV1");
            }

            tailLength = Math.min(OVERLAP_BYTES, scan.length);
            System.arraycopy(scan, scan.length - tailLength, tail, 0, tailLength);
        }

        byte[] prefix() {
            return Arrays.copyOf(prefix, prefixLength);
        }
    }

    private record ValidatedMedia(
            MultipartFile file, long sizeBytes, String contentType, String extension,
            String sha256, String originalFilename) { }

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
