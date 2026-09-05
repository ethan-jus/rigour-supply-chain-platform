package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rigour.sales.application.port.out.AmapPoiClient;
import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CreateStoreRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CreateSubmissionRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.LocationCommand;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.ResolveLocationRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.SearchNewStoreRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinReverseGeocoder.GeocodeResult;
import com.rigour.sales.temporarycheckin.TemporaryCheckinStoreSelectionTokenService.Candidate;
import com.rigour.shared.file.FileMetadata;
import com.rigour.shared.file.FileStorage;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

/** 使用真实 MySQL 验证公开接口、固定租户、幂等与媒体提交边界。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TemporaryCheckinApiIntegrationTests {

    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID CREATOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID VISITOR_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_TENANT_SALESPERSON_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID STORE_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID STORE_CLIENT_ID = UUID.fromString("31000000-0000-0000-0000-000000000001");
    private static final UUID SHENZHEN_SALESPERSON_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000004");
    private static final UUID HEADQUARTERS_SALESPERSON_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000005");
    private static final UUID SHENZHEN_STORE_ID = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final String SUBMISSION_KEY = "temporary-checkin-browser-key-" + "a".repeat(32);
    private static final String OTHER_SUBMISSION_KEY = "temporary-checkin-browser-key-" + "b".repeat(32);

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work")
            .withUsername("rigour_sales_work_api_test")
            .withPassword("rigour_sales_work_api_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
        registry.add("rigour.sales.temporary-checkin.enabled", () -> true);
        registry.add("rigour.sales.temporary-checkin.identity-enforcement-enabled", () -> false);
        registry.add("rigour.sales.temporary-checkin.tenant-id", TENANT_ID::toString);
        registry.add("rigour.sales.temporary-checkin.identity-signing-key-base64",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("rigour.sales.temporary-checkin.risk-hmac-key-base64",
                () -> "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=");
        registry.add("rigour.sales.temporary-checkin.trusted-proxy-marker",
                () -> "integration-test-trusted-proxy-marker-0123456789abcdef0123456789abcdef");
        registry.add("rigour.sales.temporary-checkin.max-checkin-distance-meters", () -> 300);
        registry.add("rigour.sales.temporary-checkin.max-checkin-accuracy-meters", () -> 300);
        registry.add("rigour.sales.temporary-checkin.max-location-age-minutes", () -> 60);
    }

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TemporaryCheckinStoreSelectionTokenService storeSelectionTokenService;

    @Autowired
    private TemporaryCheckinLocationVerificationTokenService locationVerificationTokenService;

    @MockitoBean
    private FileStorage fileStorage;

    @MockitoBean
    private TemporaryCheckinReverseGeocoder reverseGeocoder;

    @MockitoBean
    private AmapPoiClient amapPoiClient;

    @MockitoBean
    private Wgs84Gcj02Converter coordinateConverter;

    @MockitoBean
    private TemporaryCheckinAiClient aiClient;

    @BeforeEach
    void seedConfiguredAndForeignTenants() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbc.update("DELETE FROM temp_sales_checkin_submission");
        jdbc.update("DELETE FROM temp_sales_checkin_store");
        jdbc.update("DELETE FROM temp_sales_checkin_salesperson");
        insertSalesperson(TENANT_ID, CREATOR_ID, "门店创建人", "北京");
        insertSalesperson(TENANT_ID, VISITOR_ID, "当次拜访人", "北京");
        insertSalesperson(TENANT_ID, SHENZHEN_SALESPERSON_ID, "深圳拜访人", "深圳");
        insertSalesperson(TENANT_ID, HEADQUARTERS_SALESPERSON_ID, "总部拜访人", "总部");
        insertSalesperson(OTHER_TENANT_ID, OTHER_TENANT_SALESPERSON_ID, "外部租户销售", "北京");
        insertStore();
        insertShenzhenStore();
        reset(fileStorage, reverseGeocoder, amapPoiClient, coordinateConverter, aiClient);
        when(fileStorage.put(any(FileMetadata.class), any(InputStream.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(reverseGeocoder.resolve(any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(new GeocodeResult(
                        "RESOLVED", "东城区龙潭路与夕照寺街交叉口东南60米",
                        "北京市东城区龙潭路与夕照寺街交叉口东南60米", "110101", "北京市", "北京市",
                        "东城区", "龙潭街道", new BigDecimal("116.403000"),
                        new BigDecimal("39.912000"), null));
        when(coordinateConverter.convert(any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(new Wgs84Gcj02Converter.Coordinates(
                        new BigDecimal("116.403000"), new BigDecimal("39.912000")));
        when(amapPoiClient.searchAround(any(String.class), any(BigDecimal.class), any(BigDecimal.class),
                anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> "高德候选门店".equals(invocation.getArgument(0))
                        ? new AmapPoiClient.NearbyPoiPage(List.of(
                                new AmapPoiClient.NearbyPoi(
                                        "B0FFTESTPOI", "高德候选门店", "北京市东城区服务端地址",
                                        "休闲服务", "080000", new BigDecimal("116.403000"),
                                        new BigDecimal("39.912000"), BigDecimal.ZERO,
                                        "北京市", "110101")), 1, 25, 1)
                        : new AmapPoiClient.NearbyPoiPage(List.of(), 1, 25, 0));
    }

    @Test
    void resolvesRegisteredStoresAfterOneLocationVerificationWithoutAnyAmapSearch() throws Exception {
        ResolveLocationRequest request = resolveRequest("北京", VISITOR_ID, location());

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geocodeStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.address").value("东城区龙潭路与夕照寺街交叉口东南60米"))
                .andExpect(jsonPath("$.cityMatched").value(true))
                .andExpect(jsonPath("$.resolvedCity").value("北京"))
                .andExpect(jsonPath("$.locationVerificationToken").isNotEmpty())
                .andExpect(jsonPath("$.maxCheckinDistanceMeters").value(300))
                .andExpect(jsonPath("$.maxCheckinAccuracyMeters").value(300))
                .andExpect(jsonPath("$.maxLocationAgeMinutes").value(60))
                .andExpect(jsonPath("$.accuracyAccepted").value(true))
                .andExpect(jsonPath("$.freshnessAccepted").value(true))
                .andExpect(jsonPath("$.poiLookupStatus").value("SKIPPED"))
                .andExpect(jsonPath("$.nearbyStores[0].source").value("REGISTERED"))
                .andExpect(jsonPath("$.nearbyStores[0].storeId").value(STORE_ID.toString()))
                .andExpect(jsonPath("$.nearbyStores[0].name").value("已导入门店"))
                .andExpect(jsonPath("$.nearbyStores[0].distanceMeters").value(0))
                .andExpect(jsonPath("$.nearbyStores[0].address").value("北京市朝阳区"))
                .andExpect(jsonPath("$.nearbyStores[0].longitude").doesNotExist())
                .andExpect(jsonPath("$.nearbyStores[0].latitude").doesNotExist())
                .andExpect(jsonPath("$.nearbyStores[0].locationSource").value("STORE_LOCATION"))
                .andExpect(jsonPath("$.nearbyStores[0].checkinEligible").value(true))
                .andExpect(jsonPath("$.nearbyStores[0].nextAction").value("CHECK_IN"));

        verify(reverseGeocoder, times(1)).resolve(
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270"));
        verifyNoInteractions(amapPoiClient, coordinateConverter);
    }

    @Test
    void acceptsCommonIndoorNetworkAccuracyAtTwoHundredFiftyMeters() throws Exception {
        LocationCommand indoorNetworkLocation = new LocationCommand(
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270"),
                new BigDecimal("250.00"), Instant.now().minusSeconds(10), "室内网络定位");

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, indoorNetworkLocation))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geocodeStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.accuracyAccepted").value(true))
                .andExpect(jsonPath("$.maxCheckinAccuracyMeters").value(300))
                .andExpect(jsonPath("$.locationVerificationToken").isNotEmpty());
    }

    @Test
    void keepsActualCrossCityAddressAndReturnsLocationProofAndNearbyStores() throws Exception {
        when(reverseGeocoder.resolve(any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(new GeocodeResult(
                        "RESOLVED", "深圳市南山区测试路1号", "深圳市南山区测试路1号",
                        "440305", "广东省", "深圳市", "南山区", "粤海街道",
                        new BigDecimal("113.930000"), new BigDecimal("22.530000"), null));

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geocodeStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.cityMatched").value(false))
                .andExpect(jsonPath("$.resolvedCity").value("深圳"))
                .andExpect(jsonPath("$.locationMessage").value(
                        "实际定位在深圳，业务归属按北京记录；门店仅按当前位置300米范围选择"))
                .andExpect(jsonPath("$.poiLookupStatus").value("SKIPPED"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(1)))
                .andExpect(jsonPath("$.nearbyStores[0].storeId").value(STORE_ID.toString()))
                .andExpect(jsonPath("$.locationVerificationToken").isNotEmpty());

        verify(reverseGeocoder, times(1)).resolve(any(BigDecimal.class), any(BigDecimal.class));
        verifyNoInteractions(amapPoiClient, coordinateConverter);
    }

    @Test
    void allowsNearbyRegisteredStoreAcrossBusinessCitiesWhileKeepingSubmissionAttribution() throws Exception {
        UUID nearbyShenzhenStoreId = UUID.randomUUID();
        insertImportedStore(nearbyShenzhenStoreId, "深圳", SHENZHEN_SALESPERSON_ID,
                "跨归属附近门店", location());
        when(reverseGeocoder.resolve(any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(new GeocodeResult(
                        "RESOLVED", "深圳市南山区测试路1号", "深圳市南山区测试路1号",
                        "440305", "广东省", "深圳市", "南山区", "粤海街道",
                        new BigDecimal("113.930000"), new BigDecimal("22.530000"), null));

        LocationCommand captured = location();
        MvcResult resolved = mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, captured))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cityMatched").value(false))
                .andExpect(jsonPath("$.nearbyStores[*].storeId", containsInAnyOrder(
                        STORE_ID.toString(), nearbyShenzhenStoreId.toString())))
                .andReturn();
        String locationProof = objectMapper.readTree(resolved.getResponse().getContentAsByteArray())
                .path("locationVerificationToken").asText();

        CreateSubmissionRequest visit = new CreateSubmissionRequest(
                UUID.randomUUID(), SUBMISSION_KEY, "北京", VISITOR_ID, nearbyShenzhenStoreId,
                "跨归属客户", null, "现场距离满足要求", captured, true,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION, locationProof);
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(visit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        var stored = jdbc.queryForMap("""
                SELECT city, store_id, location_city FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(submissionId));
        assertThat(stored.get("city")).isEqualTo("北京");
        assertThat(stored.get("store_id")).isEqualTo(bin(nearbyShenzhenStoreId));
        assertThat(stored.get("location_city")).isEqualTo("深圳市");
    }

    @Test
    void distinguishesNearbyUnregisteredAmapPoiAsProfileCompletionCandidate() throws Exception {
        when(amapPoiClient.searchAround("台球", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25))
                .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(
                        new AmapPoiClient.NearbyPoi("B0FFNEWPOI", "附近新门店", "北京市东城区测试路2号",
                                "休闲服务", "080000", new BigDecimal("116.403100"),
                                new BigDecimal("39.912100"), new BigDecimal("14"),
                                "北京市", "110101")), 1, 25, 1));

        mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                searchNewStoreRequest("北京", VISITOR_ID, location(), "台球"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poiLookupStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(1)))
                .andExpect(jsonPath("$.nearbyStores[0].source").value("AMAP_POI"))
                .andExpect(jsonPath("$.nearbyStores[0].storeId").doesNotExist())
                .andExpect(jsonPath("$.nearbyStores[0].poiId").value("B0FFNEWPOI"))
                .andExpect(jsonPath("$.nearbyStores[0].longitude").value(116.403100))
                .andExpect(jsonPath("$.nearbyStores[0].latitude").value(39.912100))
                .andExpect(jsonPath("$.nearbyStores[0].selectionToken").isNotEmpty())
                .andExpect(jsonPath("$.nearbyStores[0].locationSource").value("AMAP_POI"))
                .andExpect(jsonPath("$.nearbyStores[0].checkinEligible").value(false))
                .andExpect(jsonPath("$.nearbyStores[0].nextAction").value("COMPLETE_STORE_PROFILE"));
        verify(amapPoiClient, times(1)).searchAround("台球", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25);
        verifyNoInteractions(reverseGeocoder);
    }

    @Test
    void createsNearbyPoiStoreFromActualCrossCityLocationUnderAuthorizedBusinessCity() throws Exception {
        when(reverseGeocoder.resolve(any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(new GeocodeResult(
                        "RESOLVED", "深圳市南山区现场路1号", "深圳市南山区现场路1号",
                        "440305", "广东省", "深圳市", "南山区", "粤海街道",
                        new BigDecimal("113.930000"), new BigDecimal("22.530000"), null));
        when(amapPoiClient.searchAround("跨城新店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25))
                .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(
                        new AmapPoiClient.NearbyPoi(
                                "B0FFCROSSCITY", "深圳跨城新店", "深圳市南山区现场路1号",
                                "休闲服务", "080000", new BigDecimal("116.403000"),
                                new BigDecimal("39.912000"), BigDecimal.ZERO,
                                "深圳市", "440305")), 1, 25, 1));

        UUID clientStoreId = UUID.randomUUID();
        LocationCommand captured = location();
        MvcResult resolved = mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, captured))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cityMatched").value(false))
                .andExpect(jsonPath("$.locationVerificationToken").isNotEmpty())
                .andReturn();
        String locationProof = objectMapper.readTree(resolved.getResponse().getContentAsByteArray())
                .path("locationVerificationToken").asText();

        MvcResult searched = mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new SearchNewStoreRequest(
                                clientStoreId, "北京", VISITOR_ID, captured,
                                "跨城新店", locationProof))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores", hasSize(1)))
                .andExpect(jsonPath("$.nearbyStores[0].distanceMeters").value(0))
                .andExpect(jsonPath("$.nearbyStores[0].selectionToken").isNotEmpty())
                .andReturn();
        String selectionToken = objectMapper.readTree(searched.getResponse().getContentAsByteArray())
                .path("nearbyStores").get(0).path("selectionToken").asText();

        CreateStoreRequest store = new CreateStoreRequest(
                clientStoreId, "北京", VISITOR_ID,
                "B0FFCROSSCITY", "深圳跨城新店", "深圳市南山区现场路1号",
                new BigDecimal("116.403000"), new BigDecimal("39.912000"),
                "台球", "深圳跨城新店", "营业中", "深店长", "13800000000",
                "100-300平米", "10张球桌", List.of("竞技赛事"), List.of("高德业务"),
                "高意向", "A类", List.of("单店"), captured,
                selectionToken, locationProof, null);
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("北京"))
                .andReturn();
        UUID storeId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        var stored = jdbc.queryForMap("""
                SELECT city, location_formatted_address, location_adcode, source_poi_id
                  FROM temp_sales_checkin_store WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(storeId));
        assertThat(stored.get("city")).isEqualTo("北京");
        assertThat(stored.get("location_formatted_address")).isEqualTo("深圳市南山区现场路1号");
        assertThat(stored.get("location_adcode")).isEqualTo("440305");
        assertThat(stored.get("source_poi_id")).isEqualTo("B0FFCROSSCITY");
    }

    @Test
    void searchesByCurrentCoordinatesWithoutFilteringCandidatesByBusinessCity() throws Exception {
        when(amapPoiClient.searchAround("南京球馆", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25))
                .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(
                        new AmapPoiClient.NearbyPoi(
                                "B0FFNANJING", "南京球馆", "南京市玄武区", "休闲服务", "080000",
                                new BigDecimal("116.403000"), new BigDecimal("39.912000"),
                                BigDecimal.ZERO, "南京市", "320102"),
                        new AmapPoiClient.NearbyPoi(
                                "B0FFBEIJING", "跨城球馆", "北京市东城区", "休闲服务", "080000",
                                new BigDecimal("116.403000"), new BigDecimal("39.912000"),
                                BigDecimal.ZERO, "北京市", "110101")),
                        1, 25, 2));

        mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(searchNewStoreRequest(
                                "南京", HEADQUARTERS_SALESPERSON_ID, location(), "南京球馆"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores", hasSize(2)))
                .andExpect(jsonPath("$.nearbyStores[*].poiId",
                        containsInAnyOrder("B0FFNANJING", "B0FFBEIJING")))
                .andExpect(jsonPath("$.nearbyStores[0].selectionToken").isNotEmpty())
                .andExpect(jsonPath("$.nearbyStores[1].selectionToken").isNotEmpty());
        verify(amapPoiClient, times(1)).searchAround("南京球馆", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25);
        verifyNoInteractions(reverseGeocoder);
    }

    @Test
    void keepsDynamicBusinessCityAuthorizationSeparateFromNearbyPoiCities() throws Exception {
        UUID cityId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_city
                    (id, tenant_id, name, status, sort_order, created_at, updated_at)
                VALUES (?, ?, '无锡', 'ACTIVE', 999, ?, ?)
                """, bin(cityId), bin(TENANT_ID), Timestamp.from(now), Timestamp.from(now));
        try {
            when(amapPoiClient.searchAround("无锡球馆", new BigDecimal("116.403000"),
                    new BigDecimal("39.912000"), 300, 1, 25))
                    .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(
                            new AmapPoiClient.NearbyPoi(
                                    "B0FFWUXI", "无锡球馆", "无锡市滨湖区", "休闲服务", "080000",
                                    new BigDecimal("116.403000"), new BigDecimal("39.912000"),
                                    BigDecimal.ZERO, "无锡市", "320205"),
                            new AmapPoiClient.NearbyPoi(
                                    "B0FFADCODEONLY", "仅行政区证据", "无锡市滨湖区", "休闲服务", "080000",
                                    new BigDecimal("116.403000"), new BigDecimal("39.912000"),
                                    BigDecimal.ZERO, null, "320205"),
                            new AmapPoiClient.NearbyPoi(
                                    "B0FFSUZHOU", "跨城球馆", "苏州市姑苏区", "休闲服务", "080000",
                                    new BigDecimal("116.403000"), new BigDecimal("39.912000"),
                                    BigDecimal.ZERO, "苏州市", "320508")),
                            1, 25, 3));

            mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(searchNewStoreRequest(
                                    "无锡", HEADQUARTERS_SALESPERSON_ID, location(), "无锡球馆"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.nearbyStores", hasSize(3)))
                    .andExpect(jsonPath("$.nearbyStores[*].poiId", containsInAnyOrder(
                            "B0FFWUXI", "B0FFADCODEONLY", "B0FFSUZHOU")))
                    .andExpect(jsonPath("$.nearbyStores[0].selectionToken").isNotEmpty())
                    .andExpect(jsonPath("$.nearbyStores[1].selectionToken").isNotEmpty())
                    .andExpect(jsonPath("$.nearbyStores[2].selectionToken").isNotEmpty());
            verify(amapPoiClient, times(1)).searchAround("无锡球馆", new BigDecimal("116.403000"),
                    new BigDecimal("39.912000"), 300, 1, 25);
            verifyNoInteractions(reverseGeocoder);
        } finally {
            jdbc.update("DELETE FROM temp_sales_checkin_city WHERE tenant_id=? AND id=?",
                    bin(TENANT_ID), bin(cityId));
        }
    }

    @Test
    void keepsRegisteredAndExplicitAmapSearchesAsSeparateCallPaths() throws Exception {
        UUID registeredId = UUID.randomUUID();
        insertImportedStore(registeredId, "统一同名门店", location());
        when(amapPoiClient.searchAround("统一同名门店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25))
                .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(
                        new AmapPoiClient.NearbyPoi(
                                "B0FFSAMENAME", "统一同名门店", "另一座", "休闲服务", "080000",
                                new BigDecimal("116.403100"), new BigDecimal("39.912100"),
                                new BigDecimal("14"), "北京市", "110101")), 1, 25, 1));

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, location(), "统一同名门店"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores", hasSize(1)))
                .andExpect(jsonPath("$.nearbyStores[0].source").value("REGISTERED"))
                .andExpect(jsonPath("$.nearbyStores[0].storeId").value(registeredId.toString()));
        mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(searchNewStoreRequest(
                                "北京", VISITOR_ID, location(), "统一同名门店"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores", hasSize(1)))
                .andExpect(jsonPath("$.nearbyStores[0].source").value("AMAP_POI"))
                .andExpect(jsonPath("$.nearbyStores[0].poiId").value("B0FFSAMENAME"));
    }

    @Test
    void keepsBothRegisteredAndAmapCandidatesInsideTheServerEnforcedRadius() throws Exception {
        UUID clientStoreId = UUID.randomUUID();
        UUID farRegisteredId = UUID.randomUUID();
        LocationCommand farLocation = new LocationCommand(new BigDecimal("116.4071280"),
                new BigDecimal("39.9165270"), new BigDecimal("8.50"),
                Instant.now().minusSeconds(30), "远距门店");
        insertImportedStore(farRegisteredId, "远距门店", farLocation);
        AmapPoiClient.NearbyPoi farPoi = new AmapPoiClient.NearbyPoi(
                "B0FFFAR300", "远距门店", "远距地址", "休闲服务", "080000",
                new BigDecimal("116.413000"), new BigDecimal("39.912000"), new BigDecimal("850"),
                "北京市", "110101");

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxCheckinDistanceMeters").value(300))
                .andExpect(jsonPath("$.nearbyStores", hasSize(1)))
                .andExpect(jsonPath("$.nearbyStores[0].storeId").value(STORE_ID.toString()));

        when(amapPoiClient.searchAround("远距门店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25))
                .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(farPoi), 1, 25, 1));
        mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(searchNewStoreRequest(
                                clientStoreId, "北京", VISITOR_ID, location(), "远距门店"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poiLookupStatus").value("EMPTY"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(0)))
                .andExpect(jsonPath("$.manualEntryToken").isNotEmpty());

        LocationCommand captured = location();
        CreateStoreRequest forgedWithoutToken = new CreateStoreRequest(
                clientStoreId, "北京", VISITOR_ID,
                "B0FFFAR300", "远距门店", "远距地址",
                new BigDecimal("116.413000"), new BigDecimal("39.912000"),
                "台球", "远距门店", "营业中", "远距店长", "13800000000",
                "100-300平米", "10张球桌", List.of("竞技赛事"), List.of("高德业务"),
                "高意向", "A类", List.of("单店"), captured, null,
                locationVerificationToken(VISITOR_ID, "北京", captured), null);
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(forgedWithoutToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("高德门店候选已过期，请重新搜索选择"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND client_store_id=?
                """, Integer.class, bin(TENANT_ID), bin(clientStoreId))).isZero();
        verify(amapPoiClient, times(1)).searchAround("远距门店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25);
    }

    @Test
    void searchesUnavailableOnceThenCreatesSearchableManualStoreWithoutRetryingAmap() throws Exception {
        UUID clientStoreId = UUID.randomUUID();
        LocationCommand captured = location();
        String locationProof = locationVerificationToken(VISITOR_ID, "北京", captured);
        when(amapPoiClient.searchAround("高德故障手工新店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25))
                .thenThrow(new com.rigour.sales.application.port.out.AmapPoiException("响应解析失败"));

        MvcResult searched = mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new SearchNewStoreRequest(
                                clientStoreId, "北京", VISITOR_ID, captured,
                                "高德故障手工新店", locationProof))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poiLookupStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(0)))
                .andExpect(jsonPath("$.manualEntryToken").isNotEmpty())
                .andReturn();
        String manualEntryToken = objectMapper.readTree(searched.getResponse().getContentAsByteArray())
                .path("manualEntryToken").asText();
        verify(amapPoiClient, times(1)).searchAround("高德故障手工新店",
                new BigDecimal("116.403000"), new BigDecimal("39.912000"), 300, 1, 25);
        verify(coordinateConverter, times(1)).convert(captured.longitude(), captured.latitude());
        verifyNoInteractions(reverseGeocoder);
        clearInvocations(reverseGeocoder, amapPoiClient, coordinateConverter);

        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                clientStoreId, "高德故障手工新店", captured,
                                locationProof, manualEntryToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("高德故障手工新店"))
                .andReturn();
        UUID createdId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        var stored = jdbc.queryForMap("""
                SELECT client_store_id, source_poi_id, longitude, latitude
                  FROM temp_sales_checkin_store WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(createdId));
        assertThat((byte[]) stored.get("client_store_id")).isEqualTo(bin(clientStoreId));
        assertThat(stored.get("source_poi_id")).isNull();
        assertThat((BigDecimal) stored.get("longitude")).isEqualByComparingTo("116.3971280");
        assertThat((BigDecimal) stored.get("latitude")).isEqualByComparingTo("39.9165270");

        mockMvc.perform(get("/sales-checkin/api/v1/stores")
                        .param("city", "北京")
                        .param("q", "高德故障手工新店"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(createdId.toString()));

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, location(), "高德故障手工新店"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poiLookupStatus").value("SKIPPED"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(1)))
                .andExpect(jsonPath("$.nearbyStores[0].source").value("REGISTERED"))
                .andExpect(jsonPath("$.nearbyStores[0].storeId").value(createdId.toString()));
        verify(reverseGeocoder, times(1)).resolve(any(BigDecimal.class), any(BigDecimal.class));
        verifyNoInteractions(amapPoiClient, coordinateConverter);
    }

    @Test
    void emptySearchTokenIsRequiredAndBoundToClientStoreWhileSaveNeverRetriesAmap() throws Exception {
        UUID clientStoreId = UUID.randomUUID();
        LocationCommand captured = location();

        MvcResult resolved = mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, captured))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationVerificationToken").isNotEmpty())
                .andReturn();
        String locationProof = objectMapper.readTree(resolved.getResponse().getContentAsByteArray())
                .path("locationVerificationToken").asText();
        verify(reverseGeocoder, times(1)).resolve(captured.longitude(), captured.latitude());
        verifyNoInteractions(amapPoiClient, coordinateConverter);
        clearInvocations(reverseGeocoder, amapPoiClient, coordinateConverter);

        MvcResult searched = mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new SearchNewStoreRequest(
                                clientStoreId, "北京", VISITOR_ID, captured,
                                "不存在新店", locationProof))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poiLookupStatus").value("EMPTY"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(0)))
                .andExpect(jsonPath("$.manualEntryToken").isNotEmpty())
                .andReturn();
        String manualEntryToken = objectMapper.readTree(searched.getResponse().getContentAsByteArray())
                .path("manualEntryToken").asText();
        verify(amapPoiClient, times(1)).searchAround("不存在新店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25);
        verify(coordinateConverter, times(1)).convert(captured.longitude(), captured.latitude());
        verifyNoInteractions(reverseGeocoder);
        clearInvocations(reverseGeocoder, amapPoiClient, coordinateConverter);

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                clientStoreId, "空结果手工新店", captured,
                                locationProof, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请先完成一次新门店搜索；无结果后才能手工录入"));

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                UUID.randomUUID(), "空结果手工新店", captured,
                                locationProof, manualEntryToken))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith("人工建店凭证已过期")));

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                clientStoreId, "空结果手工新店", captured,
                                locationProof, manualEntryToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("空结果手工新店"));

        verifyNoInteractions(reverseGeocoder, amapPoiClient, coordinateConverter);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND client_store_id=?
                """, Integer.class, bin(TENANT_ID), bin(clientStoreId))).isEqualTo(1);
    }

    @Test
    void usesFirstAcceptableSubmittedVisitAsFixedPrivateAnchorOnlyWhenStoreHasNoUsableCoordinates()
            throws Exception {
        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET longitude=NULL, latitude=NULL, accuracy_meters=NULL, location_captured_at=NULL,
                       location_note=NULL, location_address=NULL, source_poi_address=NULL
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));
        UUID inaccurateFirstVisit = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "最早客户", "最早低精度拜访");
        jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET accuracy_meters=350.01
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(inaccurateFirstVisit));
        Instant inaccurateSubmittedAt = Instant.now().minusSeconds(240);
        markSubmitted(inaccurateFirstVisit, inaccurateSubmittedAt.minusSeconds(10), inaccurateSubmittedAt);
        UUID firstAcceptableVisit = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "历史客户", "首次合格拜访");
        Instant firstAcceptableAt = Instant.now().minusSeconds(180);
        markSubmitted(firstAcceptableVisit, firstAcceptableAt.minusSeconds(10), firstAcceptableAt);
        UUID laterFarVisit = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "后续客户", "后续漂移拜访");
        jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET longitude=116.4071280, latitude=39.9165270
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(laterFarVisit));
        Instant laterSubmittedAt = Instant.now().minusSeconds(60);
        markSubmitted(laterFarVisit, laterSubmittedAt.minusSeconds(10), laterSubmittedAt);

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores[0].storeId").value(STORE_ID.toString()))
                .andExpect(jsonPath("$.nearbyStores[0].locationSource")
                        .value("FIRST_SUBMITTED_VISIT"))
                .andExpect(jsonPath("$.nearbyStores[0].address")
                        .value("已有拜访定位（仅用于到店距离校验）"))
                .andExpect(jsonPath("$.nearbyStores[0].longitude").doesNotExist())
                .andExpect(jsonPath("$.nearbyStores[0].latitude").doesNotExist())
                .andExpect(jsonPath("$.nearbyStores[0].checkinEligible").value(true));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "复访已到店", true, location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void rejectsFarIncompleteUnlocatedAndLowAccuracyCheckinsAtTheServerBoundary() throws Exception {
        LocationCommand farLocation = new LocationCommand(new BigDecimal("116.4071280"),
                new BigDecimal("39.9165270"), new BigDecimal("8.50"),
                Instant.now().minusSeconds(30), "远程位置");
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "远程尝试", true, farLocation))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith("当前定位距离门店约")));

        jdbc.update("""
                UPDATE temp_sales_checkin_store SET business_types_json=JSON_ARRAY()
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "资料不完整", true, location()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("门店基础资料不完整，请先补全门店信息"));
        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET business_types_json=JSON_ARRAY('竞技赛事'), longitude=NULL, latitude=NULL,
                       accuracy_meters=NULL, location_captured_at=NULL
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "无定位门店", true, location()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("门店缺少有效定位，请先补录门店定位"));

        LocationCommand inaccurate = new LocationCommand(new BigDecimal("116.3971280"),
                new BigDecimal("39.9165270"), new BigDecimal("350.01"),
                Instant.now().minusSeconds(30), "定位漂移");
        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, inaccurate))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.accuracyAccepted").value(false))
                .andExpect(jsonPath("$.locationMessage")
                        .value("当前定位精度约351米，超过允许的300米，请到室外或开阔处重新定位"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(0)));
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "低精度打卡", true, inaccurate))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("当前定位精度约351米，超过允许的300米，请到室外或开阔处重新定位"));
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(poiStoreAtLocation(
                                UUID.randomUUID(), "低精度新店", "赵店长", inaccurate))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("当前定位精度约351米，超过允许的300米，请到室外或开阔处重新定位"));
    }

    @Test
    void recordsUnverifiedStoreAndVisitWithoutCoordinatesAndStillRequiresStorefrontPhoto()
            throws Exception {
        UUID storeAttemptId = UUID.randomUUID();
        UUID clientStoreId = UUID.randomUUID();
        CreateStoreRequest storeRequest = unverifiedStore(
                clientStoreId, "定位失败仍可录入门店", null, "POSITION_UNAVAILABLE", storeAttemptId);
        MvcResult createdStore = mockMvc.perform(post("/sales-checkin/api/v1/stores/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(storeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("定位失败仍可录入门店"))
                .andReturn();
        UUID storeId = UUID.fromString(objectMapper.readTree(
                createdStore.getResponse().getContentAsByteArray()).path("id").asText());

        var storedStore = jdbc.queryForMap("""
                SELECT location_verification_status, location_failure_reason, location_attempt_id,
                       longitude, latitude, accuracy_meters, location_captured_at,
                       geocode_status, geocode_error_code
                  FROM temp_sales_checkin_store WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(storeId));
        assertThat(storedStore.get("location_verification_status")).isEqualTo("UNVERIFIED");
        assertThat(storedStore.get("location_failure_reason")).isEqualTo("POSITION_UNAVAILABLE");
        assertThat((byte[]) storedStore.get("location_attempt_id")).containsExactly(bin(storeAttemptId));
        assertThat(storedStore.get("longitude")).isNull();
        assertThat(storedStore.get("latitude")).isNull();
        assertThat(storedStore.get("accuracy_meters")).isNull();
        assertThat(storedStore.get("location_captured_at")).isNull();
        assertThat(storedStore.get("geocode_status")).isEqualTo("SKIPPED");
        assertThat(storedStore.get("geocode_error_code"))
                .isEqualTo("LOCATION_POSITION_UNAVAILABLE");

        mockMvc.perform(get("/sales-checkin/api/v1/stores")
                        .param("city", "北京")
                        .param("q", "定位失败仍可录入门店"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(storeId.toString()))
                .andExpect(jsonPath("$[0].locationSummary").value("定位未核验"))
                .andExpect(jsonPath("$[0].locationVerificationStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$[0].locationFailureReason").value("POSITION_UNAVAILABLE"));

        UUID visitAttemptId = UUID.randomUUID();
        UUID clientSubmissionId = UUID.randomUUID();
        CreateSubmissionRequest visitRequest = unverifiedSubmission(
                clientSubmissionId, storeId, "定位失败仍完成真实拜访记录", null,
                "TIMEOUT", visitAttemptId);
        MvcResult createdVisit = mockMvc.perform(post("/sales-checkin/api/v1/submissions/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(visitRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                createdVisit.getResponse().getContentAsByteArray()).path("id").asText());

        var storedVisit = jdbc.queryForMap("""
                SELECT location_verification_status, location_failure_reason, location_attempt_id,
                       longitude, latitude, accuracy_meters, location_captured_at,
                       geocode_status, geocode_error_code, risk_level, risk_flags_json
                  FROM temp_sales_checkin_submission WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(submissionId));
        assertThat(storedVisit.get("location_verification_status")).isEqualTo("UNVERIFIED");
        assertThat(storedVisit.get("location_failure_reason")).isEqualTo("TIMEOUT");
        assertThat((byte[]) storedVisit.get("location_attempt_id")).containsExactly(bin(visitAttemptId));
        assertThat(storedVisit.get("longitude")).isNull();
        assertThat(storedVisit.get("latitude")).isNull();
        assertThat(storedVisit.get("accuracy_meters")).isNull();
        assertThat(storedVisit.get("location_captured_at")).isNull();
        assertThat(storedVisit.get("geocode_status")).isEqualTo("SKIPPED");
        assertThat(storedVisit.get("geocode_error_code")).isEqualTo("LOCATION_TIMEOUT");
        assertThat(storedVisit.get("risk_level")).isEqualTo("MEDIUM");
        assertThat(storedVisit.get("risk_flags_json").toString()).contains("LOCATION_UNVERIFIED");

        mockMvc.perform(post("/sales-checkin/api/v1/submissions/{id}/complete", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请先上传门头照"));

        upload(submissionId, "storefront-photo", new MockMultipartFile(
                "file", "door.jpg", "image/jpeg",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}))
                .andExpect(status().isOk());
        mockMvc.perform(post("/sales-checkin/api/v1/submissions/{id}/complete", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=? AND status='SUBMITTED'
                   AND location_verification_status='UNVERIFIED'
                   AND location_failure_reason='TIMEOUT' AND location_attempt_id=?
                   AND risk_level IN ('MEDIUM', 'HIGH')
                   AND JSON_CONTAINS(risk_flags_json, JSON_QUOTE('LOCATION_UNVERIFIED'))
                """, Integer.class, bin(TENANT_ID), bin(submissionId), bin(visitAttemptId))).isEqualTo(1);

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("q", "定位失败仍完成真实拜访记录"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].locationVerificationStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.items[0].locationFailureReason").value("TIMEOUT"))
                .andExpect(jsonPath("$.items[0].locationAttemptId").value(visitAttemptId.toString()))
                .andExpect(jsonPath("$.items[0].riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.items[0].riskFlags",
                        containsInAnyOrder("LOCATION_UNVERIFIED")));
    }

    @Test
    void discardsUnusableLocationEvidenceInsteadOfBlockingTheUnverifiedFallback()
            throws Exception {
        LocationCommand unusableEvidence = new LocationCommand(
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270"),
                new BigDecimal("25000.00"), Instant.now().minusSeconds(10), "网络粗略定位");

        UUID storeAttemptId = UUID.randomUUID();
        MvcResult storeResult = mockMvc.perform(post("/sales-checkin/api/v1/stores/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverifiedStore(
                                UUID.randomUUID(), "超粗定位仍可录入门店", unusableEvidence,
                                "ACCURACY_INSUFFICIENT", storeAttemptId))))
                .andExpect(status().isOk())
                .andReturn();
        UUID storeId = UUID.fromString(objectMapper.readTree(
                storeResult.getResponse().getContentAsByteArray()).path("id").asText());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND id=? AND location_verification_status='UNVERIFIED'
                   AND location_failure_reason='ACCURACY_INSUFFICIENT'
                   AND longitude IS NULL AND latitude IS NULL AND accuracy_meters IS NULL
                   AND location_captured_at IS NULL
                """, Integer.class, bin(TENANT_ID), bin(storeId))).isEqualTo(1);

        UUID submissionAttemptId = UUID.randomUUID();
        MvcResult submissionResult = mockMvc.perform(post(
                        "/sales-checkin/api/v1/submissions/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverifiedSubmission(
                                UUID.randomUUID(), STORE_ID, "超粗定位仍可完成拜访留档",
                                unusableEvidence, "ACCURACY_INSUFFICIENT", submissionAttemptId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                submissionResult.getResponse().getContentAsByteArray()).path("id").asText());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=? AND location_verification_status='UNVERIFIED'
                   AND location_failure_reason='ACCURACY_INSUFFICIENT'
                   AND longitude IS NULL AND latitude IS NULL AND accuracy_meters IS NULL
                   AND location_captured_at IS NULL
                """, Integer.class, bin(TENANT_ID), bin(submissionId))).isEqualTo(1);
    }

    @Test
    void doesNotUseUnverifiedStoreCoordinatesAsStrictAnchorAndAllowsVerifiedUpgrade()
            throws Exception {
        UUID clientStoreId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        LocationCommand impreciseEvidence = new LocationCommand(
                new BigDecimal("116.3971280"), new BigDecimal("39.9165270"),
                new BigDecimal("650.00"), Instant.now().minusSeconds(20), "仅记录到的粗略坐标");
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/stores/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverifiedStore(
                                clientStoreId, "待补定位门店", impreciseEvidence,
                                "ACCURACY_INSUFFICIENT", attemptId))))
                .andExpect(status().isOk())
                .andReturn();
        String storeId = objectMapper.readTree(created.getResponse().getContentAsByteArray())
                .path("id").asText();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND id=? AND location_verification_status='UNVERIFIED'
                   AND location_failure_reason='ACCURACY_INSUFFICIENT' AND location_attempt_id=?
                   AND longitude=116.3971280 AND latitude=39.9165270 AND accuracy_meters=650.00
                """, Integer.class, bin(TENANT_ID), bin(UUID.fromString(storeId)), bin(attemptId))).isEqualTo(1);

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submissionForStore(
                                UUID.randomUUID(), UUID.fromString(storeId),
                                "不能把粗略坐标当严格锚点", location()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("门店缺少有效定位，请先补录门店定位"));

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                clientStoreId, "待补定位门店", location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(storeId));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND id=? AND location_verification_status='VERIFIED'
                   AND location_failure_reason IS NULL AND location_attempt_id IS NULL
                   AND longitude IS NOT NULL AND latitude IS NOT NULL
                """, Integer.class, bin(TENANT_ID), bin(UUID.fromString(storeId)))).isEqualTo(1);

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submissionForStore(
                                UUID.randomUUID(), UUID.fromString(storeId),
                                "补全定位后正常严格打卡", location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void rejectsMixedStrictAndUnverifiedContractsAndConflictingSubmissionRetries()
            throws Exception {
        UUID attemptId = UUID.randomUUID();
        mockMvc.perform(post("/sales-checkin/api/v1/submissions/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverifiedSubmission(
                                UUID.randomUUID(), STORE_ID, "缺少失败原因", null,
                                null, attemptId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("locationFailureReason不能为空"));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverifiedSubmission(
                                UUID.randomUUID(), STORE_ID, "无效失败原因", null,
                                "NOT_A_REAL_REASON", attemptId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("locationFailureReason无效"));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverifiedSubmission(
                                UUID.randomUUID(), STORE_ID, "缺少尝试编号", null,
                                "TIMEOUT", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("locationAttemptId不能为空"));

        mockMvc.perform(post("/sales-checkin/api/v1/stores/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverifiedStore(
                                UUID.randomUUID(), "缺少尝试编号门店", null, "TIMEOUT", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("locationAttemptId不能为空"));

        LocationCommand captured = location();
        CreateSubmissionRequest unverifiedWithProof = new CreateSubmissionRequest(
                UUID.randomUUID(), SUBMISSION_KEY, "北京", VISITOR_ID, STORE_ID,
                "李经理", "13900000000", "未核验路径不得混入定位凭证", captured, true,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION,
                locationVerificationToken(VISITOR_ID, "北京", captured), "TIMEOUT", attemptId);
        mockMvc.perform(post("/sales-checkin/api/v1/submissions/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverifiedWithProof)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("定位未核验留档不能携带位置验证凭证"));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverifiedSubmission(
                                UUID.randomUUID(), STORE_ID, "严格路径不得带失败标记", captured,
                                "TIMEOUT", attemptId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("正常定位提交不能携带定位失败标记"));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "严格路径无定位", true, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("定位经纬度、精度和采集时间不能为空"));

        CreateStoreRequest unverifiedWithToken = new CreateStoreRequest(
                UUID.randomUUID(), "北京", VISITOR_ID,
                null, null, null, null, null,
                "台球", "错误混用凭证门店", "营业中", "提交店长", "13800000000",
                "100-300平米", "10张球桌", List.of("竞技赛事"), List.of("高德业务"),
                "高意向", "A类", List.of("单店"), null, null, "forged-proof", null,
                "TIMEOUT", attemptId);
        mockMvc.perform(post("/sales-checkin/api/v1/stores/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverifiedWithToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("定位未核验时只能手工录入门店资料"));

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverifiedStore(
                                UUID.randomUUID(), "严格建店无定位", null, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("定位经纬度、精度和采集时间不能为空"));

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverifiedStore(
                                UUID.randomUUID(), "严格建店不得带失败标记", captured,
                                "TIMEOUT", attemptId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("正常定位提交不能携带定位失败标记"));

        UUID clientSubmissionId = UUID.randomUUID();
        UUID retryAttemptId = UUID.randomUUID();
        CreateSubmissionRequest unverified = unverifiedSubmission(
                clientSubmissionId, STORE_ID, "未核验幂等草稿", null,
                "POSITION_UNAVAILABLE", retryAttemptId);
        MvcResult first = mockMvc.perform(post("/sales-checkin/api/v1/submissions/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverified)))
                .andExpect(status().isOk())
                .andReturn();
        String submissionId = objectMapper.readTree(first.getResponse().getContentAsByteArray())
                .path("id").asText();
        mockMvc.perform(post("/sales-checkin/api/v1/submissions/unverified-location")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unverified)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(submissionId));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new CreateSubmissionRequest(
                                clientSubmissionId, SUBMISSION_KEY, "北京", VISITOR_ID, STORE_ID,
                                "李经理", "13900000000", "未核验幂等草稿", location(), true,
                                TemporaryCheckinService.PRIVACY_NOTICE_VERSION,
                                locationVerificationToken(VISITOR_ID, "北京", location())))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_CONFLICT"));
    }

    @Test
    void fallsBackToFirstAcceptableVisitWhenStoreCoordinatesHaveUnacceptableAccuracy()
            throws Exception {
        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET accuracy_meters=350.01
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));
        UUID firstAcceptable = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "历史客户", "合格锚点");
        Instant submittedAt = Instant.now().minusSeconds(90);
        markSubmitted(firstAcceptable, submittedAt.minusSeconds(10), submittedAt);

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores[0].storeId").value(STORE_ID.toString()))
                .andExpect(jsonPath("$.nearbyStores[0].locationSource")
                        .value("FIRST_SUBMITTED_VISIT"));
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "使用首次合格锚点", true,
                                location()))))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsStaleLocationForStoreAndSubmissionWritesWithoutCallingAmap()
            throws Exception {
        LocationCommand stale = new LocationCommand(new BigDecimal("116.3971280"),
                new BigDecimal("39.9165270"), new BigDecimal("8.50"),
                Instant.now().minusSeconds(61 * 60), "过期定位");
        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, stale))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.maxLocationAgeMinutes").value(60))
                .andExpect(jsonPath("$.accuracyAccepted").value(true))
                .andExpect(jsonPath("$.freshnessAccepted").value(false))
                .andExpect(jsonPath("$.locationMessage")
                        .value("定位采集时间已超过60分钟，请重新定位后提交"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(0)));
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "过期定位打卡", true, stale))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("定位采集时间已超过60分钟，请重新定位后提交"));
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(poiStoreAtLocation(
                                UUID.randomUUID(), "过期定位新店", "赵店长", stale))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("定位采集时间已超过60分钟，请重新定位后提交"));
    }

    @Test
    void returnsExistingStoreAndDraftForExactLongAgedRetriesButStillRejectsWrongSalesperson()
            throws Exception {
        UUID clientStoreId = UUID.randomUUID();
        CreateStoreRequest initialStore = manualStore(clientStoreId, "长期幂等门店", location());
        MvcResult createdStore = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(initialStore)))
                .andExpect(status().isOk())
                .andReturn();
        String storeId = objectMapper.readTree(createdStore.getResponse().getContentAsByteArray())
                .path("id").asText();
        Instant longAgo = Instant.now().minus(java.time.Duration.ofDays(45))
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("""
                UPDATE temp_sales_checkin_store SET location_captured_at=?
                 WHERE tenant_id=? AND client_store_id=?
                """, Timestamp.from(longAgo), bin(TENANT_ID), bin(clientStoreId));
        LocationCommand agedStoreLocation = new LocationCommand(initialStore.location().longitude(),
                initialStore.location().latitude(), initialStore.location().accuracyMeters(),
                longAgo, initialStore.location().note());
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                clientStoreId, "长期幂等门店", agedStoreLocation))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(storeId));

        UUID clientSubmissionId = UUID.randomUUID();
        CreateSubmissionRequest initialSubmission = submission(
                clientSubmissionId, SUBMISSION_KEY, "长期幂等草稿", true, location());
        MvcResult createdDraft = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(initialSubmission)))
                .andExpect(status().isOk())
                .andReturn();
        String submissionId = objectMapper.readTree(createdDraft.getResponse().getContentAsByteArray())
                .path("id").asText();
        jdbc.update("""
                UPDATE temp_sales_checkin_submission SET location_captured_at=?
                 WHERE tenant_id=? AND client_submission_id=?
                """, Timestamp.from(longAgo), bin(TENANT_ID), bin(clientSubmissionId));
        LocationCommand agedSubmissionLocation = new LocationCommand(
                initialSubmission.location().longitude(), initialSubmission.location().latitude(),
                initialSubmission.location().accuracyMeters(), longAgo, initialSubmission.location().note());
        CreateSubmissionRequest agedRetry = submission(
                clientSubmissionId, SUBMISSION_KEY, "长期幂等草稿", true, agedSubmissionLocation);
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(agedRetry)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(submissionId));

        CreateSubmissionRequest wrongSalesperson = new CreateSubmissionRequest(
                clientSubmissionId, SUBMISSION_KEY, "北京", CREATOR_ID, STORE_ID,
                agedRetry.customerName(), agedRetry.customerPhone(), agedRetry.visitResult(),
                agedRetry.location(), true, TemporaryCheckinService.PRIVACY_NOTICE_VERSION);
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(wrongSalesperson)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_CONFLICT"));
    }

    @Test
    void rejectsNewLocationsOverTwoMinutesInFutureButAllowsSmallClockSkewAndExistingRetry()
            throws Exception {
        LocationCommand tooFarFuture = new LocationCommand(new BigDecimal("116.3971280"),
                new BigDecimal("39.9165270"), new BigDecimal("8.50"),
                Instant.now().plusSeconds(3 * 60), "手机时间超前");
        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, tooFarFuture))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freshnessAccepted").value(false))
                .andExpect(jsonPath("$.locationMessage")
                        .value("定位采集时间晚于服务器时间超过2分钟，请校准手机时间并重新定位"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(0)));
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "未来时间新请求", true,
                                tooFarFuture))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("定位采集时间晚于服务器时间超过2分钟，请校准手机时间并重新定位"));
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                UUID.randomUUID(), "未来时间新门店", tooFarFuture))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("定位采集时间晚于服务器时间超过2分钟，请校准手机时间并重新定位"));

        Instant acceptedFuture = Instant.now().plusSeconds(90)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        LocationCommand withinSkew = new LocationCommand(new BigDecimal("116.3971280"),
                new BigDecimal("39.9165270"), new BigDecimal("8.50"), acceptedFuture, "轻微时钟偏差");
        UUID clientSubmissionId = UUID.randomUUID();
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                clientSubmissionId, SUBMISSION_KEY, "边界内新请求", true, withinSkew))))
                .andExpect(status().isOk())
                .andReturn();
        String submissionId = objectMapper.readTree(created.getResponse().getContentAsByteArray())
                .path("id").asText();

        Instant replayFuture = Instant.now().plusSeconds(5 * 60)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("""
                UPDATE temp_sales_checkin_submission SET location_captured_at=?
                 WHERE tenant_id=? AND client_submission_id=?
                """, Timestamp.from(replayFuture), bin(TENANT_ID), bin(clientSubmissionId));
        LocationCommand exactExistingFuture = new LocationCommand(withinSkew.longitude(), withinSkew.latitude(),
                withinSkew.accuracyMeters(), replayFuture, withinSkew.note());
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                clientSubmissionId, SUBMISSION_KEY, "边界内新请求", true,
                                exactExistingFuture))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(submissionId));
    }

    @Test
    void reverseGeocoderFailureStillUsesSignedCoordinatesForVisitAndNewStoreFlows() throws Exception {
        when(reverseGeocoder.resolve(any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(GeocodeResult.failed("AMAP_UNAVAILABLE"));

        LocationCommand captured = location();
        MvcResult resolved = mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, captured))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geocodeStatus").value("FAILED"))
                .andExpect(jsonPath("$.cityMatched").doesNotExist())
                .andExpect(jsonPath("$.locationMessage").value(
                        "真实坐标已获取，详细地址暂未取得；门店仅按当前位置300米范围选择"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(1)))
                .andExpect(jsonPath("$.locationVerificationToken").isNotEmpty())
                .andReturn();
        String failedProof = objectMapper.readTree(resolved.getResponse().getContentAsByteArray())
                .path("locationVerificationToken").asText();

        UUID clientStoreId = UUID.randomUUID();
        MvcResult searched = mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new SearchNewStoreRequest(
                                clientStoreId, "北京", VISITOR_ID, captured, "台球", failedProof))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poiLookupStatus").value("EMPTY"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(0)))
                .andExpect(jsonPath("$.manualEntryToken").isNotEmpty())
                .andReturn();
        String manualEntryToken = objectMapper.readTree(searched.getResponse().getContentAsByteArray())
                .path("manualEntryToken").asText();

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                clientStoreId, "地址待恢复新店", captured,
                                failedProof, manualEntryToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("地址待恢复新店"));

        CreateSubmissionRequest visit = new CreateSubmissionRequest(
                UUID.randomUUID(), SUBMISSION_KEY, "北京", VISITOR_ID, STORE_ID,
                "地址待恢复客户", null, "按签名坐标完成现场校验", captured, true,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION, failedProof);
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(visit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        verify(reverseGeocoder, times(1)).resolve(any(BigDecimal.class), any(BigDecimal.class));
        verify(amapPoiClient, times(1)).searchAround("台球", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25);
        verify(coordinateConverter, times(1)).convert(captured.longitude(), captured.latitude());
    }

    @Test
    void allowsFirstVisitImmediatelyAfterCompletingANearbyNewStoreProfile() throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "首访新门店", "周店长"))))
                .andExpect(status().isOk())
                .andReturn();
        UUID newStoreId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND store_id=? AND status='SUBMITTED'
                """, Integer.class, bin(TENANT_ID), bin(newStoreId))).isZero();

        LocationCommand firstVisitLocation = location();
        CreateSubmissionRequest firstVisit = new CreateSubmissionRequest(
                UUID.randomUUID(), SUBMISSION_KEY, "北京", VISITOR_ID, newStoreId,
                "周店长", "13800000000", "首访已完成基础沟通", firstVisitLocation, true,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION,
                locationVerificationToken(VISITOR_ID, "北京", firstVisitLocation));
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(firstVisit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void forwardsProtectedAdminPageEntryToBundledStaticPage() throws Exception {
        mockMvc.perform(get("/sales-checkin/admin/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/sales-checkin/admin/index.html"));
    }

    @Test
    void removesLegacyBasicAuthAccountSwitchEndpoint() throws Exception {
        mockMvc.perform(post("/sales-checkin/admin/account-switches")
                        .with(admin("sales-checkin-admin")))
                .andExpect(status().isNotFound());
    }

    @Test
    void exposesPublicFixedTenantApiAndEnforcesPayloadAwareIdempotency() throws Exception {
        mockMvc.perform(get("/sales-checkin/api/v1/options")
                        .param("city", "北京")
                        .header("X-Tenant-Id", OTHER_TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salespersons", hasSize(2)))
                .andExpect(jsonPath("$.salespersons[*].id", containsInAnyOrder(
                        CREATOR_ID.toString(), VISITOR_ID.toString())))
                .andExpect(jsonPath("$.storeTags", containsInAnyOrder(
                        "追分", "连锁", "单店", "好沟通", "品牌店", "可动销",
                        "已加微信", "老板不在", "已合作", "商场店", "已有竞品合作")));

        UUID clientSubmissionId = UUID.randomUUID();
        CreateSubmissionRequest request = submission(clientSubmissionId, SUBMISSION_KEY, "完成首次沟通", true,
                location());
        MvcResult first = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        String submissionId = objectMapper.readTree(first.getResponse().getContentAsByteArray())
                .path("id").asText();

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(submissionId));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                clientSubmissionId, SUBMISSION_KEY, "变更后的内容", true, location()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_CONFLICT"));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                clientSubmissionId, OTHER_SUBMISSION_KEY, "完成首次沟通", true,
                                request.location()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_KEY_INVALID"));
    }

    @Test
    void reusesRegisteredAmapPoiAcrossDifferentBrowserClientIds() throws Exception {
        CreateStoreRequest first = poiStore(UUID.randomUUID(), "高德候选门店", "张店长");
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(first)))
                .andExpect(status().isOk())
                .andReturn();
        String firstStoreId = objectMapper.readTree(created.getResponse().getContentAsByteArray())
                .path("id").asText();

        CreateStoreRequest retriedFromAnotherBrowser = poiStore(
                UUID.randomUUID(), "高德候选门店新名", "李店长");
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(retriedFromAnotherBrowser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstStoreId))
                .andExpect(jsonPath("$.name").value("高德候选门店"));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND source_poi_id='B0FFTESTPOI'
                """, Integer.class, bin(TENANT_ID))).isEqualTo(1);
    }

    @Test
    void acceptsExistingFeishuStoreTagsWithoutMisclassifyingTheStoreAsIncomplete() throws Exception {
        jdbc.update("""
                UPDATE temp_sales_checkin_store SET tags_json=JSON_ARRAY('已合作')
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores[0].source").value("REGISTERED"))
                .andExpect(jsonPath("$.nearbyStores[0].storeId").value(STORE_ID.toString()))
                .andExpect(jsonPath("$.nearbyStores[0].checkinEligible").value(true));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "历史标签门店拜访", true, location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void keepsIncompleteBoundPoiVisibleAndCompletesItsProfileBeforeCheckin() throws Exception {
        MvcResult first = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "高德候选门店", "旧联系人"))))
                .andExpect(status().isOk())
                .andReturn();
        UUID existingId = UUID.fromString(objectMapper.readTree(
                first.getResponse().getContentAsByteArray()).path("id").asText());
        jdbc.update("""
                UPDATE temp_sales_checkin_store SET tags_json=JSON_ARRAY('待补全旧标签')
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(existingId));

        mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(searchNewStoreRequest(
                                "北京", VISITOR_ID, location(), "高德候选门店"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores", hasSize(1)))
                .andExpect(jsonPath("$.nearbyStores[0].source").value("AMAP_POI"))
                .andExpect(jsonPath("$.nearbyStores[0].poiId").value("B0FFTESTPOI"))
                .andExpect(jsonPath("$.nearbyStores[0].nextAction").value("COMPLETE_STORE_PROFILE"));

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "客户端名称", "补全联系人"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(existingId.toString()))
                .andExpect(jsonPath("$.name").value("高德候选门店"));

        var completed = jdbc.queryForMap("""
                SELECT contact_name, tags_json FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(existingId));
        assertThat(completed.get("contact_name")).isEqualTo("补全联系人");
        assertThat(completed.get("tags_json").toString()).contains("单店");

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, location(), "高德候选门店"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores[0].source").value("REGISTERED"))
                .andExpect(jsonPath("$.nearbyStores[0].storeId").value(existingId.toString()))
                .andExpect(jsonPath("$.nearbyStores[0].nextAction").value("CHECK_IN"));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submissionForStore(
                                UUID.randomUUID(), existingId, "补全后成功拜访", location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void keepsCompleteButUnanchoredBoundPoiVisibleAndRepairsItsLocation() throws Exception {
        MvcResult first = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "高德候选门店", "原联系人"))))
                .andExpect(status().isOk())
                .andReturn();
        UUID existingId = UUID.fromString(objectMapper.readTree(
                first.getResponse().getContentAsByteArray()).path("id").asText());
        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET source_poi_longitude=NULL, source_poi_latitude=NULL,
                       longitude=NULL, latitude=NULL, accuracy_meters=NULL, location_captured_at=NULL
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(existingId));

        mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(searchNewStoreRequest(
                                "北京", VISITOR_ID, location(), "高德候选门店"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores", hasSize(1)))
                .andExpect(jsonPath("$.nearbyStores[0].source").value("AMAP_POI"))
                .andExpect(jsonPath("$.nearbyStores[0].nextAction").value("COMPLETE_STORE_PROFILE"));

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "客户端名称", "现场联系人"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(existingId.toString()));

        var repaired = jdbc.queryForMap("""
                SELECT source_poi_longitude, source_poi_latitude, longitude, latitude
                  FROM temp_sales_checkin_store WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(existingId));
        assertThat(repaired.get("source_poi_longitude")).isNotNull();
        assertThat(repaired.get("source_poi_latitude")).isNotNull();
        assertThat(repaired.get("longitude")).isNotNull();
        assertThat(repaired.get("latitude")).isNotNull();

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submissionForStore(
                                UUID.randomUUID(), existingId, "修复定位后拜访", location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void searchesOnceThenPersistsSignedCanonicalAmapSnapshotWithoutASecondQuery() throws Exception {
        UUID clientStoreId = UUID.randomUUID();
        LocationCommand searchLocation = location();
        MvcResult searched = mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(searchNewStoreRequest(
                                clientStoreId, "北京", VISITOR_ID, searchLocation, "高德候选门店"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores[0].selectionToken").isNotEmpty())
                .andReturn();
        var searchPayload = objectMapper.readTree(searched.getResponse().getContentAsByteArray());
        String selectionToken = searchPayload.path("nearbyStores").get(0).path("selectionToken").asText();
        String locationVerificationToken = searchPayload.path("locationVerificationToken").asText();
        verify(amapPoiClient, times(1)).searchAround("高德候选门店",
                new BigDecimal("116.403000"), new BigDecimal("39.912000"), 300, 1, 25);
        verifyNoInteractions(reverseGeocoder);
        clearInvocations(reverseGeocoder, amapPoiClient, coordinateConverter);

        CreateStoreRequest clientSupplied = new CreateStoreRequest(
                clientStoreId, "北京", VISITOR_ID,
                "B0FFTESTPOI", "高德候选门店", "客户端伪造地址",
                BigDecimal.ZERO, BigDecimal.ZERO,
                "台球", "客户端伪造名称", "营业中", "张店长", "13800000000",
                "100-300平米", "10张球桌", List.of("竞技赛事"), List.of("高德业务"),
                "高意向", "A类", List.of("单店"), searchLocation, selectionToken,
                locationVerificationToken, null);

        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(clientSupplied)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("高德候选门店"))
                .andReturn();
        UUID createdId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        var canonical = jdbc.queryForMap("""
                SELECT name, source_poi_name, source_poi_address,
                       source_poi_longitude, source_poi_latitude, longitude, latitude
                  FROM temp_sales_checkin_store WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(createdId));
        assertThat(canonical.get("name")).isEqualTo("高德候选门店");
        assertThat(canonical.get("source_poi_name")).isEqualTo("高德候选门店");
        assertThat(canonical.get("source_poi_address")).isEqualTo("北京市东城区服务端地址");
        assertThat((BigDecimal) canonical.get("source_poi_longitude"))
                .isEqualByComparingTo("116.403000");
        assertThat((BigDecimal) canonical.get("source_poi_latitude"))
                .isEqualByComparingTo("39.912000");
        assertThat((BigDecimal) canonical.get("longitude")).isEqualByComparingTo("116.3971280");
        assertThat((BigDecimal) canonical.get("latitude")).isEqualByComparingTo("39.9165270");
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(clientSupplied)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId.toString()));
        verifyNoInteractions(reverseGeocoder, amapPoiClient);
        verify(coordinateConverter, times(1))
                .convert(searchLocation.longitude(), searchLocation.latitude());
    }

    @Test
    void rejectsSignedNearbyCandidateWhenRefreshedSaveLocationIsOver300MetersFromPoi()
            throws Exception {
        UUID clientStoreId = UUID.randomUUID();
        LocationCommand searchLocation = location();
        MvcResult searched = mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(searchNewStoreRequest(
                                clientStoreId, "北京", VISITOR_ID, searchLocation, "高德候选门店"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores[0].selectionToken").isNotEmpty())
                .andReturn();
        String selectionToken = objectMapper.readTree(searched.getResponse().getContentAsByteArray())
                .path("nearbyStores").get(0).path("selectionToken").asText();

        LocationCommand refreshedSaveLocation = new LocationCommand(
                new BigDecimal("116.3991280"), searchLocation.latitude(),
                searchLocation.accuracyMeters(), Instant.now().minusSeconds(10), "重新定位点");
        String refreshedLocationProof = locationVerificationToken(
                VISITOR_ID, "北京", refreshedSaveLocation);
        clearInvocations(reverseGeocoder, amapPoiClient, coordinateConverter);
        when(coordinateConverter.convert(any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(new Wgs84Gcj02Converter.Coordinates(
                        new BigDecimal("116.409000"), new BigDecimal("39.912000")));

        CreateStoreRequest request = new CreateStoreRequest(
                clientStoreId, "北京", VISITOR_ID,
                "B0FFTESTPOI", "高德候选门店", "北京市东城区服务端地址",
                new BigDecimal("116.403000"), new BigDecimal("39.912000"),
                "台球", "高德候选门店", "营业中", "张店长", "13800000000",
                "100-300平米", "10张球桌", List.of("竞技赛事"), List.of("高德业务"),
                "高意向", "A类", List.of("单店"), refreshedSaveLocation, selectionToken,
                refreshedLocationProof, null);

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith("当前定位距离所选高德门店约")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "超过允许的300米")));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND client_store_id=?
                """, Integer.class, bin(TENANT_ID), bin(clientStoreId))).isZero();
        verifyNoInteractions(reverseGeocoder, amapPoiClient);
        verify(coordinateConverter, times(1)).convert(
                refreshedSaveLocation.longitude(), refreshedSaveLocation.latitude());
    }

    @Test
    void usesOriginalWgsStoreAnchorForResolveAndEveryLaterCheckinWithoutAmap()
            throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "高德候选门店", "张店长"))))
                .andExpect(status().isOk())
                .andReturn();
        UUID poiStoreId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        clearInvocations(reverseGeocoder, amapPoiClient, coordinateConverter);

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, location(), "高德"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores[0].storeId").value(poiStoreId.toString()))
                .andExpect(jsonPath("$.nearbyStores[0].locationSource").value("STORE_LOCATION"))
                .andExpect(jsonPath("$.nearbyStores[0].distanceMeters").value(0))
                .andExpect(jsonPath("$.nearbyStores[0].longitude").doesNotExist())
                .andExpect(jsonPath("$.nearbyStores[0].latitude").doesNotExist());

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submissionForStore(
                                UUID.randomUUID(), poiStoreId, "POI内首次拜访", location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        LocationCommand shifted = new LocationCommand(new BigDecimal("116.4071280"),
                new BigDecimal("39.9165270"), new BigDecimal("8.50"),
                Instant.now().minusSeconds(30), "远离建档WGS84锚点");

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, shifted, "高德"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores", hasSize(0)));
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submissionForStore(
                                UUID.randomUUID(), poiStoreId, "双距离绕过尝试", shifted))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith("当前定位距离门店约")));
        verify(reverseGeocoder, times(2)).resolve(any(BigDecimal.class), any(BigDecimal.class));
        verifyNoInteractions(amapPoiClient, coordinateConverter);
    }

    @Test
    void rejectsUnsignedPoiKeepsManualStorePendingAndNeverRetriesAmapOnSave() throws Exception {
        LocationCommand unsignedLocation = location();
        CreateStoreRequest unsignedPoi = new CreateStoreRequest(
                UUID.randomUUID(), "北京", VISITOR_ID,
                "B0FFTESTPOI", "高德候选门店", "伪造地址", BigDecimal.ZERO, BigDecimal.ZERO,
                "台球", "伪造门店", "营业中", "赵店长", "13800000000",
                "100-300平米", "10张球桌", List.of("竞技赛事"), List.of("高德业务"),
                "高意向", "A类", List.of("单店"), unsignedLocation, null,
                locationVerificationToken(VISITOR_ID, "北京", unsignedLocation), null);
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(unsignedPoi)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith("高德门店候选已过期")));

        MvcResult manual = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                UUID.randomUUID(), "附近手工门店", location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID manualId = UUID.fromString(objectMapper.readTree(
                manual.getResponse().getContentAsByteArray()).path("id").asText());
        var manualRow = jdbc.queryForMap("""
                SELECT source_poi_id, geocode_status, geocoded_at
                  FROM temp_sales_checkin_store WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(manualId));
        assertThat(manualRow.get("source_poi_id")).isNull();
        assertThat(manualRow.get("geocode_status")).isEqualTo("RESOLVED");
        assertThat(manualRow.get("geocoded_at")).isNotNull();

        UUID selectedClientStoreId = UUID.randomUUID();
        CreateStoreRequest selectedPoi = poiStore(
                selectedClientStoreId, "客户端名称", "赵店长");
        when(amapPoiClient.searchAround("高德候选门店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25))
                .thenThrow(new com.rigour.sales.application.port.out.AmapPoiException("上游失败"));
        MvcResult selected = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(selectedPoi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("高德候选门店"))
                .andReturn();
        UUID selectedStoreId = UUID.fromString(objectMapper.readTree(
                selected.getResponse().getContentAsByteArray()).path("id").asText());
        assertThat(jdbc.queryForObject("""
                SELECT source_poi_id FROM temp_sales_checkin_store WHERE tenant_id=? AND id=?
                """, String.class, bin(TENANT_ID), bin(selectedStoreId))).isEqualTo("B0FFTESTPOI");
        verifyNoInteractions(reverseGeocoder, amapPoiClient);
        verify(coordinateConverter, times(1))
                .convert(selectedPoi.location().longitude(), selectedPoi.location().latitude());

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(selectedPoi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(selectedStoreId.toString()));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND client_store_id=?
                """, Integer.class, bin(TENANT_ID), bin(selectedClientStoreId))).isEqualTo(1);
    }

    @Test
    void keepsOriginalManualPayloadPendingAndRetriesWithoutCallingAmap() throws Exception {
        UUID clientStoreId = UUID.randomUUID();
        LocationCommand capturedLocation = location();
        CreateStoreRequest original = manualStore(
                clientStoreId, "弱网自动升级门店", capturedLocation);
        when(amapPoiClient.searchAround("弱网自动升级门店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25))
                .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(
                        new AmapPoiClient.NearbyPoi(
                                "B0FFRETRY", "弱网自动升级门店", "现场地址", "休闲服务", "080000",
                                new BigDecimal("116.403000"), new BigDecimal("39.912000"), BigDecimal.ZERO)),
                        1, 25, 1));

        MvcResult first = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(original)))
                .andExpect(status().isOk())
                .andReturn();
        String storeId = objectMapper.readTree(first.getResponse().getContentAsByteArray())
                .path("id").asText();

        reset(amapPoiClient);
        when(amapPoiClient.searchAround(any(String.class), any(BigDecimal.class), any(BigDecimal.class),
                anyInt(), anyInt(), anyInt()))
                .thenThrow(new com.rigour.sales.application.port.out.AmapPoiException("上游已不可用"));
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(original)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(storeId));
        verifyNoInteractions(amapPoiClient);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND client_store_id=? AND source_poi_id IS NULL
                   AND geocode_status='RESOLVED'
                """, Integer.class, bin(TENANT_ID), bin(clientStoreId))).isEqualTo(1);

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                clientStoreId, "不同门店载荷", capturedLocation))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("clientStoreId已被不同门店数据使用"));
    }

    @Test
    void selectedPoiCreatesNewStoreWithoutRewritingSameNameHistoricalImport() throws Exception {
        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET name='高德候选门店', longitude=NULL, latitude=NULL,
                       accuracy_meters=NULL, location_captured_at=NULL, location_note=NULL,
                       source_poi_id=NULL, source_poi_name=NULL, source_poi_address=NULL,
                       source_poi_longitude=NULL, source_poi_latitude=NULL
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));

        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "客户端名称", "提交店长"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(created.getResponse().getContentAsByteArray())
                .path("id").asText()).isNotEqualTo(STORE_ID.toString());
        var historical = jdbc.queryForMap("""
                SELECT contact_name, business_types_json, source_poi_id, source_poi_address,
                       longitude, latitude
                  FROM temp_sales_checkin_store WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));
        assertThat(historical.get("contact_name")).isEqualTo("王店长");
        assertThat(historical.get("business_types_json").toString()).contains("竞技赛事");
        assertThat(historical.get("source_poi_id")).isNull();
        assertThat(historical.get("source_poi_address")).isNull();
        assertThat(historical.get("longitude")).isNull();
        assertThat(historical.get("latitude")).isNull();
    }

    @Test
    void manualSameNameCreatesNewGpsStoreWithoutRewritingHistoricalImport() throws Exception {
        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET name='手工导入门店', longitude=NULL, latitude=NULL,
                       accuracy_meters=NULL, location_captured_at=NULL, location_note=NULL,
                       source_poi_id=NULL, source_poi_name=NULL, source_poi_address=NULL,
                       source_poi_longitude=NULL, source_poi_latitude=NULL
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));

        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                UUID.randomUUID(), "手工导入门店", location()))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(created.getResponse().getContentAsByteArray())
                .path("id").asText()).isNotEqualTo(STORE_ID.toString());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND city='北京' AND name='手工导入门店'
                """, Integer.class, bin(TENANT_ID))).isEqualTo(2);
        var historical = jdbc.queryForMap("""
                SELECT contact_name, business_types_json, longitude, latitude, source_poi_id
                  FROM temp_sales_checkin_store WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));
        assertThat(historical.get("contact_name")).isEqualTo("王店长");
        assertThat(historical.get("business_types_json").toString()).contains("竞技赛事");
        assertThat(historical.get("longitude")).isNull();
        assertThat(historical.get("latitude")).isNull();
        assertThat(historical.get("source_poi_id")).isNull();
    }

    @Test
    void savesManualSameNameStoreWithoutImplicitAmapLookup() throws Exception {
        when(amapPoiClient.searchAround("重名高德门店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25))
                .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(
                        new AmapPoiClient.NearbyPoi(
                                "B0FFSAME01", "重名高德门店", "A座", "休闲服务", "080000",
                                new BigDecimal("116.403000"), new BigDecimal("39.912000"), BigDecimal.ZERO),
                        new AmapPoiClient.NearbyPoi(
                                "B0FFSAME02", "重名高德门店", "B座", "休闲服务", "080000",
                                new BigDecimal("116.403100"), new BigDecimal("39.912100"), BigDecimal.TEN)),
                        1, 25, 2));
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                UUID.randomUUID(), "重名高德门店", location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID createdId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        assertThat(jdbc.queryForObject("""
                SELECT geocode_status FROM temp_sales_checkin_store WHERE tenant_id=? AND id=?
                """, String.class, bin(TENANT_ID), bin(createdId))).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND source_poi_id IN ('B0FFSAME01', 'B0FFSAME02')
                """, Integer.class, bin(TENANT_ID))).isZero();
        verifyNoInteractions(amapPoiClient, reverseGeocoder, coordinateConverter);
    }

    @Test
    void doesNotReuseNearbyHistoricalStoreByNameAlone() throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                UUID.randomUUID(), "已导入门店", location()))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(created.getResponse().getContentAsByteArray())
                .path("id").asText()).isNotEqualTo(STORE_ID.toString());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND city='北京' AND name='已导入门店'
                """, Integer.class, bin(TENANT_ID))).isEqualTo(2);
    }

    @Test
    void allowsNewManualBranchWhenSameNameHistoricalStoreIsFarAway() throws Exception {
        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET name='同名分店', longitude=116.5000000, latitude=39.9165270,
                       accuracy_meters=8.50, location_captured_at=?
                 WHERE tenant_id=? AND id=?
                """, Timestamp.from(Instant.now().minusSeconds(60)), bin(TENANT_ID), bin(STORE_ID));

        MvcResult result = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                UUID.randomUUID(), "同名分店", location()))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText()).isNotEqualTo(STORE_ID.toString());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND city='北京' AND name='同名分店'
                """, Integer.class, bin(TENANT_ID))).isEqualTo(2);
    }

    @Test
    void validatesSalespersonBeforeReturningIdempotentStoreAndSubmission() throws Exception {
        UUID clientStoreId = UUID.randomUUID();
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(clientStoreId, "首次创建", "赵店长"))))
                .andExpect(status().isOk());
        CreateStoreRequest wrongStoreRetry = new CreateStoreRequest(
                clientStoreId, "北京", SHENZHEN_SALESPERSON_ID,
                "B0FFTESTPOI", "高德候选门店", "北京市东城区测试路1号",
                new BigDecimal("116.397128"), new BigDecimal("39.916527"),
                "台球", "首次创建", "营业中", "赵店长", "13800000000", "100-300平米",
                "10张球桌", List.of("竞技赛事"), List.of("高德业务"), "高意向", "A类",
                List.of("单店"), location());
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(wrongStoreRetry)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("销售与选择城市不一致"));

        UUID clientSubmissionId = UUID.randomUUID();
        CreateSubmissionRequest first = submission(
                clientSubmissionId, SUBMISSION_KEY, "幂等草稿", true, location());
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(first)))
                .andExpect(status().isOk());
        CreateSubmissionRequest wrongSubmissionRetry = new CreateSubmissionRequest(
                clientSubmissionId, SUBMISSION_KEY, "北京", SHENZHEN_SALESPERSON_ID, STORE_ID,
                first.customerName(), first.customerPhone(), first.visitResult(), first.location(), true,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION);
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(wrongSubmissionRetry)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("销售与选择城市不一致"));
    }

    @Test
    void headquartersSalespersonUsesActualWorkCityWithoutBypassingLocationOrStoreCity() throws Exception {
        LocationCommand headquartersVisitLocation = location();
        CreateSubmissionRequest headquartersVisit = new CreateSubmissionRequest(
                UUID.randomUUID(), SUBMISSION_KEY, "北京", HEADQUARTERS_SALESPERSON_ID, STORE_ID,
                "总部客户", "13900000000", "总部人员北京现场拜访", headquartersVisitLocation, true,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION,
                locationVerificationToken(HEADQUARTERS_SALESPERSON_ID, "北京", headquartersVisitLocation));
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(headquartersVisit)))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        var storedSubmission = jdbc.queryForMap("""
                SELECT city, salesperson_id FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(submissionId));
        assertThat(storedSubmission.get("city")).isEqualTo("北京");
        assertThat(storedSubmission.get("salesperson_id")).isEqualTo(bin(HEADQUARTERS_SALESPERSON_ID));

        LocationCommand headquartersLocation = location();
        CreateStoreRequest headquartersStore = new CreateStoreRequest(
                UUID.randomUUID(), "北京", HEADQUARTERS_SALESPERSON_ID,
                "B0FFTESTPOI", "高德候选门店", "北京市东城区测试路1号",
                new BigDecimal("116.397128"), new BigDecimal("39.916527"),
                "台球", "总部现场补录门店", "营业中", "赵店长", "13800000000",
                "100-300平米", "10张球桌", List.of("竞技赛事"), List.of("高德业务"),
                "高意向", "A类", List.of("单店"), headquartersLocation,
                selectionToken(HEADQUARTERS_SALESPERSON_ID, "北京", headquartersLocation,
                        defaultCandidate()),
                locationVerificationToken(HEADQUARTERS_SALESPERSON_ID, "北京", headquartersLocation),
                null);
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(headquartersStore)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("北京"));

        CreateSubmissionRequest invalidHeadquartersCity = new CreateSubmissionRequest(
                UUID.randomUUID(), SUBMISSION_KEY, "总部", HEADQUARTERS_SALESPERSON_ID, STORE_ID,
                "总部客户", null, "总部不是实际打卡城市", location(), true,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION);
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(invalidHeadquartersCity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("销售与选择城市不一致"));
    }

    @Test
    void keepsExplicitAmapResultsSeparateWhenRegisteredStoresFillTheLocalLimit()
            throws Exception {
        for (int index = 0; index < 20; index++) {
            insertImportedStore(UUID.randomUUID(), "附近门店" + index, location());
        }
        when(amapPoiClient.searchAround("门店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 25))
                .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(
                        new AmapPoiClient.NearbyPoi(
                                "B0FFCAPACITY", "附近新门店", "附近地址", "休闲服务", "080000",
                                new BigDecimal("116.403000"), new BigDecimal("39.912000"),
                                BigDecimal.ZERO, "北京市", "110101")), 1, 25, 1));

        MvcResult result = mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                resolveRequest("北京", VISITOR_ID, location(), "门店"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores", hasSize(20)))
                .andReturn();
        var items = objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("nearbyStores");
        long poiCount = java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .filter(item -> "AMAP_POI".equals(item.path("source").asText())).count();
        assertThat(poiCount).isZero();
        mockMvc.perform(post("/sales-checkin/api/v1/locations/search-new-store")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(searchNewStoreRequest(
                                "北京", VISITOR_ID, location(), "门店"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores", hasSize(1)))
                .andExpect(jsonPath("$.nearbyStores[0].poiId").value("B0FFCAPACITY"));
    }

    @Test
    void concurrentlyReusesAmapPoiAfterUniqueKeyConflictUnderRepeatableRead() throws Exception {
        CreateStoreRequest first = poiStore(UUID.randomUUID(), "并发门店甲", "张店长");
        CreateStoreRequest second = poiStore(UUID.randomUUID(), "并发门店乙", "李店长");

        CompletableFuture<MvcResult> firstResult = CompletableFuture.supplyAsync(() -> createStore(first));
        CompletableFuture<MvcResult> secondResult = CompletableFuture.supplyAsync(() -> createStore(second));
        MvcResult responseA = firstResult.get(20, TimeUnit.SECONDS);
        MvcResult responseB = secondResult.get(20, TimeUnit.SECONDS);

        assertThat(responseA.getResponse().getStatus()).isEqualTo(200);
        assertThat(responseB.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(responseA.getResponse().getContentAsByteArray()).path("id").asText())
                .isEqualTo(objectMapper.readTree(responseB.getResponse().getContentAsByteArray()).path("id").asText());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND source_poi_id='B0FFTESTPOI'
                """, Integer.class, bin(TENANT_ID))).isEqualTo(1);
        verifyNoInteractions(reverseGeocoder, amapPoiClient);
        verify(coordinateConverter, times(2)).convert(
                any(BigDecimal.class), any(BigDecimal.class));
    }

    @Test
    void rejectsMissingConsentLocationAndValuesOutsideCurrentDropdowns() throws Exception {
        mockMvc.perform(get("/sales-checkin/api/v1/stores")
                        .param("city", "北京")
                        .param("q", "已导入")
                        .param("limit", "21"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_BAD_REQUEST"));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "拜访结果", false, location()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_BAD_REQUEST"));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "拜访结果", true, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("定位经纬度、精度和采集时间不能为空"));

        CreateStoreRequest invalidHistoricalTag = new CreateStoreRequest(
                UUID.randomUUID(), "北京", VISITOR_ID, null, null, null, null, null,
                "台球", "新建门店", "营业中",
                "张店长", "13800000000", "100-300平米", "10张球桌",
                List.of("竞技赛事"), List.of("高德业务"), "高意向", "A类",
                List.of("飞书旧标签"), location());
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(invalidHistoricalTag)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_BAD_REQUEST"));

        CreateStoreRequest missingLocation = new CreateStoreRequest(
                UUID.randomUUID(), "北京", VISITOR_ID, null, null, null, null, null,
                "台球", "无定位新门店", "营业中",
                "张店长", null, "100-300平米", "8张球桌", List.of("竞技赛事"),
                List.of("高德业务"), "中意向", null, List.of("单店"), null);
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(missingLocation)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void optionalUploadDoesNotHoldDraftLockAndLateArrivalCannotAttachAfterCompletion() throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "选填上传不阻塞提交", true, location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        upload(submissionId, "storefront-photo", new MockMultipartFile(
                "file", "door.jpg", "image/jpeg",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}))
                .andExpect(status().isOk());
        clearInvocations(fileStorage);

        byte[] wav = new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'A', 'V', 'E'};
        CountDownLatch uploadEnteredStorage = new CountDownLatch(1);
        CountDownLatch allowUploadToFinish = new CountDownLatch(1);
        AtomicReference<FileMetadata> uploadedMetadata = new AtomicReference<>();
        when(fileStorage.put(any(FileMetadata.class), any(InputStream.class)))
                .thenAnswer(invocation -> {
                    FileMetadata metadata = invocation.getArgument(0);
                    uploadedMetadata.set(metadata);
                    uploadEnteredStorage.countDown();
                    if (!allowUploadToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发提交未在超时前完成");
                    }
                    return metadata;
                });

        CompletableFuture<MvcResult> uploadFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return upload(submissionId, "audio",
                        new MockMultipartFile("file", "visit.wav", "audio/wav", wav)).andReturn();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        if (!uploadEnteredStorage.await(5, TimeUnit.SECONDS)) {
            allowUploadToFinish.countDown();
            throw new AssertionError("选填录音PUT未进入阻塞的COS写入阶段");
        }
        CompletableFuture<MvcResult> completeFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(post(
                                "/sales-checkin/api/v1/submissions/{id}/complete", submissionId)
                                .header("X-Submission-Key", SUBMISSION_KEY))
                        .andReturn();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        try {
            assertThat(completeFuture.get(2, TimeUnit.SECONDS).getResponse().getStatus())
                    .as("阻塞的选填COS PUT不得占用草稿行锁或拖住打卡提交")
                    .isEqualTo(200);
        } finally {
            allowUploadToFinish.countDown();
        }

        assertThat(uploadFuture.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        assertThat(uploadedMetadata.get()).isNotNull();
        verify(fileStorage).delete(TENANT_ID.toString(), uploadedMetadata.get().objectKey());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=? AND status='SUBMITTED' AND audio_object_key IS NULL
                   AND audio_content_type IS NULL AND audio_size_bytes IS NULL
                   AND audio_sha256 IS NULL AND audio_original_filename IS NULL
                """, Integer.class, bin(TENANT_ID), bin(submissionId))).isEqualTo(1);
    }

    @Test
    void deletingAbsentAudioAdvancesRevisionSoInFlightUploadCannotReviveIt() throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "并发上传删除", true, location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        UUID segmentId = UUID.randomUUID();
        byte[] wav = new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'A', 'V', 'E'};
        CountDownLatch uploadEnteredStorage = new CountDownLatch(1);
        CountDownLatch allowUploadToFinish = new CountDownLatch(1);
        AtomicReference<FileMetadata> uploadedMetadata = new AtomicReference<>();
        when(fileStorage.put(any(FileMetadata.class), any(InputStream.class)))
                .thenAnswer(invocation -> {
                    FileMetadata metadata = invocation.getArgument(0);
                    uploadedMetadata.set(metadata);
                    uploadEnteredStorage.countDown();
                    if (!allowUploadToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发DELETE未在超时前到达");
                    }
                    return metadata;
                });

        CompletableFuture<MvcResult> uploadFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return uploadAudioSegment(submissionId, segmentId,
                        new MockMultipartFile("file", "visit.wav", "audio/wav", wav)).andReturn();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        if (!uploadEnteredStorage.await(5, TimeUnit.SECONDS)) {
            allowUploadToFinish.countDown();
            throw new AssertionError("选填录音PUT未进入阻塞的COS写入阶段");
        }
        try {
            mockMvc.perform(delete(
                            "/sales-checkin/api/v1/submissions/{id}/media/audio/{segmentId}",
                            submissionId, segmentId)
                            .header("X-Submission-Key", SUBMISSION_KEY))
                    .andExpect(status().isOk());
        } finally {
            allowUploadToFinish.countDown();
        }

        assertThat(uploadFuture.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        assertThat(uploadedMetadata.get()).isNotNull();
        verify(fileStorage).delete(TENANT_ID.toString(), uploadedMetadata.get().objectKey());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=? AND status='DRAFT'
                   AND audio_object_key IS NULL AND audio_active_segment_count=0
                   AND JSON_LENGTH(audio_segments_json)=0
                """, Integer.class, bin(TENANT_ID), bin(submissionId))).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalAudioUploadsRegisterOneObjectAndCleanTheOther() throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "并发录音幂等", true, location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        UUID segmentId = UUID.randomUUID();
        byte[] wav = new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'A', 'V', 'E'};
        CountDownLatch bothUploadsEnteredStorage = new CountDownLatch(2);
        CountDownLatch allowUploadsToFinish = new CountDownLatch(1);
        List<FileMetadata> staged = new CopyOnWriteArrayList<>();
        when(fileStorage.put(any(FileMetadata.class), any(InputStream.class)))
                .thenAnswer(invocation -> {
                    FileMetadata metadata = invocation.getArgument(0);
                    staged.add(metadata);
                    bothUploadsEnteredStorage.countDown();
                    if (!allowUploadsToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("两个并发PUT未在超时前到达");
                    }
                    return metadata;
                });

        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> {
            try {
                return uploadAudioSegment(submissionId, segmentId,
                        new MockMultipartFile("file", "visit.wav", "audio/wav", wav)).andReturn();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        CompletableFuture<MvcResult> second = CompletableFuture.supplyAsync(() -> {
            try {
                return uploadAudioSegment(submissionId, segmentId,
                        new MockMultipartFile("file", "visit.wav", "audio/wav", wav)).andReturn();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        if (!bothUploadsEnteredStorage.await(5, TimeUnit.SECONDS)) {
            allowUploadsToFinish.countDown();
            throw new AssertionError("两个录音请求未同时进入锁外COS写入");
        }
        allowUploadsToFinish.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        assertThat(second.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        assertThat(staged).hasSize(2);
        var stored = jdbc.queryForMap("""
                SELECT audio_object_key, audio_active_segment_count, audio_segments_json
                  FROM temp_sales_checkin_submission WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(submissionId));
        String referencedKey = String.valueOf(stored.get("audio_object_key"));
        assertThat(((Number) stored.get("audio_active_segment_count")).intValue()).isEqualTo(1);
        assertThat(objectMapper.readTree(String.valueOf(stored.get("audio_segments_json")))).hasSize(1);
        assertThat(staged.stream().map(FileMetadata::objectKey)).contains(referencedKey);
        ArgumentCaptor<String> deletedKey = ArgumentCaptor.forClass(String.class);
        verify(fileStorage, times(1)).delete(any(String.class), deletedKey.capture());
        assertThat(deletedKey.getValue()).isNotEqualTo(referencedKey);
    }

    @Test
    void physicallyDeletesDraftMediaClearsReferencesIdempotentlyAndPreventsHiddenCompletion()
            throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "删除草稿媒体", true, location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
        byte[] wav = new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'A', 'V', 'E'};
        upload(submissionId, "storefront-photo",
                new MockMultipartFile("file", "door.jpg", "image/jpeg", jpeg))
                .andExpect(status().isOk());
        upload(submissionId, "audio",
                new MockMultipartFile("file", "visit.wav", "audio/wav", wav))
                .andExpect(status().isOk());
        var keys = jdbc.queryForMap("""
                SELECT storefront_photo_object_key, audio_object_key
                  FROM temp_sales_checkin_submission WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(submissionId));
        String photoKey = (String) keys.get("storefront_photo_object_key");
        String audioKey = (String) keys.get("audio_object_key");

        mockMvc.perform(delete("/sales-checkin/api/v1/submissions/{id}/media/audio", submissionId)
                        .header("X-Submission-Key", OTHER_SUBMISSION_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_KEY_INVALID"));
        verify(fileStorage, never()).delete(any(String.class), any(String.class));

        mockMvc.perform(delete("/sales-checkin/api/v1/submissions/{id}/media/audio", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("audio"))
                .andExpect(jsonPath("$.status").value("DELETED"));
        mockMvc.perform(delete(
                        "/sales-checkin/api/v1/submissions/{id}/media/storefront-photo", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("storefront-photo"))
                .andExpect(jsonPath("$.status").value("DELETED"));
        verify(fileStorage).delete(TENANT_ID.toString(), audioKey);
        verify(fileStorage).delete(TENANT_ID.toString(), photoKey);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=?
                   AND storefront_photo_object_key IS NULL
                   AND storefront_photo_content_type IS NULL
                   AND storefront_photo_size_bytes IS NULL
                   AND storefront_photo_sha256 IS NULL
                   AND storefront_photo_original_filename IS NULL
                   AND audio_object_key IS NULL AND audio_content_type IS NULL
                   AND audio_size_bytes IS NULL AND audio_sha256 IS NULL
                   AND audio_original_filename IS NULL
                """, Integer.class, bin(TENANT_ID), bin(submissionId))).isEqualTo(1);

        mockMvc.perform(delete("/sales-checkin/api/v1/submissions/{id}/media/audio", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));
        mockMvc.perform(delete(
                        "/sales-checkin/api/v1/submissions/{id}/media/storefront-photo", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));
        verify(fileStorage, times(1)).delete(TENANT_ID.toString(), audioKey);
        verify(fileStorage, times(1)).delete(TENANT_ID.toString(), photoKey);

        mockMvc.perform(post("/sales-checkin/api/v1/submissions/{id}/complete", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请先上传门头照"));

        upload(submissionId, "storefront-photo",
                new MockMultipartFile("file", "door.jpg", "image/jpeg", jpeg))
                .andExpect(status().isOk());
        mockMvc.perform(post("/sales-checkin/api/v1/submissions/{id}/complete", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(delete(
                        "/sales-checkin/api/v1/submissions/{id}/media/storefront-photo", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("已提交的打卡不允许删除媒体"));
        verify(fileStorage, times(1)).delete(TENANT_ID.toString(), photoKey);
    }

    @Test
    void clearsAdminDeletionMarkersWhenSalespersonReuploadsDeletedDraftMedia() throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "管理员删除后重传", true, location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
        MockMultipartFile photo = new MockMultipartFile("file", "door.jpg", "image/jpeg", jpeg);
        upload(submissionId, "storefront-photo", photo).andExpect(status().isOk());

        mockMvc.perform(delete("/sales-checkin/admin/api/v1/submissions/{id}/media/storefront-photo",
                        submissionId)
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"现场照片模糊\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/sales-checkin/api/v1/submissions/{id}/complete", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请先上传门头照"));

        upload(submissionId, "storefront-photo",
                new MockMultipartFile("file", "door.jpg", "image/jpeg", jpeg))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=?
                   AND storefront_photo_object_key IS NOT NULL
                   AND storefront_photo_deleted_at IS NULL
                   AND storefront_photo_deleted_by IS NULL
                   AND storefront_photo_deletion_reason IS NULL
                """, Integer.class, bin(TENANT_ID), bin(submissionId))).isEqualTo(1);
        verify(fileStorage, times(2)).put(any(FileMetadata.class), any(InputStream.class));
        mockMvc.perform(post("/sales-checkin/api/v1/submissions/{id}/complete", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void makesOnlyStorefrontPhotoRequiredAndKeepsUploadAndCompleteIdempotent() throws Exception {
        UUID clientSubmissionId = UUID.randomUUID();
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                clientSubmissionId, SUBMISSION_KEY, "门店现场沟通完成", true, location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsByteArray())
                .path("id").asText());

        mockMvc.perform(post("/sales-checkin/api/v1/submissions/{id}/complete", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_BAD_REQUEST"));

        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
        upload(submissionId, "storefront-photo",
                new MockMultipartFile("file", "door.jpg", "image/jpeg", new byte[] {1, 2, 3, 4}))
                .andExpect(status().isBadRequest());

        MockMultipartFile extensionlessBlob = new MockMultipartFile(
                "file", "blob", "application/octet-stream", jpeg);
        upload(submissionId, "storefront-photo", extensionlessBlob)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("storefront-photo"));

        MockMultipartFile samePhoto = new MockMultipartFile(
                "file", "renamed.jpg", "image/jpeg", jpeg);
        upload(submissionId, "storefront-photo", samePhoto)
                .andExpect(status().isOk());

        ArgumentCaptor<FileMetadata> metadata = ArgumentCaptor.forClass(FileMetadata.class);
        verify(fileStorage, times(1)).put(metadata.capture(), any(InputStream.class));
        String objectPrefix = TENANT_ID + "/temporary-sales-checkin/" + submissionId + "/";
        assertThat(metadata.getValue().tenantId()).isEqualTo(TENANT_ID.toString());
        assertThat(metadata.getValue().originalName()).isEqualTo("blob.jpg");
        assertThat(metadata.getValue().objectKey())
                .matches(objectPrefix + "photos/storefront/[0-9a-f]{64}\\.jpg");

        MvcResult completed = mockMvc.perform(post("/sales-checkin/api/v1/submissions/{id}/complete", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn();
        String submittedAt = objectMapper.readTree(completed.getResponse().getContentAsByteArray())
                .path("submittedAt").asText();

        mockMvc.perform(post("/sales-checkin/api/v1/submissions/{id}/complete", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submittedAt").value(submittedAt));

        MockMultipartFile lateAudio = new MockMultipartFile(
                "file", "late.m4a", "audio/mp4", new byte[] {0, 0, 0, 12, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '});
        upload(submissionId, "audio", lateAudio)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_CONFLICT"));
        verify(fileStorage, times(1)).put(any(FileMetadata.class), any(InputStream.class));
        verify(fileStorage, never()).delete(any(String.class), any(String.class));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=? AND status='SUBMITTED'
                   AND storefront_photo_object_key IS NOT NULL
                   AND wechat_screenshot_object_key IS NULL AND audio_object_key IS NULL
                """, Integer.class, bin(TENANT_ID), bin(submissionId))).isEqualTo(1);
    }

    @Test
    void acceptsValidAudioWhenMobilePickerMetadataIsMissingOrUnreliable() throws Exception {
        List<MockMultipartFile> files = List.of(
                new MockMultipartFile("file", "voice.aac", "audio/aacp",
                        new byte[] {(byte) 0xff, (byte) 0xf1, 0, 0, 0, 0, 0}),
                new MockMultipartFile("file", "voice.mp3", "audio/mp3",
                        new byte[] {'I', 'D', '3', 0, 0, 0, 0, 0, 0, 0}),
                new MockMultipartFile("file", "voice.ogg", "application/ogg",
                        new byte[] {'O', 'g', 'g', 'S', 'O', 'p', 'u', 's', 'H', 'e', 'a', 'd'}),
                new MockMultipartFile("file", "content", "application/x-qq-file",
                        realM4a()),
                new MockMultipartFile("file", "voice.tmp", "video/mp4",
                        realM4a()),
                new MockMultipartFile("file", "voice", null, realM4a()),
                new MockMultipartFile("file", "voice.bin", "application/octet-stream",
                        new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3,
                                'A', '_', 'O', 'P', 'U', 'S'}),
                new MockMultipartFile("file", "voice.bin", "application/octet-stream",
                        new byte[] {'#', '!', 'A', 'M', 'R', '-', 'W', 'B', '\n'}),
                new MockMultipartFile("file", "voice.adif", "application/octet-stream",
                        new byte[] {'A', 'D', 'I', 'F', 0, 0, 0, 0}),
                new MockMultipartFile("file", "voice.latm", "application/octet-stream",
                        new byte[] {0x56, (byte) 0xe0, 0x01, 0, 0, 0}),
                new MockMultipartFile("file", "voice.rf64", "application/octet-stream",
                        new byte[] {'R', 'F', '6', '4', 4, 0, 0, 0, 'W', 'A', 'V', 'E'}),
                new MockMultipartFile("file", "voice.flac", "application/octet-stream",
                        new byte[] {'f', 'L', 'a', 'C', 0, 0, 0, 0}),
                new MockMultipartFile("file", "voice.caf", "application/octet-stream",
                        new byte[] {'c', 'a', 'f', 'f', 0, 1, 0, 0}),
                new MockMultipartFile("file", "voice.aifc", "application/octet-stream",
                        new byte[] {'F', 'O', 'R', 'M', 0, 0, 0, 4, 'A', 'I', 'F', 'C'}),
                new MockMultipartFile("file", "voice.silk", "application/octet-stream",
                        new byte[] {0x02, '#', '!', 'S', 'I', 'L', 'K', '_', 'V', '3'}),
                new MockMultipartFile("file", "voice.3gp", "video/3gpp",
                        new byte[] {0, 0, 0, 12, 'f', 't', 'y', 'p', '3', 'g', 'p', '6',
                                0, 0, 0, 20, 'h', 'd', 'l', 'r', 0, 0, 0, 0,
                                0, 0, 0, 0, 's', 'o', 'u', 'n'}),
                new MockMultipartFile("file", "voice.mp4", "video/mp4",
                        new byte[] {0, 0, 0, 12, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
                                0, 0, 0, 20, 'h', 'd', 'l', 'r', 0, 0, 0, 0,
                                0, 0, 0, 0, 's', 'o', 'u', 'n'}));

        for (MockMultipartFile file : files) {
            MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(submission(
                                    UUID.randomUUID(), SUBMISSION_KEY, "移动端音频别名", true, location()))))
                    .andExpect(status().isOk())
                    .andReturn();
            UUID submissionId = UUID.fromString(objectMapper.readTree(
                    created.getResponse().getContentAsByteArray()).path("id").asText());

            upload(submissionId, "audio", file)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kind").value("audio"));
        }

        ArgumentCaptor<FileMetadata> metadata = ArgumentCaptor.forClass(FileMetadata.class);
        verify(fileStorage, times(files.size())).put(metadata.capture(), any(InputStream.class));
        assertThat(metadata.getAllValues().get(3).contentType()).isEqualTo("audio/mp4");
        assertThat(metadata.getAllValues().get(3).originalName()).isEqualTo("content.m4a");
        assertThat(metadata.getAllValues().get(4).originalName()).isEqualTo("voice.m4a");
        assertThat(metadata.getAllValues().get(5).originalName()).isEqualTo("voice.m4a");
        assertThat(metadata.getAllValues().get(3).objectKey()).endsWith(".m4a");
        assertThat(metadata.getAllValues().get(15).contentType()).isEqualTo("audio/3gpp");
        assertThat(metadata.getAllValues().get(15).objectKey()).endsWith(".3gp");
        assertThat(metadata.getAllValues().get(16).contentType()).isEqualTo("audio/mp4");
    }

    @Test
    void uploadsMultipleAudioSegmentsIdempotentlyAndDeletesOnlyTheSelectedSegment() throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "多段录音", true, location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        UUID firstSegmentId = UUID.randomUUID();
        UUID secondSegmentId = UUID.randomUUID();
        byte[] first = new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'A', 'V', 'E', 1};
        byte[] second = new byte[] {'R', 'I', 'F', 'F', 5, 0, 0, 0, 'W', 'A', 'V', 'E', 2};

        uploadAudioSegment(submissionId, firstSegmentId,
                new MockMultipartFile("file", "第一段.wav", "audio/wav", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segmentId").value(firstSegmentId.toString()))
                .andExpect(jsonPath("$.originalFilename").value("第一段.wav"));
        uploadAudioSegment(submissionId, firstSegmentId,
                new MockMultipartFile("file", "第一段重试.wav", "audio/wav", first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segmentId").value(firstSegmentId.toString()));
        uploadAudioSegment(submissionId, UUID.randomUUID(),
                new MockMultipartFile("file", "重复内容.wav", "audio/wav", first))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("相同录音已经添加，请勿重复选择"));
        uploadAudioSegment(submissionId, firstSegmentId,
                new MockMultipartFile("file", "编号冲突.wav", "audio/wav", second))
                .andExpect(status().isConflict());
        uploadAudioSegment(submissionId, secondSegmentId,
                new MockMultipartFile("file", "第二段.wav", "audio/wav", second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segmentId").value(secondSegmentId.toString()));

        var stored = jdbc.queryForMap("""
                SELECT audio_segments_json, audio_active_segment_count, audio_active_size_bytes,
                       audio_object_key
                  FROM temp_sales_checkin_submission WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(submissionId));
        assertThat(((Number) stored.get("audio_active_segment_count")).intValue()).isEqualTo(2);
        assertThat(((Number) stored.get("audio_active_size_bytes")).longValue())
                .isEqualTo(first.length + second.length);
        assertThat(objectMapper.readTree(String.valueOf(stored.get("audio_segments_json")))).hasSize(2);
        String firstObjectKey = String.valueOf(stored.get("audio_object_key"));
        assertThat(firstObjectKey).contains("/recordings/visit/segments/" + firstSegmentId + "/");
        verify(fileStorage, times(2)).put(any(FileMetadata.class), any(InputStream.class));

        mockMvc.perform(delete(
                        "/sales-checkin/api/v1/submissions/{id}/media/audio/{segmentId}",
                        submissionId, firstSegmentId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segmentId").value(firstSegmentId.toString()));
        verify(fileStorage).delete(TENANT_ID.toString(), firstObjectKey);
        var remaining = jdbc.queryForMap("""
                SELECT audio_segments_json, audio_active_segment_count, audio_object_key
                  FROM temp_sales_checkin_submission WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(submissionId));
        assertThat(((Number) remaining.get("audio_active_segment_count")).intValue()).isEqualTo(1);
        assertThat(objectMapper.readTree(String.valueOf(remaining.get("audio_segments_json")))).hasSize(1);
        assertThat(String.valueOf(remaining.get("audio_object_key")))
                .contains("/recordings/visit/segments/" + secondSegmentId + "/");
    }

    @Test
    void recordsAudioCaptureEvidenceWithClockSkewToleranceAndLegacyJsonDefaults() throws Exception {
        LocationCommand capturedLocation = location();
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "录音时间证据", true, capturedLocation))))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
        UUID alignedId = UUID.randomUUID();
        UUID mismatchId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID unknownId = UUID.randomUUID();
        Instant alignedStartedAt = capturedLocation.capturedAt().minusSeconds(60);
        Instant mismatchStartedAt = capturedLocation.capturedAt().minusSeconds(180);
        Instant fileLastModifiedAt = capturedLocation.capturedAt().minusSeconds(86_400);

        mockMvc.perform(multipart(
                        "/sales-checkin/api/v1/submissions/{id}/media/audio/{segmentId}",
                        submissionId, alignedId)
                        .file(new MockMultipartFile("file", "页面录制.wav", "audio/wav",
                                new byte[] {'R', 'I', 'F', 'F', 1, 0, 0, 0, 'W', 'A', 'V', 'E', 1}))
                        .param("captureSource", "BROWSER_RECORDER")
                        .param("clientStartedAt", alignedStartedAt.toString())
                        .param("clientDurationMs", "10000")
                        .header("X-Submission-Key", SUBMISSION_KEY)
                        .with(request -> { request.setMethod("PUT"); return request; }))
                .andExpect(status().isOk());

        mockMvc.perform(multipart(
                        "/sales-checkin/api/v1/submissions/{id}/media/audio/{segmentId}",
                        submissionId, mismatchId)
                        .file(new MockMultipartFile("file", "早于定位.wav", "audio/wav",
                                new byte[] {'R', 'I', 'F', 'F', 2, 0, 0, 0, 'W', 'A', 'V', 'E', 2}))
                        .param("captureSource", "BROWSER_RECORDER")
                        .param("clientStartedAt", mismatchStartedAt.toString())
                        .param("clientDurationMs", "10000")
                        .header("X-Submission-Key", SUBMISSION_KEY)
                        .with(request -> { request.setMethod("PUT"); return request; }))
                .andExpect(status().isOk());

        mockMvc.perform(multipart(
                        "/sales-checkin/api/v1/submissions/{id}/media/audio/{segmentId}",
                        submissionId, fileId)
                        .file(new MockMultipartFile("file", "已有文件.wav", "audio/wav",
                                new byte[] {'R', 'I', 'F', 'F', 3, 0, 0, 0, 'W', 'A', 'V', 'E', 3}))
                        .param("captureSource", "FILE_UPLOAD")
                        .param("clientDurationMs", "65000")
                        .param("fileLastModifiedAt", fileLastModifiedAt.toString())
                        .header("X-Submission-Key", SUBMISSION_KEY)
                        .with(request -> { request.setMethod("PUT"); return request; }))
                .andExpect(status().isOk());

        mockMvc.perform(multipart(
                        "/sales-checkin/api/v1/submissions/{id}/media/audio/{segmentId}",
                        submissionId, unknownId)
                        .file(new MockMultipartFile("file", "旧客户端.wav", "audio/wav",
                                new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'A', 'V', 'E', 4}))
                        .param("captureSource", "RECORDED")
                        .param("clientStartedAt", "not-an-instant")
                        .param("clientDurationMs", "not-a-number")
                        .header("X-Submission-Key", SUBMISSION_KEY)
                        .with(request -> { request.setMethod("PUT"); return request; }))
                .andExpect(status().isOk());

        String storedJson = jdbc.queryForObject("""
                SELECT audio_segments_json FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=?
                """, String.class, bin(TENANT_ID), bin(submissionId));
        var manifest = objectMapper.readTree(storedJson);
        assertThat(manifest).hasSize(4);
        assertThat(manifest.get(0).path("captureSource").asText()).isEqualTo("BROWSER_RECORDER");
        assertThat(manifest.get(0).path("clientStartedAt").asText()).isEqualTo(alignedStartedAt.toString());
        assertThat(manifest.get(0).path("clientDurationMs").asLong()).isEqualTo(10_000L);
        assertThat(manifest.get(0).path("timingStatus").asText()).isEqualTo("ALIGNED");
        assertThat(manifest.get(1).path("timingStatus").asText()).isEqualTo("MISMATCH");
        assertThat(manifest.get(2).path("captureSource").asText()).isEqualTo("FILE_UPLOAD");
        assertThat(manifest.get(2).path("fileLastModifiedAt").asText())
                .isEqualTo(fileLastModifiedAt.toString());
        assertThat(manifest.get(2).path("timingStatus").asText()).isEqualTo("UNVERIFIED_FILE");
        assertThat(manifest.get(3).path("captureSource").asText()).isEqualTo("UNKNOWN");
        assertThat(manifest.get(3).path("timingStatus").asText()).isEqualTo("MISSING");

        jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET audio_segments_json=JSON_REMOVE(audio_segments_json,
                       '$[3].captureSource', '$[3].clientStartedAt', '$[3].clientDurationMs',
                       '$[3].fileLastModifiedAt', '$[3].timingStatus')
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(submissionId));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].audioSegments", hasSize(4)))
                .andExpect(jsonPath("$.items[0].audioSegments[0].captureSource")
                        .value("BROWSER_RECORDER"))
                .andExpect(jsonPath("$.items[0].audioSegments[0].timingStatus").value("ALIGNED"))
                .andExpect(jsonPath("$.items[0].audioSegments[1].timingStatus").value("MISMATCH"))
                .andExpect(jsonPath("$.items[0].audioSegments[2].captureSource").value("FILE_UPLOAD"))
                .andExpect(jsonPath("$.items[0].audioSegments[2].timingStatus")
                        .value("UNVERIFIED_FILE"))
                .andExpect(jsonPath("$.items[0].audioSegments[3].captureSource").value("UNKNOWN"))
                .andExpect(jsonPath("$.items[0].audioSegments[3].timingStatus").value("MISSING"));
    }

    @Test
    void acceptsAudioAboveTheFormerTwentyFiveMegabyteCeiling() throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "大文件录音", true, location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());

        byte[] audio = new byte[26 * 1024 * 1024];
        byte[] header = new byte[] {
                0, 0, 0, 12, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ',
                0, 0, 0, 20, 'h', 'd', 'l', 'r',
                0, 0, 0, 0, 0, 0, 0, 0, 's', 'o', 'u', 'n'
        };
        System.arraycopy(header, 0, audio, 0, header.length);

        upload(submissionId, "audio", new MockMultipartFile(
                "file", "long-recording.m4a", "application/x-qq-file", audio))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sizeBytes").value(audio.length));
    }

    @Test
    void rejectsNonAudioAndRealVideoContentEvenWhenMetadataClaimsAudio() throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "错误音频内容", true, location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());

        byte[] selectedPhoto = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
        upload(submissionId, "storefront-photo", new MockMultipartFile(
                "file", "store.jpg", "image/jpeg", selectedPhoto))
                .andExpect(status().isOk());

        upload(submissionId, "audio", new MockMultipartFile(
                "file", "voice.m4a", "audio/mp4",
                selectedPhoto))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("所选文件是图片，不是录音；录音为选填，可删除后继续提交"));

        upload(submissionId, "audio", new MockMultipartFile(
                "file", "voice.m4a", "audio/mp4", realVideoMp4()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("录音格式不支持或文件内容损坏"));

        upload(submissionId, "audio", new MockMultipartFile(
                "file", "voice.m4a", "audio/mp4", new byte[0]))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/sales-checkin/api/v1/submissions/{id}/complete", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void separatesMediaByBusinessDirectoryAndUsesCanonicalHashFilenames() throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "媒体目录验收", true, location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsByteArray())
                .path("id").asText());

        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        byte[] wav = new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'A', 'V', 'E'};
        upload(submissionId, "storefront-photo",
                new MockMultipartFile("file", "../../store.html", "text/html", jpeg))
                .andExpect(status().isOk());
        upload(submissionId, "wechat-screenshot",
                new MockMultipartFile("file", "../../customer.png", "image/png", png))
                .andExpect(status().isOk());
        upload(submissionId, "audio",
                new MockMultipartFile("file", "../../visit.wav", "audio/wav", wav))
                .andExpect(status().isOk());

        ArgumentCaptor<FileMetadata> metadata = ArgumentCaptor.forClass(FileMetadata.class);
        verify(fileStorage, times(3)).put(metadata.capture(), any(InputStream.class));
        List<FileMetadata> storedMedia = metadata.getAllValues();
        String objectPrefix = TENANT_ID + "/temporary-sales-checkin/" + submissionId + "/";
        assertThat(storedMedia.get(0).originalName()).isEqualTo("store.jpg");
        assertThat(storedMedia.get(0).objectKey())
                .matches(objectPrefix + "photos/storefront/[0-9a-f]{64}\\.jpg");
        assertThat(storedMedia.get(1).originalName()).isEqualTo("customer.png");
        assertThat(storedMedia.get(1).objectKey())
                .matches(objectPrefix + "screenshots/wechat/[0-9a-f]{64}-[0-9a-f-]{36}\\.png");
        assertThat(storedMedia.get(2).originalName()).isEqualTo("visit.wav");
        assertThat(storedMedia.get(2).objectKey())
                .matches(objectPrefix + "recordings/visit/segments/" + submissionId
                        + "/[0-9a-f]{64}-[0-9a-f-]{36}\\.wav");
        verify(fileStorage, never()).delete(any(String.class), any(String.class));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions/{id}/complete", submissionId)
                        .header("X-Submission-Key", SUBMISSION_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        assertThat(jdbc.queryForObject("""
                SELECT transcription_status FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=?
                """, String.class, bin(TENANT_ID), bin(submissionId))).isEqualTo("PENDING");
    }

    @Test
    void protectsCsvFromFormulaPrefixesAndKeepsUtf8Bom() throws Exception {
        insertCsvFormulaSubmission();
        jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET submitted_ip_masked='+masked', user_agent_summary='@browser',
                       risk_flags_json=JSON_ARRAY('=RISK_FORMULA')
                 WHERE tenant_id=? AND salesperson_name_snapshot='=formula'
                """, bin(TENANT_ID));

        MvcResult exported = mockMvc.perform(get("/sales-checkin/admin/export.csv")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN)))
                .andExpect(status().isOk())
                .andReturn();
        String csv = new String(exported.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("\"'=formula\"", "\"'+formula\"", "\"'-formula\"", "\"'@formula\"",
                "\"'\tformula\"", "\"'\rformula\"", "\"'\nformula\"",
                "\"'+masked\"", "\"'@browser\"", "\"'=RISK_FORMULA\"");
    }

    @Test
    void scopesAdminOptionsListsExportsAndMediaToAuthenticatedPrincipal() throws Exception {
        UUID beijingSubmission = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "北京门店", "北京客户", "北京跟进");
        jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET risk_level='LOW', risk_flags_json=JSON_OBJECT('unexpected', true)
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(beijingSubmission));
        UUID shenzhenSubmission = insertAdminSubmission(
                "深圳", SHENZHEN_SALESPERSON_ID, SHENZHEN_STORE_ID, "深圳门店", "深圳客户", "深圳跟进");
        jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET identity_method='PERSONAL_CODE', submitted_ip_masked='10.2.*.*',
                       user_agent_summary='Mobile Safari / iOS', risk_level='HIGH',
                       risk_flags_json=JSON_ARRAY('DEVICE_MULTIPLE_SALES', 'IP_SHARED')
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(shenzhenSubmission));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/options"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_ADMIN_UNAUTHORIZED"));
        mockMvc.perform(get("/sales-checkin/admin/api/v1/options")
                        .with(admin("unknown")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/sales-checkin/admin/api/v1/options")
                        .with(admin("city-beijing")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.username").value("city-beijing"))
                .andExpect(jsonPath("$.scope.allCities").value(false))
                .andExpect(jsonPath("$.scope.city").value("北京"))
                .andExpect(jsonPath("$.cities", hasSize(1)))
                .andExpect(jsonPath("$.cities[0]").value("北京"))
                .andExpect(jsonPath("$.salespersons", hasSize(2)));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin("city-beijing")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(beijingSubmission.toString()))
                .andExpect(jsonPath("$.items[0].city").value("北京"))
                .andExpect(jsonPath("$.items[0].locationAddress").value("北京市东城区测试路1号"))
                .andExpect(jsonPath("$.items[0].riskLevel").value("LOW"))
                .andExpect(jsonPath("$.items[0].riskFlags", hasSize(0)));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("q", "深圳客户")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.allCities").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(shenzhenSubmission.toString()))
                .andExpect(jsonPath("$.items[0].identityMethod").value("PERSONAL_CODE"))
                .andExpect(jsonPath("$.items[0].submittedIpMasked").value("10.2.*.*"))
                .andExpect(jsonPath("$.items[0].userAgentSummary").value("Mobile Safari / iOS"))
                .andExpect(jsonPath("$.items[0].riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.items[0].riskFlags", containsInAnyOrder(
                        "DEVICE_MULTIPLE_SALES", "IP_SHARED")));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin("city-beijing"))
                        .param("city", "深圳"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_ADMIN_FORBIDDEN"));

        String beijingCsv = new String(mockMvc.perform(get("/sales-checkin/admin/export.csv")
                        .with(admin("city-beijing")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(beijingCsv).contains("北京客户", "location_address").doesNotContain("深圳客户");

        String shenzhenCsv = new String(mockMvc.perform(get("/sales-checkin/admin/export.csv")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("city", "深圳"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(shenzhenCsv)
                .contains("深圳客户", "identity_method", "submitted_ip_masked", "user_agent_summary",
                        "risk_level", "risk_flags", "PERSONAL_CODE", "10.2.*.*", "Mobile Safari / iOS",
                        "HIGH", "DEVICE_MULTIPLE_SALES|IP_SHARED")
                .doesNotContain("北京客户");

        when(fileStorage.open(TENANT_ID.toString(), "tenant/shenzhen.jpg"))
                .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
        mockMvc.perform(get("/sales-checkin/admin/submissions/{id}/media/storefront-photo",
                        shenzhenSubmission)
                        .with(admin("city-beijing")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/sales-checkin/admin/submissions/{id}/media/storefront-photo",
                        shenzhenSubmission)
                        .with(admin("city-shenzhen")))
                .andExpect(status().isOk());
    }

    @Test
    void keepsVisitOrdinalFromAllSubmittedHistoryBeforeApplyingAdminDateFilters() throws Exception {
        Instant now = Instant.now();
        UUID historical = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "历史客户", "首次拜访");
        markSubmitted(historical, now.minusSeconds(3 * 86_400), now.minusSeconds(3 * 86_400 - 60));
        UUID today = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "今日客户", "复访跟进");
        markSubmitted(today, now.minusSeconds(120), now.minusSeconds(60));
        String dateFilter = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("from", dateFilter)
                        .param("status", "SUBMITTED")
                        .param("visitType", "REVISIT")
                        .param("q", "今日客户"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.firstVisitTotal").value(0))
                .andExpect(jsonPath("$.revisitTotal").value(1))
                .andExpect(jsonPath("$.items[0].id").value(today.toString()))
                .andExpect(jsonPath("$.items[0].visitOrdinal").value(2))
                .andExpect(jsonPath("$.items[0].visitType").value("REVISIT"))
                .andExpect(jsonPath("$.items[0].revisitNumber").value(1));

        String csv = new String(mockMvc.perform(get("/sales-checkin/admin/export.csv")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("from", dateFilter)
                        .param("status", "SUBMITTED")
                        .param("visitType", "REVISIT"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(csv)
                .contains("\"visit_ordinal\",\"visit_type\",\"revisit_number\"")
                .contains(",\"2\",\"REVISIT\",\"1\",\"今日客户\",")
                .doesNotContain("历史客户");
    }

    @Test
    void usesSubmittedAtForAdminDateOwnershipAndOrderingAcrossDraftDays() throws Exception {
        ZoneId businessZone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(businessZone);
        Instant todayStart = today.atStartOfDay(businessZone).toInstant();
        UUID earlierSubmission = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "跨日时间口径一", "跨日草稿");
        markSubmitted(earlierSubmission, todayStart.minusSeconds(3_600), todayStart.plusSeconds(60));
        UUID laterSubmission = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "跨日时间口径二", "跨日草稿");
        markSubmitted(laterSubmission, todayStart.minusSeconds(7_200), todayStart.plusSeconds(120));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("from", today.toString())
                        .param("to", today.toString())
                        .param("status", "SUBMITTED")
                        .param("q", "跨日时间口径"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].id").value(laterSubmission.toString()))
                .andExpect(jsonPath("$.items[1].id").value(earlierSubmission.toString()));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("from", today.minusDays(1).toString())
                        .param("to", today.minusDays(1).toString())
                        .param("status", "SUBMITTED")
                        .param("q", "跨日时间口径"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        String csv = new String(mockMvc.perform(get("/sales-checkin/admin/export.csv")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("from", today.toString())
                        .param("to", today.toString())
                        .param("status", "SUBMITTED")
                        .param("q", "跨日时间口径"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(csv).contains("跨日时间口径一", "跨日时间口径二");
        assertThat(csv.indexOf("跨日时间口径二")).isLessThan(csv.indexOf("跨日时间口径一"));
    }

    @Test
    void filtersVisitTypesWithExactTotalsStatisticsPaginationAndCsv() throws Exception {
        Instant base = Instant.parse("2026-08-28T02:00:00Z");
        UUID first = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "北京首访", "首次到店");
        markSubmitted(first, base.minusSeconds(500), base.minusSeconds(490));
        UUID firstRevisit = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "北京复访一", "二次到店");
        markSubmitted(firstRevisit, base.minusSeconds(400), base.minusSeconds(390));
        UUID secondRevisit = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "北京复访二", "三次到店");
        markSubmitted(secondRevisit, base.minusSeconds(300), base.minusSeconds(290));
        UUID shenzhenFirst = insertAdminSubmission(
                "深圳", SHENZHEN_SALESPERSON_ID, SHENZHEN_STORE_ID,
                "深圳已导入门店", "深圳首访", "首次到店");
        markSubmitted(shenzhenFirst, base.minusSeconds(200), base.minusSeconds(190));
        insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "尚未提交草稿", "草稿");

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.firstVisitTotal").value(2))
                .andExpect(jsonPath("$.revisitTotal").value(2));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("visitType", "FIRST_VISIT")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.firstVisitTotal").value(2))
                .andExpect(jsonPath("$.revisitTotal").value(0))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].visitOrdinal").value(1))
                .andExpect(jsonPath("$.items[0].visitType").value("FIRST_VISIT"));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("visitType", "FIRST_VISIT")
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].visitOrdinal").value(1));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin("city-beijing"))
                        .param("status", "SUBMITTED")
                        .param("visitType", "REVISIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.firstVisitTotal").value(0))
                .andExpect(jsonPath("$.revisitTotal").value(2))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].visitType").value("REVISIT"))
                .andExpect(jsonPath("$.items[1].visitType").value("REVISIT"));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.firstVisitTotal").value(0))
                .andExpect(jsonPath("$.revisitTotal").value(0));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("status", "DRAFT")
                        .param("visitType", "FIRST_VISIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.firstVisitTotal").value(0))
                .andExpect(jsonPath("$.revisitTotal").value(0));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("visitType", "UNKNOWN"))
                .andExpect(status().isBadRequest());

        String csv = new String(mockMvc.perform(get("/sales-checkin/admin/export.csv")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("visitType", "REVISIT"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(csv)
                .contains("北京复访一", "北京复访二", "REVISIT")
                .doesNotContain("北京首访", "深圳首访", "尚未提交草稿");
    }

    @Test
    void letsOnlyGlobalAdminPhysicallyDeleteMediaIdempotentlyAndUpdatesStorageStats() throws Exception {
        UUID submissionId = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "北京门店", "媒体清理客户", "物理删除验收");

        mockMvc.perform(get("/sales-checkin/admin/api/v1/options")
                .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaStats.activeFiles").value(1))
                .andExpect(jsonPath("$.mediaStats.totalBytes").value(4))
                .andExpect(jsonPath("$.mediaStats.imageBytes").value(4))
                .andExpect(jsonPath("$.mediaStats.audioBytes").value(0));

        mockMvc.perform(delete("/sales-checkin/admin/api/v1/submissions/{id}/media/storefront-photo",
                        submissionId)
                        .with(admin("city-beijing"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"超过临时保留期\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_ADMIN_FORBIDDEN"));
        verify(fileStorage, never()).delete(any(String.class), any(String.class));

        mockMvc.perform(delete("/sales-checkin/admin/api/v1/submissions/{id}/media/storefront-photo",
                        submissionId)
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"超过临时保留期\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(submissionId.toString()))
                .andExpect(jsonPath("$.kind").value("storefront-photo"))
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.deletedAt").isNotEmpty());
        verify(fileStorage, times(1)).delete(TENANT_ID.toString(), "tenant/beijing.jpg");

        mockMvc.perform(delete("/sales-checkin/admin/api/v1/submissions/{id}/media/storefront-photo",
                        submissionId)
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"重复请求应幂等\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"));
        verify(fileStorage, times(1)).delete(TENANT_ID.toString(), "tenant/beijing.jpg");

        mockMvc.perform(get("/sales-checkin/admin/submissions/{id}/media/storefront-photo", submissionId)
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_NOT_FOUND"));
        verify(fileStorage, never()).open(any(String.class), any(String.class));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/options")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaStats.activeFiles").value(0))
                .andExpect(jsonPath("$.mediaStats.totalBytes").value(0))
                .andExpect(jsonPath("$.mediaStats.imageBytes").value(0));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=?
                   AND storefront_photo_deleted_at IS NOT NULL
                   AND storefront_photo_deleted_by=?
                   AND storefront_photo_deletion_reason=?
                """, Integer.class, bin(TENANT_ID), bin(submissionId),
                TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN, "超过临时保留期")).isEqualTo(1);
    }

    @Test
    void previewsImagesAndAudioInlineWithExplicitDownloadsThumbnailsAndRanges() throws Exception {
        UUID submissionId = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "北京门店", "媒体预览客户", "媒体预览验收");
        byte[] jpeg = jpeg(960, 640);
        byte[] wav = new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'A', 'V', 'E'};
        jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET storefront_photo_size_bytes=?,
                       audio_object_key='tenant/beijing.wav', audio_content_type='audio/wav',
                       audio_size_bytes=?, audio_sha256=?, audio_original_filename='visit.wav'
                 WHERE id=?
                """, jpeg.length, wav.length, "c".repeat(64), bin(submissionId));
        when(fileStorage.open(TENANT_ID.toString(), "tenant/beijing.jpg"))
                .thenAnswer(invocation -> new ByteArrayInputStream(jpeg));
        when(fileStorage.open(TENANT_ID.toString(), "tenant/beijing.wav"))
                .thenAnswer(invocation -> new ByteArrayInputStream(wav));

        // 模拟 V19 执行后短暂回滚旧镜像：旧写入器只更新 audio_*，JSON 清单仍为默认空数组。
        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .with(admin("city-beijing")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].audioAvailable").value(true))
                .andExpect(jsonPath("$.items[0].audioSegments[0].segmentId")
                        .value(submissionId.toString()))
                .andExpect(jsonPath("$.items[0].audioSegments[0].originalFilename")
                        .value("visit.wav"));
        mockMvc.perform(get("/sales-checkin/admin/api/v1/options")
                        .with(admin("city-beijing")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaStats.activeFiles").value(2))
                .andExpect(jsonPath("$.mediaStats.totalBytes").value(jpeg.length + wav.length))
                .andExpect(jsonPath("$.mediaStats.audioBytes").value(wav.length));

        mockMvc.perform(get("/sales-checkin/admin/submissions/{id}/media/storefront-photo", submissionId)
                        .with(admin("city-beijing")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("inline;")))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(jpeg));

        MvcResult thumbnail = mockMvc.perform(get(
                        "/sales-checkin/admin/submissions/{id}/media/storefront-photo/thumbnail", submissionId)
                        .with(admin("city-beijing")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("inline;")))
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn();
        BufferedImage thumbnailImage = ImageIO.read(
                new ByteArrayInputStream(thumbnail.getResponse().getContentAsByteArray()));
        assertThat(thumbnailImage).isNotNull();
        assertThat(Math.max(thumbnailImage.getWidth(), thumbnailImage.getHeight())).isLessThanOrEqualTo(320);
        assertThat(thumbnail.getResponse().getContentAsByteArray().length).isLessThan(jpeg.length);

        mockMvc.perform(get("/sales-checkin/admin/submissions/{id}/media/audio", submissionId)
                        .with(admin("city-beijing"))
                        .header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-3/12"))
                .andExpect(header().longValue("Content-Length", 4))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Disposition", startsWith("inline;")))
                .andExpect(content().bytes(new byte[] {'R', 'I', 'F', 'F'}));

        mockMvc.perform(get("/sales-checkin/admin/submissions/{id}/media/audio/{segmentId}",
                        submissionId, submissionId)
                        .with(admin("city-beijing"))
                        .header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-3/12"))
                .andExpect(content().bytes(new byte[] {'R', 'I', 'F', 'F'}));

        mockMvc.perform(get("/sales-checkin/admin/submissions/{id}/media/audio", submissionId)
                        .with(admin("city-beijing"))
                        .header("Range", "bytes=-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 8-11/12"))
                .andExpect(content().bytes(new byte[] {'W', 'A', 'V', 'E'}));

        mockMvc.perform(get("/sales-checkin/admin/submissions/{id}/media/audio", submissionId)
                        .with(admin("city-beijing"))
                        .param("download", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(content().contentType("audio/wav"))
                .andExpect(content().bytes(wav));

        clearInvocations(fileStorage);
        mockMvc.perform(get("/sales-checkin/admin/submissions/{id}/media/audio", submissionId)
                        .with(admin("city-beijing"))
                        .header("Range", "bytes=0-1,4-5"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */12"));
        mockMvc.perform(get("/sales-checkin/admin/submissions/{id}/media/audio/thumbnail", submissionId)
                        .with(admin("city-beijing")))
                .andExpect(status().isBadRequest());
        verify(fileStorage, never()).open(any(String.class), any(String.class));
    }

    private ResultActions upload(UUID submissionId, String kind, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/sales-checkin/api/v1/submissions/{id}/media/{kind}", submissionId, kind)
                .file(file)
                .header("X-Submission-Key", SUBMISSION_KEY)
                .with(request -> {
                    request.setMethod("PUT");
                    return request;
                }));
    }

    private ResultActions uploadAudioSegment(
            UUID submissionId, UUID segmentId, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart(
                        "/sales-checkin/api/v1/submissions/{id}/media/audio/{segmentId}",
                        submissionId, segmentId)
                .file(file)
                .header("X-Submission-Key", SUBMISSION_KEY)
                .with(request -> {
                    request.setMethod("PUT");
                    return request;
                }));
    }

    private CreateSubmissionRequest submission(
            UUID clientSubmissionId, String key, String result, boolean privacyAccepted, LocationCommand location) {
        return new CreateSubmissionRequest(clientSubmissionId, key, "北京", VISITOR_ID, STORE_ID,
                "李经理", "13900000000", result, location, privacyAccepted,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION,
                location == null ? null : locationVerificationToken(VISITOR_ID, "北京", location));
    }

    private CreateSubmissionRequest submissionForStore(
            UUID clientSubmissionId, UUID storeId, String result, LocationCommand location) {
        return new CreateSubmissionRequest(clientSubmissionId, SUBMISSION_KEY, "北京", VISITOR_ID, storeId,
                "李经理", "13900000000", result, location, true,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION,
                locationVerificationToken(VISITOR_ID, "北京", location));
    }

    private static CreateSubmissionRequest unverifiedSubmission(
            UUID clientSubmissionId,
            UUID storeId,
            String result,
            LocationCommand location,
            String failureReason,
            UUID attemptId) {
        return new CreateSubmissionRequest(
                clientSubmissionId, SUBMISSION_KEY, "北京", VISITOR_ID, storeId,
                "李经理", "13900000000", result, location, true,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION,
                null, failureReason, attemptId);
    }

    private CreateStoreRequest poiStore(UUID clientStoreId, String name, String contactName) {
        return poiStoreAtLocation(clientStoreId, name, contactName, location());
    }

    private CreateStoreRequest poiStoreAtLocation(
            UUID clientStoreId, String name, String contactName, LocationCommand location) {
        return new CreateStoreRequest(clientStoreId, "北京", VISITOR_ID,
                "B0FFTESTPOI", "高德候选门店", "北京市东城区测试路1号",
                new BigDecimal("116.397128"), new BigDecimal("39.916527"),
                "台球", name, "营业中", contactName, "13800000000", "100-300平米", "10张球桌",
                List.of("竞技赛事"), List.of("高德业务"), "高意向", "A类", List.of("单店"), location,
                selectionToken(VISITOR_ID, "北京", location, defaultCandidate()),
                locationVerificationToken(VISITOR_ID, "北京", location), null);
    }

    private String selectionToken(
            UUID salespersonId, String city, LocationCommand location, Candidate candidate) {
        return storeSelectionTokenService.issue(salespersonId, city,
                location.longitude(), location.latitude(), location.accuracyMeters(),
                location.capturedAt(), candidate);
    }

    private static Candidate defaultCandidate() {
        return new Candidate("B0FFTESTPOI", "高德候选门店", "北京市东城区服务端地址",
                new BigDecimal("116.403000"), new BigDecimal("39.912000"), "北京市", "110101");
    }

    private CreateStoreRequest manualStore(
            UUID clientStoreId, String name, LocationCommand location) {
        return manualStore(clientStoreId, name, location,
                locationVerificationToken(VISITOR_ID, "北京", location),
                storeSelectionTokenService.issueManual(
                        clientStoreId, VISITOR_ID, "北京", location.longitude(), location.latitude(),
                        location.accuracyMeters(), location.capturedAt(), "EMPTY"));
    }

    private CreateStoreRequest manualStore(
            UUID clientStoreId,
            String name,
            LocationCommand location,
            String locationVerificationToken,
            String manualEntryToken) {
        return new CreateStoreRequest(clientStoreId, "北京", VISITOR_ID,
                null, null, null, null, null,
                "台球", name, "营业中", "提交店长", "13800000000", "100-300平米",
                "10张球桌", List.of("竞技赛事"), List.of("高德业务"), "高意向", "A类",
                List.of("单店"), location, null,
                locationVerificationToken, manualEntryToken);
    }

    private static CreateStoreRequest unverifiedStore(
            UUID clientStoreId,
            String name,
            LocationCommand location,
            String failureReason,
            UUID attemptId) {
        return new CreateStoreRequest(clientStoreId, "北京", VISITOR_ID,
                null, null, null, null, null,
                "台球", name, "营业中", "提交店长", "13800000000", "100-300平米",
                "10张球桌", List.of("竞技赛事"), List.of("高德业务"), "高意向", "A类",
                List.of("单店"), location, null, null, null, failureReason, attemptId);
    }

    private ResolveLocationRequest resolveRequest(
            String city, UUID salespersonId, LocationCommand location) {
        return new ResolveLocationRequest(city, salespersonId, location, null);
    }

    private ResolveLocationRequest resolveRequest(
            String city, UUID salespersonId, LocationCommand location, String query) {
        return new ResolveLocationRequest(city, salespersonId, location, query);
    }

    private SearchNewStoreRequest searchNewStoreRequest(
            String city, UUID salespersonId, LocationCommand location, String query) {
        return searchNewStoreRequest(UUID.randomUUID(), city, salespersonId, location, query);
    }

    private SearchNewStoreRequest searchNewStoreRequest(
            UUID clientStoreId, String city, UUID salespersonId, LocationCommand location, String query) {
        return new SearchNewStoreRequest(clientStoreId, city, salespersonId, location, query,
                locationVerificationToken(salespersonId, city, location));
    }

    private String locationVerificationToken(
            UUID salespersonId, String city, LocationCommand location) {
        return locationVerificationTokenService.issue(
                salespersonId, city, location.longitude(), location.latitude(),
                location.accuracyMeters(), location.capturedAt(), resolvedLocation(city));
    }

    private static GeocodeResult resolvedLocation(String city) {
        return switch (city) {
            case "深圳" -> new GeocodeResult(
                    "RESOLVED", "深圳市南山区测试路1号", "深圳市南山区测试路1号",
                    "440305", "广东省", "深圳市", "南山区", "粤海街道",
                    new BigDecimal("113.930000"), new BigDecimal("22.530000"), null);
            case "南京" -> new GeocodeResult(
                    "RESOLVED", "南京市玄武区测试路1号", "南京市玄武区测试路1号",
                    "320102", "江苏省", "南京市", "玄武区", "梅园街道",
                    new BigDecimal("118.796877"), new BigDecimal("32.060255"), null);
            case "无锡" -> new GeocodeResult(
                    "RESOLVED", "无锡市滨湖区测试路1号", "无锡市滨湖区测试路1号",
                    "320211", "江苏省", "无锡市", "滨湖区", "河埒街道",
                    new BigDecimal("120.274084"), new BigDecimal("31.467526"), null);
            default -> resolvedBeijingLocation();
        };
    }

    private MvcResult createStore(CreateStoreRequest request) {
        try {
            return mockMvc.perform(post("/sales-checkin/api/v1/stores")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(request)))
                    .andReturn();
        } catch (Exception exception) {
            throw new CompletionException(exception);
        }
    }

    private static GeocodeResult resolvedBeijingLocation() {
        return new GeocodeResult("RESOLVED", "东城区龙潭路与夕照寺街交叉口东南60米",
                "北京市东城区龙潭路与夕照寺街交叉口东南60米", "110101", "北京市", "北京市",
                "东城区", "龙潭街道", new BigDecimal("116.403000"), new BigDecimal("39.912000"), null);
    }

    private static LocationCommand location() {
        return new LocationCommand(new BigDecimal("116.3971280"), new BigDecimal("39.9165270"),
                new BigDecimal("8.50"), Instant.now().minusSeconds(30), "门店内");
    }

    private void insertSalesperson(UUID tenantId, UUID id, String name, String city) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_salesperson
                    (id, tenant_id, name, city, position, employment_status, status, sort_order,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, '销售', '在职', 'ACTIVE', 0, ?, ?)
                """, bin(id), bin(tenantId), name, city, Timestamp.from(now), Timestamp.from(now));
    }

    private void insertStore() {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store
                    (id, tenant_id, client_store_id, city, creator_salesperson_id, attribute, name,
                     operating_status, contact_name, contact_phone, area_range, facility_count,
                     business_types_json, intended_businesses_json, cooperation_intent, store_grade,
                     tags_json, longitude, latitude, accuracy_meters, location_captured_at, location_note,
                     status, created_at, updated_at)
                VALUES (?, ?, ?, '北京', ?, '台球', '已导入门店', '营业中', '王店长',
                        '13800000000', '100-300平米', '10张球桌', JSON_ARRAY('竞技赛事'),
                        JSON_ARRAY('高德业务'), '高意向', 'A类', JSON_ARRAY('连锁'),
                        116.3971280, 39.9165270, 8.50, ?, '北京市朝阳区', 'ACTIVE', ?, ?)
                """, bin(STORE_ID), bin(TENANT_ID), bin(STORE_CLIENT_ID), bin(CREATOR_ID),
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now), Timestamp.from(now));
    }

    private void insertShenzhenStore() {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store
                    (id, tenant_id, client_store_id, city, creator_salesperson_id, attribute, name,
                     operating_status, contact_name, area_range, facility_count,
                     business_types_json, intended_businesses_json, cooperation_intent, tags_json,
                     status, created_at, updated_at)
                VALUES (?, ?, ?, '深圳', ?, '台球', '深圳已导入门店', '营业中', '深圳店长',
                        '100-300平米', '8张球桌', JSON_ARRAY('竞技赛事'), JSON_ARRAY('高德业务'),
                        '中意向', JSON_ARRAY('单店'), 'ACTIVE', ?, ?)
                """, bin(SHENZHEN_STORE_ID), bin(TENANT_ID), bin(UUID.randomUUID()),
                bin(SHENZHEN_SALESPERSON_ID), Timestamp.from(now), Timestamp.from(now));
    }

    private void insertImportedStore(UUID id, String name, LocationCommand location) {
        insertImportedStore(id, "北京", CREATOR_ID, name, location);
    }

    private void insertImportedStore(
            UUID id, String city, UUID creatorSalespersonId, String name, LocationCommand location) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store
                    (id, tenant_id, client_store_id, city, creator_salesperson_id, attribute, name,
                     operating_status, contact_name, contact_phone, area_range, facility_count,
                     business_types_json, intended_businesses_json, cooperation_intent, store_grade,
                     tags_json, longitude, latitude, accuracy_meters, location_captured_at, location_note,
                     status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, '台球', ?, '营业中', '历史店长',
                        '13800000000', '100-300平米', '8张球桌', JSON_ARRAY('竞技赛事'),
                        JSON_ARRAY('高德业务'), '中意向', 'B类', JSON_ARRAY('单店'),
                        ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, bin(id), bin(TENANT_ID), bin(UUID.randomUUID()), city,
                bin(creatorSalespersonId), name,
                location == null ? null : location.longitude(),
                location == null ? null : location.latitude(),
                location == null ? null : location.accuracyMeters(),
                location == null ? null : Timestamp.from(location.capturedAt()),
                location == null ? null : location.note(), Timestamp.from(now), Timestamp.from(now));
    }

    private UUID insertAdminSubmission(
            String city, UUID salespersonId, UUID storeId, String storeName, String customerName,
            String visitResult) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String objectKey = "深圳".equals(city) ? "tenant/shenzhen.jpg" : "tenant/beijing.jpg";
        String address = city + "市东城区测试路1号";
        jdbc.update("""
                INSERT INTO temp_sales_checkin_submission
                    (id, tenant_id, client_submission_id, submission_key_hash, status, city,
                     salesperson_id, salesperson_name_snapshot, store_id, store_name_snapshot,
                     customer_name, visit_result, longitude, latitude, accuracy_meters,
                     location_captured_at, location_note, location_address, location_adcode,
                     privacy_accepted, storefront_photo_object_key, storefront_photo_content_type,
                     storefront_photo_size_bytes, storefront_photo_sha256,
                     storefront_photo_original_filename, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, 116.3971280, 39.9165270, 8.50,
                        ?, '现场', ?, '110101', 1, ?, 'image/jpeg', 4, ?, 'store.jpg', ?, ?)
                """, bin(id), bin(TENANT_ID), bin(UUID.randomUUID()), "a".repeat(64), city,
                bin(salespersonId), "深圳".equals(city) ? "深圳拜访人" : "当次拜访人",
                bin(storeId), storeName, customerName, visitResult, Timestamp.from(now.minusSeconds(30)), address,
                objectKey, "b".repeat(64), Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private void insertCsvFormulaSubmission() {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_submission
                    (id, tenant_id, client_submission_id, submission_key_hash, status, city,
                     salesperson_id, salesperson_name_snapshot, store_id, store_name_snapshot,
                     customer_name, customer_phone, visit_result, longitude, latitude, accuracy_meters,
                     location_captured_at, location_note, privacy_accepted,
                     storefront_photo_object_key, storefront_photo_content_type,
                     storefront_photo_size_bytes, storefront_photo_sha256,
                     storefront_photo_original_filename, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'DRAFT', '北京', ?, '=formula', ?, '+formula', '-formula',
                        '@formula', ?, 116.3971280, 39.9165270, 8.50, ?, ?, 1,
                        'tenant/formula.jpg', 'image/jpeg', 4, ?, ?, ?, ?)
                """, bin(UUID.randomUUID()), bin(TENANT_ID), bin(UUID.randomUUID()), "a".repeat(64),
                bin(VISITOR_ID), bin(STORE_ID), "\tformula", Timestamp.from(now.minusSeconds(30)),
                "\rformula", "b".repeat(64), "\nformula", Timestamp.from(now), Timestamp.from(now));
    }

    private void markSubmitted(UUID submissionId, Instant createdAt, Instant submittedAt) {
        jdbc.update("""
                UPDATE temp_sales_checkin_submission
                   SET status='SUBMITTED', created_at=?, submitted_at=?, updated_at=?
                 WHERE tenant_id=? AND id=?
                """, Timestamp.from(createdAt), Timestamp.from(submittedAt), Timestamp.from(submittedAt),
                bin(TENANT_ID), bin(submissionId));
    }

    private static RequestPostProcessor admin(String username) {
        return request -> {
            TemporaryCheckinAdminPrincipal principal = testAdminPrincipal(username);
            if (principal != null) {
                request.setAttribute(TemporaryCheckinAdminPrincipal.REQUEST_ATTRIBUTE, principal);
            }
            return request;
        };
    }

    private static TemporaryCheckinAdminPrincipal testAdminPrincipal(String username) {
        String city = switch (username == null ? "" : username) {
            case "city-beijing" -> "北京";
            case "city-shenzhen" -> "深圳";
            default -> null;
        };
        boolean global = TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN.equals(username);
        if (!global && city == null) return null;
        UUID accountId = UUID.nameUUIDFromBytes(("test-account:" + username).getBytes(StandardCharsets.UTF_8));
        UUID sessionId = UUID.nameUUIDFromBytes(("test-session:" + username).getBytes(StandardCharsets.UTF_8));
        UUID cityId = city == null ? null
                : UUID.nameUUIDFromBytes(("test-city:" + city).getBytes(StandardCharsets.UTF_8));
        return new TemporaryCheckinAdminPrincipal(accountId, sessionId, username, username,
                global ? "GLOBAL_ADMIN" : "CITY_ADMIN", cityId, city, false, "test-csrf-token");
    }

    /** 由 ffmpeg 生成的 50ms AAC/M4A 静音样本，用于验证真实容器中的 soun handler。 */
    private static byte[] realM4a() {
        return Base64.getDecoder().decode(
                "AAAAHGZ0eXBNNEEgAAACAE00QSBpc29taXNvMgAAAAhmcmVlAAAAH21kYXTcAExhdmM2My4xLjEwMQACMEAOARggBwAAAwJtb292"
                + "AAAAbG12aGQAAAAAAAAAAAAAAAAAAB9AAAABkAABAAABAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAA"
                + "AEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAACLXRyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAAAAABkAAA"
                + "AAAAAAAAAAAAAQEAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAACRlZHRzAAAAHGVsc3QA"
                + "AAAAAAAAAQAAAZAAAAQAAAEAAAAAAaVtZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAAB9AAAAFkFXEAAAAAAAtaGRscgAAAAAAAAAA"
                + "c291bgAAAAAAAAAAAAAAAFNvdW5kSGFuZGxlcgAAAAFQbWluZgAAABBzbWhkAAAAAAAAAAAAAAAkZGluZgAAABxkcmVmAAAAAAAA"
                + "AAEAAAAMdXJsIAAAAAEAAAEUc3RibAAAAGpzdHNkAAAAAAAAAAEAAABabXA0YQAAAAAAAAABAAAAAAAAAAAAAQAQAAAAAB9AAAAA"
                + "AAA2ZXNkcwAAAAADgICAJQABAASAgIAXQBUAAAAAAD6AAAAECQWAgIAFFYhW5QAGgICAAQIAAAAgc3R0cwAAAAAAAAACAAAAAQAA"
                + "BAAAAAABAAABkAAAABxzdHNjAAAAAAAAAAEAAAABAAAAAgAAAAEAAAAcc3RzegAAAAAAAAAAAAAAAgAAABMAAAAEAAAAFHN0Y28A"
                + "AAAAAAAAAQAAACwAAAAac2dwZAEAAAByb2xsAAAAAgAAAAH//wAAABxzYmdwAAAAAHJvbGwAAAABAAAAAgAAAAEAAABhdWR0YQAA"
                + "AFltZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAACxpbHN0AAAAJKl0b28AAAAcZGF0YQAAAAEAAAAA"
                + "TGF2ZjYzLjEuMTAx");
    }

    /** 由 ffmpeg 生成的纯 H.264 MP4，用于防止把真实视频误收为录音。 */
    private static byte[] realVideoMp4() {
        return Base64.getDecoder().decode(
                "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAAAm1tZGF0AAACUwYF//9P3EXpvebZSLeWLNgg2SPu73gy"
                + "NjQgLSBjb3JlIDE2NSByMzIyMiBiMzU2MDVhIC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAyNSAt"
                + "IGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM6IGNhYmFjPTAgcmVmPTEgZGVibG9jaz0wOjA6MCBh"
                + "bmFseXNlPTA6MCBtZT1kaWEgc3VibWU9MCBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0wIG1lX3JhbmdlPTE2IGNo"
                + "cm9tYV9tZT0xIHRyZWxsaXM9MCA4eDhkY3Q9MCBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29m"
                + "ZnNldD0wIHRocmVhZHM9MSBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90aHJlYWRzPTAgbnI9MCBkZWNpbWF0ZT0xIGludGVy"
                + "bGFjZWQ9MCBibHVyYXlfY29tcGF0PTAgY29uc3RyYWluZWRfaW50cmE9MCBiZnJhbWVzPTAgd2VpZ2h0cD0wIGtleWludD0yNTAg"
                + "a2V5aW50X21pbj0xIHNjZW5lY3V0PTAgaW50cmFfcmVmcmVzaD0wIHJjPWNyZiBtYnRyZWU9MCBjcmY9MjMuMCBxY29tcD0wLjYw"
                + "IHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBhcT0wAIAAAAAKZYiEOiYoAAkC4AAAAwxtb292AAAAbG12"
                + "aGQAAAAAAAAAAAAAAAAAAAPoAAAD6AABAAABAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAA"
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAACN3RyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAAAAAD6AAAAAAAAAAA"
                + "AAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAEAAAABAAAAAAACRlZHRzAAAAHGVsc3QAAAAAAAAA"
                + "AQAAA+gAAAAAAAEAAAAAAa9tZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAAEAAAABAAFXEAAAAAAAtaGRscgAAAAAAAAAAdmlkZQAA"
                + "AAAAAAAAAAAAAFZpZGVvSGFuZGxlcgAAAAFabWluZgAAABR2bWhkAAAAAQAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAAB"
                + "AAAADHVybCAAAAABAAABGnN0YmwAAAC2c3RzZAAAAAAAAAABAAAApmF2YzEAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAEAAQAEgA"
                + "AABIAAAAAAAAAAEUTGF2YzYzLjEuMTAxIGxpYngyNjQAAAAAAAAAAAAAAAAY//8AAAAsYXZjQwFCwAr/4QAVZ0LACtp7ARAAAAMA"
                + "EAAAAwAg8SJqAQAEaM4PyAAAABBwYXNwAAAAAQAAAAEAAAAUYnRydAAAAAAAABMoAAAAAAAAABhzdHRzAAAAAAAAAAEAAAABAABA"
                + "AAAAABxzdHNjAAAAAAAAAAEAAAABAAAAAQAAAAEAAAAUc3RzegAAAAAAAAJlAAAAAQAAABRzdGNvAAAAAAAAAAEAAAAwAAAAYXVk"
                + "dGEAAABZbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAsaWxzdAAAACSpdG9vAAAAHGRhdGEAAAAB"
                + "AAAAAExhdmY2My4xLjEwMQ==");
    }

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }

    private static byte[] jpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(16, 110, 104));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "jpeg", output)).isTrue();
        return output.toByteArray();
    }
}
