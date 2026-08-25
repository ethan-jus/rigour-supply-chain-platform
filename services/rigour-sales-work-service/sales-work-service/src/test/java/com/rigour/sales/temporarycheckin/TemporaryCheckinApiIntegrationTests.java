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
import com.rigour.sales.temporarycheckin.TemporaryCheckinReverseGeocoder.GeocodeResult;
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
        registry.add("rigour.sales.temporary-checkin.max-checkin-accuracy-meters", () -> 200);
        registry.add("rigour.sales.temporary-checkin.max-location-age-minutes", () -> 60);
    }

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private FileStorage fileStorage;

    @MockitoBean
    private TemporaryCheckinReverseGeocoder reverseGeocoder;

    @MockitoBean
    private AmapPoiClient amapPoiClient;

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
        insertSalesperson(OTHER_TENANT_ID, OTHER_TENANT_SALESPERSON_ID, "外部租户销售", "北京");
        insertStore();
        insertShenzhenStore();
        reset(fileStorage, reverseGeocoder, amapPoiClient, aiClient);
        when(fileStorage.put(any(FileMetadata.class), any(InputStream.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(reverseGeocoder.resolve(any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(new GeocodeResult(
                        "RESOLVED", "东城区龙潭路与夕照寺街交叉口东南60米",
                        "北京市东城区龙潭路与夕照寺街交叉口东南60米", "110101", "北京市", "北京市",
                        "东城区", "龙潭街道", new BigDecimal("116.403000"),
                        new BigDecimal("39.912000"), null));
        when(amapPoiClient.searchAround(any(String.class), any(BigDecimal.class), any(BigDecimal.class),
                anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> "高德候选门店".equals(invocation.getArgument(0))
                        ? new AmapPoiClient.NearbyPoiPage(List.of(
                                new AmapPoiClient.NearbyPoi(
                                        "B0FFTESTPOI", "高德候选门店", "北京市东城区服务端地址",
                                        "休闲服务", "080000", new BigDecimal("116.403000"),
                                        new BigDecimal("39.912000"), BigDecimal.ZERO)), 1, 20, 1)
                        : new AmapPoiClient.NearbyPoiPage(List.of(), 1, 20, 0));
    }

    @Test
    void resolvesReadableLocationAndReturnsNearbyRegisteredStoresWithoutCallingRealAmap() throws Exception {
        ResolveLocationRequest request = new ResolveLocationRequest("北京", location());

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geocodeStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.address").value("东城区龙潭路与夕照寺街交叉口东南60米"))
                .andExpect(jsonPath("$.formattedAddress")
                        .value("北京市东城区龙潭路与夕照寺街交叉口东南60米"))
                .andExpect(jsonPath("$.adcode").value("110101"))
                .andExpect(jsonPath("$.cityMatched").value(true))
                .andExpect(jsonPath("$.resolvedCity").value("北京"))
                .andExpect(jsonPath("$.maxCheckinDistanceMeters").value(300))
                .andExpect(jsonPath("$.maxCheckinAccuracyMeters").value(200))
                .andExpect(jsonPath("$.maxLocationAgeMinutes").value(60))
                .andExpect(jsonPath("$.accuracyAccepted").value(true))
                .andExpect(jsonPath("$.freshnessAccepted").value(true))
                .andExpect(jsonPath("$.nearbyStores[0].source").value("REGISTERED"))
                .andExpect(jsonPath("$.nearbyStores[0].storeId").value(STORE_ID.toString()))
                .andExpect(jsonPath("$.nearbyStores[0].name").value("已导入门店"))
                .andExpect(jsonPath("$.nearbyStores[0].distanceMeters").doesNotExist())
                .andExpect(jsonPath("$.nearbyStores[0].address").value("北京市朝阳区"))
                .andExpect(jsonPath("$.nearbyStores[0].longitude").doesNotExist())
                .andExpect(jsonPath("$.nearbyStores[0].latitude").doesNotExist())
                .andExpect(jsonPath("$.nearbyStores[0].locationSource").value("STORE_LOCATION"))
                .andExpect(jsonPath("$.nearbyStores[0].checkinEligible").value(true))
                .andExpect(jsonPath("$.nearbyStores[0].nextAction").value("CHECK_IN"));

        verify(reverseGeocoder).resolve(new BigDecimal("116.3971280"), new BigDecimal("39.9165270"));
        verify(amapPoiClient).searchAround("", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 20);
    }

    @Test
    void rejectsLocationResolvedToAnotherConfiguredCity() throws Exception {
        when(reverseGeocoder.resolve(any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(new GeocodeResult(
                        "RESOLVED", "深圳市南山区测试路1号", "深圳市南山区测试路1号",
                        "440305", "广东省", "深圳市", "南山区", "粤海街道",
                        new BigDecimal("113.930000"), new BigDecimal("22.530000"), null));

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new ResolveLocationRequest("北京", location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geocodeStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.address").value("深圳市南山区测试路1号"))
                .andExpect(jsonPath("$.cityMatched").value(false))
                .andExpect(jsonPath("$.resolvedCity").value("深圳"))
                .andExpect(jsonPath("$.locationMessage")
                        .value("当前位置在深圳，请将城市切换为深圳后重新定位"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(0)));

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "跨城打卡", true, location()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("当前定位不在所选城市，请重新选择城市并定位"));
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "跨城新店", "高店长"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("当前定位不在所选城市，请重新选择城市并定位"));
    }

    @Test
    void distinguishesNearbyUnregisteredAmapPoiAsProfileCompletionCandidate() throws Exception {
        when(amapPoiClient.searchAround("台球", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 20))
                .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(
                        new AmapPoiClient.NearbyPoi("B0FFNEWPOI", "附近新门店", "北京市东城区测试路2号",
                                "休闲服务", "080000", new BigDecimal("116.403100"),
                                new BigDecimal("39.912100"), new BigDecimal("14"))), 1, 20, 1));

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new ResolveLocationRequest("北京", location(), "台球"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores", hasSize(1)))
                .andExpect(jsonPath("$.nearbyStores[0].source").value("AMAP_POI"))
                .andExpect(jsonPath("$.nearbyStores[0].storeId").doesNotExist())
                .andExpect(jsonPath("$.nearbyStores[0].poiId").value("B0FFNEWPOI"))
                .andExpect(jsonPath("$.nearbyStores[0].longitude").value(116.403100))
                .andExpect(jsonPath("$.nearbyStores[0].latitude").value(39.912100))
                .andExpect(jsonPath("$.nearbyStores[0].locationSource").value("AMAP_POI"))
                .andExpect(jsonPath("$.nearbyStores[0].checkinEligible").value(false))
                .andExpect(jsonPath("$.nearbyStores[0].nextAction").value("COMPLETE_STORE_PROFILE"));
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
                   SET accuracy_meters=250.01
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
                                new ResolveLocationRequest("北京", location()))))
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
                new BigDecimal("39.9165270"), new BigDecimal("250.01"),
                Instant.now().minusSeconds(30), "定位漂移");
        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new ResolveLocationRequest("北京", inaccurate))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address")
                        .value("东城区龙潭路与夕照寺街交叉口东南60米"))
                .andExpect(jsonPath("$.accuracyAccepted").value(false))
                .andExpect(jsonPath("$.locationMessage")
                        .value("当前定位精度约251米，超过允许的200米，请到室外或开阔处重新定位"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(0)));
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "低精度打卡", true, inaccurate))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("当前定位精度约251米，超过允许的200米，请到室外或开阔处重新定位"));
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(poiStoreAtLocation(
                                UUID.randomUUID(), "低精度新店", "赵店长", inaccurate))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("当前定位精度约251米，超过允许的200米，请到室外或开阔处重新定位"));
    }

    @Test
    void fallsBackToFirstAcceptableVisitWhenStoreCoordinatesHaveUnacceptableAccuracy()
            throws Exception {
        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET accuracy_meters=250.01
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));
        UUID firstAcceptable = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "已导入门店", "历史客户", "合格锚点");
        Instant submittedAt = Instant.now().minusSeconds(90);
        markSubmitted(firstAcceptable, submittedAt.minusSeconds(10), submittedAt);

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new ResolveLocationRequest("北京", location()))))
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
    void returnsReadableAddressButRejectsStaleLocationForStoreAndSubmissionWrites()
            throws Exception {
        LocationCommand stale = new LocationCommand(new BigDecimal("116.3971280"),
                new BigDecimal("39.9165270"), new BigDecimal("8.50"),
                Instant.now().minusSeconds(61 * 60), "过期定位");
        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new ResolveLocationRequest("北京", stale))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address")
                        .value("东城区龙潭路与夕照寺街交叉口东南60米"))
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
                                new ResolveLocationRequest("北京", tooFarFuture))))
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
    void failsClosedForWritesWhenReverseGeocodingIsUnavailable() throws Exception {
        when(reverseGeocoder.resolve(any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(GeocodeResult.failed("AMAP_UNAVAILABLE"));

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new ResolveLocationRequest("北京", location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.geocodeStatus").value("FAILED"))
                .andExpect(jsonPath("$.cityMatched").doesNotExist())
                .andExpect(jsonPath("$.locationMessage").value("地址暂未解析，定位坐标已记录"))
                .andExpect(jsonPath("$.nearbyStores", hasSize(0)));
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "地址解析失败", true, location()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("当前定位地址解析失败，请重新定位后再提交"));
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "地址失败新店", "赵店长"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("当前定位地址解析失败，请重新定位后再提交"));
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

        CreateSubmissionRequest firstVisit = new CreateSubmissionRequest(
                UUID.randomUUID(), SUBMISSION_KEY, "北京", VISITOR_ID, newStoreId,
                "周店长", "13800000000", "首访已完成基础沟通", location(), true,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION);
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
                                new ResolveLocationRequest("北京", location()))))
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

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new ResolveLocationRequest(
                                "北京", location(), "高德候选门店"))))
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
                        .content(objectMapper.writeValueAsBytes(new ResolveLocationRequest(
                                "北京", location(), "高德候选门店"))))
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

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new ResolveLocationRequest(
                                "北京", location(), "高德候选门店"))))
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
    void revalidatesPoiServerSideAndPersistsOnlyCanonicalAmapSnapshot() throws Exception {
        CreateStoreRequest clientSupplied = new CreateStoreRequest(
                UUID.randomUUID(), "北京", VISITOR_ID,
                "B0FFTESTPOI", "高德候选门店", "客户端伪造地址",
                BigDecimal.ZERO, BigDecimal.ZERO,
                "台球", "客户端伪造名称", "营业中", "张店长", "13800000000",
                "100-300平米", "10张球桌", List.of("竞技赛事"), List.of("高德业务"),
                "高意向", "A类", List.of("单店"), location());

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
    }

    @Test
    void usesVerifiedAmapPoiAnchorForResolveAndEveryLaterCheckinWithoutDoubleDistanceAllowance()
            throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "高德候选门店", "张店长"))))
                .andExpect(status().isOk())
                .andReturn();
        UUID poiStoreId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new ResolveLocationRequest("北京", location(), "高德"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores[0].storeId").value(poiStoreId.toString()))
                .andExpect(jsonPath("$.nearbyStores[0].locationSource").value("AMAP_POI"))
                .andExpect(jsonPath("$.nearbyStores[0].distanceMeters").doesNotExist())
                .andExpect(jsonPath("$.nearbyStores[0].longitude").doesNotExist())
                .andExpect(jsonPath("$.nearbyStores[0].latitude").doesNotExist());

        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submissionForStore(
                                UUID.randomUUID(), poiStoreId, "POI内首次拜访", location()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // 这次浏览器 WGS84 坐标仍贴近首次采集位置，但服务端转换后的 GCJ-02
        // 已距官方 POI 超过 300 米；旧实现会错误地按首次 GPS 锚点放行。
        LocationCommand shifted = new LocationCommand(new BigDecimal("116.3980000"),
                new BigDecimal("39.9165270"), new BigDecimal("8.50"),
                Instant.now().minusSeconds(30), "靠近首次GPS但远离POI");
        when(reverseGeocoder.resolve(new BigDecimal("116.3980000"), new BigDecimal("39.9165270")))
                .thenReturn(new GeocodeResult(
                        "RESOLVED", "北京市东城区测试路", "北京市东城区测试路", "110101",
                        "北京市", "北京市", "东城区", "龙潭街道",
                        new BigDecimal("116.407000"), new BigDecimal("39.912000"), null));

        mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new ResolveLocationRequest("北京", shifted, "高德"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores", hasSize(0)));
        mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submissionForStore(
                                UUID.randomUUID(), poiStoreId, "双距离绕过尝试", shifted))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith("当前定位距离门店约")));
    }

    @Test
    void rejectsForgedFarPoiManualDuplicateAndAmapVerificationFailure() throws Exception {
        when(amapPoiClient.searchAround("高德候选门店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 20))
                .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(
                        new AmapPoiClient.NearbyPoi(
                                "B0FFTESTPOI", "高德候选门店", "远程地址", "休闲服务", "080000",
                                new BigDecimal("116.503000"), new BigDecimal("39.912000"),
                                new BigDecimal("9000"))), 1, 20, 1));
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "远程伪造门店", "赵店长"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith("所选高德门店不在当前位置")));

        when(amapPoiClient.searchAround("附近手工门店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 20))
                .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(
                        new AmapPoiClient.NearbyPoi(
                                "B0FFMANUAL", "附近手工门店", "附近地址", "休闲服务", "080000",
                                new BigDecimal("116.403000"), new BigDecimal("39.912000"),
                                BigDecimal.ZERO)), 1, 20, 1));
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                UUID.randomUUID(), "附近手工门店", location()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", startsWith("附近已有高德门店")));

        when(amapPoiClient.searchAround("高德候选门店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 20))
                .thenThrow(new com.rigour.sales.application.port.out.AmapPoiException("上游失败"));
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "校验失败门店", "赵店长"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("附近门店校验暂不可用，请稍后重新定位再试"));
    }

    @Test
    void atomicallyBindsUniqueUnlocatedImportedStoreAndPreservesBusinessProfile() throws Exception {
        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET name='高德候选门店', longitude=NULL, latitude=NULL,
                       accuracy_meters=NULL, location_captured_at=NULL, location_note=NULL,
                       source_poi_id=NULL, source_poi_name=NULL, source_poi_address=NULL,
                       source_poi_longitude=NULL, source_poi_latitude=NULL
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "客户端名称", "提交店长"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(STORE_ID.toString()))
                .andExpect(jsonPath("$.name").value("高德候选门店"));
        var bound = jdbc.queryForMap("""
                SELECT contact_name, business_types_json, source_poi_id, source_poi_address,
                       longitude, latitude
                  FROM temp_sales_checkin_store WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));
        assertThat(bound.get("contact_name")).isEqualTo("王店长");
        assertThat(bound.get("business_types_json").toString()).contains("竞技赛事");
        assertThat(bound.get("source_poi_id")).isEqualTo("B0FFTESTPOI");
        assertThat(bound.get("source_poi_address")).isEqualTo("北京市东城区服务端地址");
        assertThat((BigDecimal) bound.get("longitude")).isEqualByComparingTo("116.3971280");
    }

    @Test
    void rejectsManualSameNameInsteadOfBindingHistoricalImportedStore() throws Exception {
        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET name='手工导入门店', longitude=NULL, latitude=NULL,
                       accuracy_meters=NULL, location_captured_at=NULL, location_note=NULL,
                       source_poi_id=NULL, source_poi_name=NULL, source_poi_address=NULL,
                       source_poi_longitude=NULL, source_poi_latitude=NULL
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(manualStore(
                                UUID.randomUUID(), "手工导入门店", location()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "已存在同名历史门店，手工录入不能自动绑定；请从附近门店选择高德POI或联系管理员核对"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND city='北京' AND name='手工导入门店'
                """, Integer.class, bin(TENANT_ID))).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_store
                 WHERE tenant_id=? AND id=? AND longitude IS NULL AND source_poi_id IS NULL
                """, Integer.class, bin(TENANT_ID), bin(STORE_ID))).isEqualTo(1);
    }

    @Test
    void rejectsAmbiguousImportedStoreNamesInsteadOfGuessing() throws Exception {
        jdbc.update("""
                UPDATE temp_sales_checkin_store
                   SET name='高德候选门店', longitude=NULL, latitude=NULL,
                       accuracy_meters=NULL, location_captured_at=NULL
                 WHERE tenant_id=? AND id=?
                """, bin(TENANT_ID), bin(STORE_ID));
        insertImportedStore(UUID.randomUUID(), "高德候选门店", null);

        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                poiStore(UUID.randomUUID(), "不应新增", "赵店长"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", startsWith("发现多条同名历史门店")));
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
    void reservesNearbyResultCapacityForAmapPoiWhenRegisteredStoresFillTheLimit()
            throws Exception {
        for (int index = 0; index < 20; index++) {
            insertImportedStore(UUID.randomUUID(), "附近门店" + index, location());
        }
        when(amapPoiClient.searchAround("门店", new BigDecimal("116.403000"),
                new BigDecimal("39.912000"), 300, 1, 20))
                .thenReturn(new AmapPoiClient.NearbyPoiPage(List.of(
                        new AmapPoiClient.NearbyPoi(
                                "B0FFCAPACITY", "附近新门店", "附近地址", "休闲服务", "080000",
                                new BigDecimal("116.403000"), new BigDecimal("39.912000"),
                                BigDecimal.ZERO)), 1, 20, 1));

        MvcResult result = mockMvc.perform(post("/sales-checkin/api/v1/locations/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                new ResolveLocationRequest("北京", location(), "门店"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyStores", hasSize(20)))
                .andReturn();
        var items = objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("nearbyStores");
        long poiCount = java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .filter(item -> "AMAP_POI".equals(item.path("source").asText())).count();
        assertThat(poiCount).isEqualTo(1);
    }

    @Test
    void concurrentlyReusesAmapPoiAfterUniqueKeyConflictUnderRepeatableRead() throws Exception {
        CountDownLatch bothRequestsPassedPrecheck = new CountDownLatch(2);
        when(reverseGeocoder.resolve(any(BigDecimal.class), any(BigDecimal.class)))
                .thenAnswer(invocation -> {
                    bothRequestsPassedPrecheck.countDown();
                    if (!bothRequestsPassedPrecheck.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发请求未同时到达写入前屏障");
                    }
                    return resolvedBeijingLocation();
                });
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
    void serializesConcurrentUploadAndDeleteSoLateDatabaseWriteCannotReviveMedia() throws Exception {
        MvcResult created = mockMvc.perform(post("/sales-checkin/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(submission(
                                UUID.randomUUID(), SUBMISSION_KEY, "并发上传删除", true, location()))))
                .andExpect(status().isOk())
                .andReturn();
        UUID submissionId = UUID.fromString(objectMapper.readTree(
                created.getResponse().getContentAsByteArray()).path("id").asText());
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
                return upload(submissionId, "audio",
                        new MockMultipartFile("file", "visit.wav", "audio/wav", wav)).andReturn();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        if (!uploadEnteredStorage.await(5, TimeUnit.SECONDS)) {
            allowUploadToFinish.countDown();
            throw new AssertionError("PUT未进入受行锁保护的COS写入阶段");
        }
        CompletableFuture<MvcResult> deleteFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(delete(
                                "/sales-checkin/api/v1/submissions/{id}/media/audio", submissionId)
                                .header("X-Submission-Key", SUBMISSION_KEY))
                        .andReturn();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        try {
            Thread.sleep(150);
            assertThat(deleteFuture.isDone())
                    .as("DELETE必须等待持有同一草稿行锁的PUT完成")
                    .isFalse();
        } finally {
            allowUploadToFinish.countDown();
        }

        assertThat(uploadFuture.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        assertThat(deleteFuture.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        assertThat(uploadedMetadata.get()).isNotNull();
        verify(fileStorage).delete(TENANT_ID.toString(), uploadedMetadata.get().objectKey());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM temp_sales_checkin_submission
                 WHERE tenant_id=? AND id=? AND audio_object_key IS NULL
                   AND audio_content_type IS NULL AND audio_size_bytes IS NULL
                   AND audio_sha256 IS NULL AND audio_original_filename IS NULL
                """, Integer.class, bin(TENANT_ID), bin(submissionId))).isEqualTo(1);
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
                        new byte[] {'#', '!', 'A', 'M', 'R', '-', 'W', 'B', '\n'}));

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

        upload(submissionId, "audio", new MockMultipartFile(
                "file", "voice.m4a", "audio/mp4",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("录音格式不支持或文件内容损坏"));

        upload(submissionId, "audio", new MockMultipartFile(
                "file", "voice.m4a", "audio/mp4", realVideoMp4()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("录音格式不支持或文件内容损坏"));

        upload(submissionId, "audio", new MockMultipartFile(
                "file", "voice.m4a", "audio/mp4", new byte[0]))
                .andExpect(status().isBadRequest());
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
                .matches(objectPrefix + "screenshots/wechat/[0-9a-f]{64}\\.png");
        assertThat(storedMedia.get(2).originalName()).isEqualTo("visit.wav");
        assertThat(storedMedia.get(2).objectKey())
                .matches(objectPrefix + "recordings/visit/[0-9a-f]{64}\\.wav");
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
                        .param("q", "今日客户"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(today.toString()))
                .andExpect(jsonPath("$.items[0].visitOrdinal").value(2))
                .andExpect(jsonPath("$.items[0].visitType").value("REVISIT"))
                .andExpect(jsonPath("$.items[0].revisitNumber").value(1));

        String csv = new String(mockMvc.perform(get("/sales-checkin/admin/export.csv")
                        .with(admin(TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                        .param("from", dateFilter)
                        .param("status", "SUBMITTED"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(csv)
                .contains("\"visit_ordinal\",\"visit_type\",\"revisit_number\"")
                .contains(",\"2\",\"REVISIT\",\"1\",\"今日客户\",")
                .doesNotContain("历史客户");
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

    private CreateSubmissionRequest submission(
            UUID clientSubmissionId, String key, String result, boolean privacyAccepted, LocationCommand location) {
        return new CreateSubmissionRequest(clientSubmissionId, key, "北京", VISITOR_ID, STORE_ID,
                "李经理", "13900000000", result, location, privacyAccepted,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION);
    }

    private CreateSubmissionRequest submissionForStore(
            UUID clientSubmissionId, UUID storeId, String result, LocationCommand location) {
        return new CreateSubmissionRequest(clientSubmissionId, SUBMISSION_KEY, "北京", VISITOR_ID, storeId,
                "李经理", "13900000000", result, location, true,
                TemporaryCheckinService.PRIVACY_NOTICE_VERSION);
    }

    private static CreateStoreRequest poiStore(UUID clientStoreId, String name, String contactName) {
        return poiStoreAtLocation(clientStoreId, name, contactName, location());
    }

    private static CreateStoreRequest poiStoreAtLocation(
            UUID clientStoreId, String name, String contactName, LocationCommand location) {
        return new CreateStoreRequest(clientStoreId, "北京", VISITOR_ID,
                "B0FFTESTPOI", "高德候选门店", "北京市东城区测试路1号",
                new BigDecimal("116.397128"), new BigDecimal("39.916527"),
                "台球", name, "营业中", contactName, "13800000000", "100-300平米", "10张球桌",
                List.of("竞技赛事"), List.of("高德业务"), "高意向", "A类", List.of("单店"), location);
    }

    private static CreateStoreRequest manualStore(
            UUID clientStoreId, String name, LocationCommand location) {
        return new CreateStoreRequest(clientStoreId, "北京", VISITOR_ID,
                null, null, null, null, null,
                "台球", name, "营业中", "提交店长", "13800000000", "100-300平米",
                "10张球桌", List.of("竞技赛事"), List.of("高德业务"), "高意向", "A类",
                List.of("单店"), location);
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
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store
                    (id, tenant_id, client_store_id, city, creator_salesperson_id, attribute, name,
                     operating_status, contact_name, contact_phone, area_range, facility_count,
                     business_types_json, intended_businesses_json, cooperation_intent, store_grade,
                     tags_json, longitude, latitude, accuracy_meters, location_captured_at, location_note,
                     status, created_at, updated_at)
                VALUES (?, ?, ?, '北京', ?, '台球', ?, '营业中', '历史店长',
                        '13800000000', '100-300平米', '8张球桌', JSON_ARRAY('竞技赛事'),
                        JSON_ARRAY('高德业务'), '中意向', 'B类', JSON_ARRAY('单店'),
                        ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, bin(id), bin(TENANT_ID), bin(UUID.randomUUID()), bin(CREATOR_ID), name,
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
