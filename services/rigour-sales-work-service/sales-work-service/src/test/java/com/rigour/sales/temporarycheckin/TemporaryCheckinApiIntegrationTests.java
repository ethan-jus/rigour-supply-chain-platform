package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CreateStoreRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CreateSubmissionRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.LocationCommand;
import com.rigour.shared.file.FileMetadata;
import com.rigour.shared.file.FileStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
        registry.add("rigour.sales.temporary-checkin.tenant-id", TENANT_ID::toString);
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
        reset(fileStorage);
        when(fileStorage.put(any(FileMetadata.class), any(InputStream.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void forwardsProtectedAdminPageEntryToBundledStaticPage() throws Exception {
        mockMvc.perform(get("/sales-checkin/admin/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/sales-checkin/admin/index.html"));
    }

    @Test
    void exposesPublicFixedTenantApiAndEnforcesPayloadAwareIdempotency() throws Exception {
        mockMvc.perform(get("/sales-checkin/api/v1/options")
                        .param("city", "北京")
                        .header("X-Tenant-Id", OTHER_TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salespersons", hasSize(2)))
                .andExpect(jsonPath("$.salespersons[*].id", containsInAnyOrder(
                        CREATOR_ID.toString(), VISITOR_ID.toString())));

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
                UUID.randomUUID(), "北京", VISITOR_ID, "台球", "新建门店", "营业中",
                "张店长", "13800000000", "100-300平米", "10张球桌",
                List.of("竞技赛事"), List.of("高德业务"), "高意向", "A类",
                List.of("飞书旧标签"), location());
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(invalidHistoricalTag)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_BAD_REQUEST"));

        CreateStoreRequest missingLocation = new CreateStoreRequest(
                UUID.randomUUID(), "北京", VISITOR_ID, "台球", "无定位新门店", "营业中",
                "张店长", null, "100-300平米", "8张球桌", List.of("竞技赛事"),
                List.of("高德业务"), "中意向", null, List.of("单店"), null);
        mockMvc.perform(post("/sales-checkin/api/v1/stores")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(missingLocation)))
                .andExpect(status().isBadRequest());
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
                new MockMultipartFile("file", "door.png", "image/jpeg", jpeg))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件扩展名与实际内容不一致"));
        upload(submissionId, "storefront-photo",
                new MockMultipartFile("file", "door.jpg", "image/png", jpeg))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("文件声明类型与实际内容不一致"));
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
        assertThat(metadata.getValue().originalName()).isEqualTo("blob");
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
                new MockMultipartFile("file", "../../store.jpg", "image/jpeg", jpeg))
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
    }

    @Test
    void protectsCsvFromFormulaPrefixesAndKeepsUtf8Bom() throws Exception {
        insertCsvFormulaSubmission();

        MvcResult exported = mockMvc.perform(get("/sales-checkin/admin/export.csv")
                        .header(TemporaryCheckinAdminAccessPolicy.HEADER,
                                TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN))
                .andExpect(status().isOk())
                .andReturn();
        String csv = new String(exported.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("\"'=formula\"", "\"'+formula\"", "\"'-formula\"", "\"'@formula\"",
                "\"'\tformula\"", "\"'\rformula\"", "\"'\nformula\"");
    }

    @Test
    void scopesAdminOptionsListsExportsAndMediaToTrustedUsername() throws Exception {
        UUID beijingSubmission = insertAdminSubmission(
                "北京", VISITOR_ID, STORE_ID, "北京门店", "北京客户", "北京跟进");
        UUID shenzhenSubmission = insertAdminSubmission(
                "深圳", SHENZHEN_SALESPERSON_ID, SHENZHEN_STORE_ID, "深圳门店", "深圳客户", "深圳跟进");

        mockMvc.perform(get("/sales-checkin/admin/api/v1/options"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_ADMIN_FORBIDDEN"));
        mockMvc.perform(get("/sales-checkin/admin/api/v1/options")
                        .header(TemporaryCheckinAdminAccessPolicy.HEADER, "unknown"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/sales-checkin/admin/api/v1/options")
                        .header(TemporaryCheckinAdminAccessPolicy.HEADER, "city-beijing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.username").value("city-beijing"))
                .andExpect(jsonPath("$.scope.allCities").value(false))
                .andExpect(jsonPath("$.scope.city").value("北京"))
                .andExpect(jsonPath("$.cities", hasSize(1)))
                .andExpect(jsonPath("$.cities[0]").value("北京"))
                .andExpect(jsonPath("$.salespersons", hasSize(2)));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .header(TemporaryCheckinAdminAccessPolicy.HEADER, "city-beijing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(beijingSubmission.toString()))
                .andExpect(jsonPath("$.items[0].city").value("北京"))
                .andExpect(jsonPath("$.items[0].locationAddress").value("北京市东城区测试路1号"));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .header(TemporaryCheckinAdminAccessPolicy.HEADER,
                                TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN)
                        .param("q", "深圳客户")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope.allCities").value(true))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(shenzhenSubmission.toString()));

        mockMvc.perform(get("/sales-checkin/admin/api/v1/submissions")
                        .header(TemporaryCheckinAdminAccessPolicy.HEADER, "city-beijing")
                        .param("city", "深圳"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TEMP_CHECKIN_ADMIN_FORBIDDEN"));

        String beijingCsv = new String(mockMvc.perform(get("/sales-checkin/admin/export.csv")
                        .header(TemporaryCheckinAdminAccessPolicy.HEADER, "city-beijing"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(beijingCsv).contains("北京客户", "location_address").doesNotContain("深圳客户");

        String shenzhenCsv = new String(mockMvc.perform(get("/sales-checkin/admin/export.csv")
                        .header(TemporaryCheckinAdminAccessPolicy.HEADER,
                                TemporaryCheckinAdminAccessPolicy.GLOBAL_ADMIN)
                        .param("city", "深圳"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(shenzhenCsv).contains("深圳客户").doesNotContain("北京客户");

        when(fileStorage.open(TENANT_ID.toString(), "tenant/shenzhen.jpg"))
                .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}));
        mockMvc.perform(get("/sales-checkin/admin/submissions/{id}/media/storefront-photo",
                        shenzhenSubmission)
                        .header(TemporaryCheckinAdminAccessPolicy.HEADER, "city-beijing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/sales-checkin/admin/submissions/{id}/media/storefront-photo",
                        shenzhenSubmission)
                        .header(TemporaryCheckinAdminAccessPolicy.HEADER, "city-shenzhen"))
                .andExpect(status().isOk());
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
                "李经理", "13900000000", result, location, privacyAccepted);
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

    private static byte[] bin(UUID value) {
        return SalesUuidCodec.encode(value);
    }
}
