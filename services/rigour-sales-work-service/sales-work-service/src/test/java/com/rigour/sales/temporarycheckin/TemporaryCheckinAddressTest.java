package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAccessPolicy.AdminScope;
import com.rigour.sales.temporarycheckin.TemporaryCheckinReverseGeocoder.GeocodeResult;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** 真实 MySQL 验证显式补解析、缓存和配额跨实例有效，原始定位和后台权限不会随解析结果被改写。 */
@Testcontainers(disabledWithoutDocker=true)
class TemporaryCheckinAddressTest {
    @Container static final org.testcontainers.mysql.MySQLContainer MYSQL = new org.testcontainers.mysql.MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work").withUsername("address_test").withPassword("address_test_password");
    private static final UUID TENANT=UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER=UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID SALES=UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID STORE=UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant NOW=Instant.parse("2026-09-08T02:00:00Z");
    private static final AdminScope GLOBAL=new AdminScope(UUID.randomUUID(),"address-admin",null);
    private final JsonMapper mapper=JsonMapper.builder().findAndAddModules().build();
    private JdbcTemplate jdbc;
    private TemporaryCheckinAddressRepository repository;
    private TemporaryCheckinReverseGeocoder geocoder;
    private TemporaryCheckinAddressService service;
    private MutableClock clock;
    private MockMvc mvc;
    private TemporaryCheckinAdminAuthProperties authProperties;

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).load().migrate();
    }
    @BeforeEach void prepare() {
        String url=MYSQL.getJdbcUrl();
        var ds=new DriverManagerDataSource(url+(url.contains("?") ? "&" : "?")+"connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(ds);
        jdbc.update("DELETE FROM temp_sales_checkin_submission");
        jdbc.update("DELETE FROM temp_sales_checkin_store");
        jdbc.update("DELETE FROM temp_sales_checkin_salesperson");
        jdbc.update("DELETE FROM temp_sales_checkin_address_cache");
        jdbc.update("DELETE FROM temp_sales_checkin_address_guard");
        seed(TENANT,SALES,STORE);
        repository=new TemporaryCheckinAddressRepository(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)));
        geocoder=mock(TemporaryCheckinReverseGeocoder.class);
        when(geocoder.resolve(any(),any())).thenReturn(answer("设备坐标测试路11号"));
        clock=new MutableClock(NOW);
        service=newService(TENANT,100);
        authProperties=new TemporaryCheckinAdminAuthProperties();
        var auth=mock(TemporaryCheckinAdminAuthService.class);
        when(auth.authenticate("global-session")).thenReturn(principal(null));
        when(auth.authenticate("city-session")).thenReturn(principal("北京"));
        mvc=MockMvcBuilders.standaloneSetup(new TemporaryCheckinAddressController(service,new TemporaryCheckinAdminAccessPolicy()))
                .setControllerAdvice(new TemporaryCheckinExceptionHandler())
                .addFilters(new TemporaryCheckinAdminAuthenticationFilter(auth,authProperties,mapper)).build();
    }

    @Test void readsWithoutNetworkAndNeverSubstitutesStoreAddressForMissingCoordinates() {
        UUID id=submission(TENANT,SALES,STORE,"北京",116);
        assertThat(service.get(GLOBAL,id).status()).isEqualTo("UNRESOLVED");
        jdbc.update("UPDATE temp_sales_checkin_submission SET longitude=NULL,latitude=NULL WHERE id=?",bin(id));
        assertThat(service.resolve(GLOBAL,id).status()).isEqualTo("MISSING_COORDINATES");
        assertThat(service.get(GLOBAL,id).locationAddress()).isNull();
        verify(geocoder,never()).resolve(any(),any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_address_guard",Integer.class)).isZero();
    }

    @Test void persistsAddressAndReusesItAcrossServiceInstancesWithoutChangingOriginalEvidence() {
        UUID first=submission(TENANT,SALES,STORE,"北京",116);
        var before=evidence(first);
        var resolved=service.resolve(GLOBAL,first);
        assertThat(resolved.locationAddress()).isEqualTo("北京市设备坐标测试路11号");
        assertThat(resolved.addressSource()).isEqualTo("HISTORICAL_COORDINATES_RESOLVED_LATER");
        assertThat(resolved.addressResolvedAt()).isEqualTo(NOW);
        assertThat(evidence(first)).isEqualTo(before);
        assertThat(service.resolve(GLOBAL,first).cached()).isTrue();
        UUID second=submission(TENANT,SALES,STORE,"北京",116);
        var restarted=newService(TENANT,100);
        assertThat(restarted.get(GLOBAL,second).status()).isEqualTo("UNRESOLVED");
        assertThat(restarted.resolve(GLOBAL,second).cached()).isTrue();
        verify(geocoder,times(1)).resolve(new BigDecimal("116.0000000"),new BigDecimal("39.0000000"));
        assertThat(jdbc.queryForObject("SELECT calls_used FROM temp_sales_checkin_address_guard WHERE tenant_id=?",Integer.class,bin(TENANT))).isEqualTo(1);
    }

    @Test void existingSnapshotNeverTriggersGeocoderAndNewCaptureCanSeedReusableCache() {
        UUID first=submission(TENANT,SALES,STORE,"北京",116);
        jdbc.update("UPDATE temp_sales_checkin_submission SET location_address='原设备地址',location_formatted_address='北京市原设备地址',"
                +"geocode_status='RESOLVED',amap_longitude=116.006,amap_latitude=39.002 WHERE id=?",bin(first));
        assertThat(service.resolve(GLOBAL,first).addressSource()).isEqualTo("LEGACY_SNAPSHOT");
        assertThat(service.get(GLOBAL,first).addressResolvedAt()).isNull();
        repository.recordCaptureSnapshot(TENANT,first,NOW);
        UUID second=submission(TENANT,SALES,STORE,"北京",116);
        assertThat(service.resolve(GLOBAL,second).locationAddress()).isEqualTo("北京市原设备地址");
        verify(geocoder,never()).resolve(any(),any());
    }

    @Test void formattedAddressWinsAcrossAddressAdminAndBothExportReadsWithoutRewritingRawFields() throws Exception {
        UUID id=submission(TENANT,SALES,STORE,"北京",116);
        jdbc.update("UPDATE temp_sales_checkin_submission SET location_address=?,location_formatted_address=? WHERE id=?",
                "设备留存的简写地址","  北京市设备坐标对应的完整街道11号  ",bin(id));
        var before=addressEvidence(id);
        String expected="北京市设备坐标对应的完整街道11号";
        String path="/sales-checkin/admin/api/v1/submissions/"+id+"/address";
        mvc.perform(authorized(get(path),"global-session"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.locationAddress").value(expected))
                .andExpect(jsonPath("$.addressSource").value("LEGACY_SNAPSHOT"));
        mvc.perform(authorized(post(path+"/resolve"),"global-session"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locationAddress").value(expected));
        assertAddressProjections(id,expected);
        assertThat(addressEvidence(id)).isEqualTo(before);
        verify(geocoder,never()).resolve(any(),any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_address_guard",Integer.class)).isZero();
    }

    @Test void formattedOnlySnapshotIsResolvedWithoutCoordinatesOrProviderCallsAndBlankFormattedFallsBack() throws Exception {
        UUID id=submission(TENANT,SALES,STORE,"北京",116);
        jdbc.update("UPDATE temp_sales_checkin_submission SET location_address=NULL,location_formatted_address=?,longitude=NULL,latitude=NULL WHERE id=?",
                "历史仅留存的设备完整地址",bin(id));
        var before=addressEvidence(id);
        String path="/sales-checkin/admin/api/v1/submissions/"+id+"/address";
        mvc.perform(authorized(get(path),"global-session"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.locationAddress").value("历史仅留存的设备完整地址"))
                .andExpect(jsonPath("$.addressResolvedAt").isEmpty());
        mvc.perform(authorized(post(path+"/resolve"),"global-session"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.locationAddress").value("历史仅留存的设备完整地址"));
        assertAddressProjections(id,"历史仅留存的设备完整地址");
        assertThat(addressEvidence(id)).isEqualTo(before);
        jdbc.update("UPDATE temp_sales_checkin_submission SET location_address=?,location_formatted_address='   ' WHERE id=?","原始设备地址",bin(id));
        assertThat(service.resolve(GLOBAL,id).locationAddress()).isEqualTo("原始设备地址");
        assertAddressProjections(id,"原始设备地址");
        verify(geocoder,never()).resolve(any(),any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_address_cache",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_address_guard",Integer.class)).isZero();
    }

    @Test void lateResolvedAnswerCannotOverwriteFormattedOnlyAddressSavedAfterInitialRead() {
        UUID id=submission(TENANT,SALES,STORE,"北京",116);
        var initial=repository.find(TENANT,id,"北京");
        String key=TemporaryCheckinAddressRepository.coordinateHash(initial.longitude(),initial.latitude());
        // 模拟另一个请求在本请求读完之后、派生写入之前已保存完整地址。
        jdbc.update("UPDATE temp_sales_checkin_submission SET location_formatted_address=? WHERE id=?",
                "另一请求已保存的设备完整地址",bin(id));
        var before=addressEvidence(id);
        assertThat(repository.apply(TENANT,initial,key,answer("迟到且不应覆盖的答案"),NOW,NOW.plusSeconds(2))).isZero();
        assertThat(addressEvidence(id)).isEqualTo(before);
        assertThat(service.resolve(GLOBAL,id).locationAddress()).isEqualTo("另一请求已保存的设备完整地址");
        verify(geocoder,never()).resolve(any(),any());
    }

    @Test void enforcesAuthenticationCsrfCityTenantAndDeletionBeforeCallingProvider() throws Exception {
        UUID id=submission(TENANT,SALES,STORE,"深圳",116);
        String path="/sales-checkin/admin/api/v1/submissions/"+id+"/address/resolve";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).cookie(new Cookie(authProperties.getCookieName(),"global-session"))).andExpect(status().isForbidden());
        mvc.perform(authorized(post(path),"city-session")).andExpect(status().isNotFound());
        mvc.perform(authorized(get("/sales-checkin/admin/api/v1/submissions/"+id+"/address"),"city-session")).andExpect(status().isNotFound());
        assertThatThrownBy(() -> newService(OTHER,100).resolve(GLOBAL,id)).isInstanceOf(TemporaryCheckinException.class);
        jdbc.update("UPDATE temp_sales_checkin_submission SET deletion_state='PENDING' WHERE id=?",bin(id));
        mvc.perform(authorized(post(path),"global-session")).andExpect(status().isNotFound());
        verify(geocoder,never()).resolve(any(),any());
    }

    @Test void ignoresClientCoordinatesAndUsesOnlySavedDevicePoint() throws Exception {
        UUID id=submission(TENANT,SALES,STORE,"北京",116);
        mvc.perform(authorized(post("/sales-checkin/admin/api/v1/submissions/"+id+"/address/resolve"),"global-session")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"longitude\":0,\"latitude\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.locationAddress").value("北京市设备坐标测试路11号"));
        verify(geocoder).resolve(new BigDecimal("116.0000000"),new BigDecimal("39.0000000"));
    }

    @Test void isolatesPersistentCacheAndBudgetAcrossTenants() {
        UUID otherSales=UUID.randomUUID(),otherStore=UUID.randomUUID();
        seed(OTHER,otherSales,otherStore);
        service.resolve(GLOBAL,submission(TENANT,SALES,STORE,"北京",116));
        newService(OTHER,1).resolve(GLOBAL,submission(OTHER,otherSales,otherStore,"北京",116));
        verify(geocoder,times(2)).resolve(any(),any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_address_guard",Integer.class)).isEqualTo(2);
    }

    @Test void coolsDownFailuresAndKeepsDailyQuotaAcrossRestartsAndChineseDateBoundary() {
        UUID one=submission(TENANT,SALES,STORE,"北京",116);
        when(geocoder.resolve(any(),any())).thenReturn(GeocodeResult.failed("AMAP_CONNECTION_FAILED"),answer("重试地址"));
        service=newService(TENANT,2);
        assertThat(service.resolve(GLOBAL,one).status()).isEqualTo("FAILED");
        assertThat(newService(TENANT,2).resolve(GLOBAL,one).status()).isEqualTo("RETRY_LATER");
        verify(geocoder,times(1)).resolve(any(),any());
        clock.now=NOW.plusSeconds(900);
        assertThat(service.resolve(GLOBAL,one).status()).isEqualTo("RESOLVED");
        UUID two=submission(TENANT,SALES,STORE,"北京",117);
        var exhausted=newService(TENANT,2).resolve(GLOBAL,two);
        assertThat(exhausted.status()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(exhausted.retryAt()).isEqualTo(Instant.parse("2026-09-08T16:00:00Z"));
        clock.now=exhausted.retryAt();
        assertThat(service.resolve(GLOBAL,two).status()).isEqualTo("RESOLVED");
        verify(geocoder,times(3)).resolve(any(),any());
    }

    @Test void serializesConcurrentNetworkCallsWithoutHoldingDatabaseTransaction() throws Exception {
        UUID one=submission(TENANT,SALES,STORE,"北京",116),two=submission(TENANT,SALES,STORE,"北京",117);
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        when(geocoder.resolve(any(),any())).thenAnswer(ignored -> {
            started.countDown(); if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
            return answer("并发地址");
        });
        var future=CompletableFuture.supplyAsync(() -> service.resolve(GLOBAL,one));
        try {
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(newService(TENANT,100).resolve(GLOBAL,two).status()).isEqualTo("PROCESSING");
            assertThat(newService(TENANT,100).resolve(GLOBAL,one).status()).isEqualTo("PROCESSING");
            verify(geocoder,times(1)).resolve(any(),any());
        } finally { release.countDown(); }
        assertThat(future.get(5,TimeUnit.SECONDS).status()).isEqualTo("RESOLVED");
    }

    @Test void staleLeaseCannotOverwriteNewAnswerOrReleaseNewClaim() {
        UUID id=submission(TENANT,SALES,STORE,"北京",116);
        var row=repository.find(TENANT,id,null);
        String key=TemporaryCheckinAddressRepository.coordinateHash(row.longitude(),row.latitude());
        var day=NOW.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var old=repository.claim(TENANT,row,key,"old",day,NOW,100,NOW.plusSeconds(120));
        Instant later=NOW.plusSeconds(121);
        var fresh=repository.claim(TENANT,row,key,"fresh",day,later,100,later.plusSeconds(120));
        assertThat(repository.finish(TENANT,key,old.token(),mapper.writeValueAsString(answer("迟到旧答案")),true,later,null)).isFalse();
        assertThat(repository.finish(TENANT,key,fresh.token(),mapper.writeValueAsString(answer("新答案")),true,later,null)).isTrue();
        assertThat(repository.finish(TENANT,key,old.token(),mapper.writeValueAsString(answer("迟到旧答案")),true,later,null)).isFalse();
        assertThat(service.resolve(GLOBAL,id).locationAddress()).isEqualTo("北京市新答案");
        verify(geocoder,never()).resolve(any(),any());
    }

    @Test void rechecksDeletionAfterNetworkAndValidatesEntireBatchBeforeSpendingQuota() {
        UUID one=submission(TENANT,SALES,STORE,"北京",116),two=submission(TENANT,SALES,STORE,"深圳",117);
        var beijing=new AdminScope(UUID.randomUUID(),"beijing-admin","北京");
        assertThatThrownBy(() -> service.resolveBatch(beijing,List.of(one,two))).isInstanceOf(TemporaryCheckinException.class);
        assertThatThrownBy(() -> service.resolveBatch(GLOBAL,List.of(one,one))).isInstanceOf(TemporaryCheckinException.class);
        verify(geocoder,never()).resolve(any(),any());
        when(geocoder.resolve(any(),any())).thenAnswer(ignored -> {
            jdbc.update("UPDATE temp_sales_checkin_submission SET deletion_state='PENDING' WHERE id=?",bin(one));
            return answer("应不写入删除中的记录");
        });
        assertThatThrownBy(() -> service.resolve(GLOBAL,one)).isInstanceOf(TemporaryCheckinException.class);
        assertThat(jdbc.queryForObject("SELECT location_address FROM temp_sales_checkin_submission WHERE id=?",String.class,bin(one))).isNull();
    }

    private TemporaryCheckinAddressService newService(UUID tenant,int limit) {
        var properties=new TemporaryCheckinProperties(); properties.setTenantId(tenant.toString());
        return new TemporaryCheckinAddressService(properties,repository,geocoder,mapper,clock,limit,15);
    }
    private java.util.Map<String,Object> evidence(UUID id) {
        return jdbc.queryForMap("SELECT longitude,latitude,accuracy_meters,location_captured_at,location_quality,location_verification_status,"
                +"risk_flags_json,risk_level,store_longitude_snapshot,store_latitude_snapshot,submitted_at FROM temp_sales_checkin_submission WHERE id=?",bin(id));
    }
    private java.util.Map<String,Object> addressEvidence(UUID id) {
        return jdbc.queryForMap("SELECT location_address,location_formatted_address,longitude,latitude,accuracy_meters,location_captured_at,"
                +"location_quality,location_verification_status,risk_flags_json,risk_level,submitted_at,address_source,address_resolved_at,"
                +"address_coordinate_hash,address_conversion_version,amap_longitude,amap_latitude,geocode_status,updated_at "
                +"FROM temp_sales_checkin_submission WHERE id=?",bin(id));
    }
    private void assertAddressProjections(UUID id,String expected) {
        var submissions=new TemporaryCheckinRepository(jdbc);
        assertThat(repository.find(TENANT,id,"北京").address()).isEqualTo(expected);
        assertThat(submissions.findAdminSubmission(TENANT,id,"北京").orElseThrow().locationAddress()).isEqualTo(expected);
        assertThat(submissions.findAdminSubmissions(TENANT,null,null,"北京",SALES,"SUBMITTED",null,null,0,20))
                .filteredOn(row->row.id().equals(id)).extracting(TemporaryCheckinRepository.AdminSubmissionRow::locationAddress).containsExactly(expected);
        assertThat(submissions.export(TENANT,null,null,"北京",SALES,"SUBMITTED",null,null,20))
                .filteredOn(row->row.id().equals(id)).extracting(TemporaryCheckinRepository.ExportRow::locationAddress).containsExactly(expected);
        assertThat(submissions.exportForWorkbook(TENANT,null,null,"北京",SALES,"SUBMITTED",null,null,20,TemporaryCheckinRepository.AdminReadOptions.defaults()))
                .filteredOn(row->row.id().equals(id)).extracting(TemporaryCheckinRepository.ExportRow::locationAddress).containsExactly(expected);
    }
    private GeocodeResult answer(String address) {
        return new GeocodeResult("RESOLVED",address,"北京市"+address,"110101","北京市","北京市","东城区","测试街道",
                new BigDecimal("116.006000"),new BigDecimal("39.002000"),null);
    }
    private void seed(UUID tenant,UUID sales,UUID store) {
        jdbc.update("INSERT INTO temp_sales_checkin_salesperson(id,tenant_id,name,city,employment_status,status,created_at,updated_at)"
                +" VALUES (?,?,?,'北京','在职','ACTIVE',?,?)",bin(sales),bin(tenant),"示例销售",Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store(id,tenant_id,client_store_id,city,attribute,name,operating_status,
                    contact_name,area_range,facility_count,business_types_json,intended_businesses_json,cooperation_intent,
                    tags_json,status,created_at,updated_at,location_address,longitude,latitude)
                VALUES (?,?,?,'北京','台球','示例门店','营业中','示例联系人','100平','10',JSON_ARRAY(),JSON_ARRAY(),
                    '待沟通',JSON_ARRAY(),'ACTIVE',?,?,'这个门店地址不能替代设备位置',120,30)
                """,bin(store),bin(tenant),bin(UUID.randomUUID()),Timestamp.from(NOW),Timestamp.from(NOW));
    }
    private UUID submission(UUID tenant,UUID sales,UUID store,String city,int longitude) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO temp_sales_checkin_submission(id,tenant_id,client_submission_id,submission_key_hash,status,city,
                    salesperson_id,salesperson_name_snapshot,store_id,store_name_snapshot,customer_name,visit_result,
                    longitude,latitude,accuracy_meters,location_captured_at,privacy_accepted,created_at,updated_at,submitted_at,
                    location_quality,location_verification_status,risk_level,risk_flags_json,store_longitude_snapshot,store_latitude_snapshot,
                    storefront_photo_object_key,storefront_photo_content_type,storefront_photo_size_bytes,storefront_photo_sha256,storefront_photo_original_filename)
                VALUES (?,?,?,?,'SUBMITTED',?,?,'示例销售',?,'示例门店','示例客户','示例拜访',?,39,900,?,0,?,?,?,
                    'STALE','UNVERIFIED','MEDIUM',JSON_ARRAY('LOCATION_STALE'),120,30,
                    'fixture/photo.jpg','image/jpeg',4,?,'示例照片.jpg')
                """,bin(id),bin(tenant),bin(UUID.randomUUID()),"a".repeat(64),city,bin(sales),bin(store),longitude,
                Timestamp.from(NOW.minusSeconds(86400)),Timestamp.from(NOW),Timestamp.from(NOW),Timestamp.from(NOW),"b".repeat(64));
        return id;
    }
    private TemporaryCheckinAdminPrincipal principal(String city) {
        return new TemporaryCheckinAdminPrincipal(UUID.randomUUID(),UUID.randomUUID(),"address-admin","地址测试管理员",
                city==null ? "GLOBAL_ADMIN" : "CITY_ADMIN",city==null ? null : UUID.randomUUID(),city,false,"fixture-csrf");
    }
    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request,String session) {
        return request.cookie(new Cookie(authProperties.getCookieName(),session)).header(TemporaryCheckinAdminAuthenticationFilter.CSRF_HEADER,"fixture-csrf");
    }
    private static byte[] bin(UUID id) { return SalesUuidCodec.encode(id); }
    private static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now=now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
