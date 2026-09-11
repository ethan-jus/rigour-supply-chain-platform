package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

/** 真实 MySQL 全结果排序经同一 HTTP 协议进入列表、CSV、Excel，避免仅对当前分页重新排序。 */
@Testcontainers(disabledWithoutDocker=true)
class TemporaryCheckinCombinedSortIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
            .withDatabaseName("rigour_sales_work").withUsername("combined_sort_test").withPassword("synthetic_test_password");
    static final UUID TENANT=new UUID(1,1),OTHER=new UUID(1,2),A=new UUID(2,1),B=new UUID(2,2),C=new UUID(2,3),OA=new UUID(2,4),STORE=new UUID(3,1),OTHER_STORE=new UUID(3,2);
    static final Instant NOW=Instant.parse("2026-09-08T02:00:00Z");
    JdbcTemplate jdbc;MockMvc mvc;TemporaryCheckinRiskService risk;int sequence;
    final ObjectMapper json=new ObjectMapper();

    @BeforeAll static void migrate() {Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).load().migrate();}
    @BeforeEach void setup() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl()+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(source);sequence=0;
        for(String suffix:List.of("review_member","review","assignment","audio","device"))jdbc.update("DELETE FROM temp_sales_checkin_risk_"+suffix);
        jdbc.update("DELETE FROM temp_sales_checkin_submission");jdbc.update("DELETE FROM temp_sales_checkin_store");jdbc.update("DELETE FROM temp_sales_checkin_salesperson");
        for(UUID person:List.of(A,B,C))person(TENANT,person);
        person(OTHER,OA);store(TENANT,STORE);store(OTHER,OTHER_STORE);
        var properties=new TemporaryCheckinProperties();properties.setTenantId(TENANT.toString());
        var catalog=mock(TemporaryCheckinAdminAuthRepository.class);when(catalog.listActiveCities(TENANT)).thenReturn(List.of("北京","深圳"));
        var beans=new DefaultListableBeanFactory();var transactionManager=new DataSourceTransactionManager(source);
        var repository=new TemporaryCheckinRepository(jdbc);var evidence=new TemporaryCheckinEvidenceRepository(jdbc);
        var service=new TemporaryCheckinService(repository,evidence,new TemporaryCheckinDerivativeRepository(jdbc),null,catalog,properties,
                null,null,null,null,null,null,json,null,new TransactionTemplate(transactionManager),Clock.fixed(NOW,ZoneOffset.UTC),
                beans.getBeanProvider(TemporaryCheckinAiClient.class),beans.getBeanProvider(org.springframework.boot.servlet.autoconfigure.MultipartProperties.class),null);
        risk=new TemporaryCheckinRiskService(new TemporaryCheckinRiskRepository(jdbc),properties,Clock.fixed(NOW,ZoneOffset.UTC),transactionManager);
        var workbook=new TemporaryCheckinWorkbookService(service,repository,new TemporaryCheckinStatisticsRepository(jdbc),evidence,
                new TemporaryCheckinWorkbookWriter(),properties,risk);
        mvc=MockMvcBuilders.standaloneSetup(new TemporaryCheckinAdminController(service,new TemporaryCheckinAdminAccessPolicy()),
                new TemporaryCheckinWorkbookController(workbook,new TemporaryCheckinAdminAccessPolicy()))
                .setControllerAdvice(new TemporaryCheckinExceptionHandler()).build();
    }

    @ParameterizedTest @CsvSource({"asc,asc","asc,desc","desc,asc","desc,desc"})
    void salesThenTimeGroupsSameNamedAccountsAcrossPagesAndBothExports(String salesDirection,String timeDirection) throws Exception {
        var records=List.of(visit(TENANT,A,"北京","Same",30,null),visit(TENANT,B,"北京","Same",20,null),
                visit(TENANT,A,"北京","Same",10,null),visit(TENANT,B,"北京","Same",40,null),
                visit(TENANT,A,"北京","Same",30,null),visit(TENANT,C,"北京","Zulu",0,null));
        visit(OTHER,OA,"北京","Hidden",-100,null);
        var deleted=visit(TENANT,A,"北京","Before",-100,null);
        // 完成删除会物理移除记录；PENDING 仍是未完成任务，不能在排序测试中当作已删除。
        jdbc.update("DELETE FROM temp_sales_checkin_submission WHERE tenant_id=? AND id=?",bin(TENANT),bin(deleted.id()));
        Comparator<Row> people=Comparator.comparing(Row::name).thenComparing(row->row.person().toString());
        Comparator<Row> times=Comparator.comparing(Row::at).thenComparing(row->row.id().toString());
        if(salesDirection.equals("desc"))people=people.reversed();if(timeDirection.equals("desc"))times=times.reversed();
        var expected=records.stream().sorted(people.thenComparing(times)).map(Row::id).toList();
        assertAllOutputs(expected,List.of("salespersonName:"+salesDirection,"completedAt:"+timeDirection),null);
    }

    @Test void mixedCityAndTimeDirectionsPreserveAllPrioritiesAndCityScope() throws Exception {
        Row beijingLater=visit(TENANT,A,"北京","Same",20,null),shenzhenLater=visit(TENANT,A,"深圳","Same",30,null),
                beijingEarlier=visit(TENANT,A,"北京","Same",0,null),shenzhenEarlier=visit(TENANT,A,"深圳","Same",10,null);
        var sort=List.of("cityName:desc","salespersonName:asc","completedAt:asc");
        // MySQL 中文排序下深圳在北京之后；方向降序先深圳，各城市内部再按时间升序。
        assertAllOutputs(List.of(shenzhenEarlier.id(),shenzhenLater.id(),beijingEarlier.id(),beijingLater.id()),sort,null);
        assertAllOutputs(List.of(beijingEarlier.id(),beijingLater.id()),sort,"北京");
    }

    @Test void secondaryRiskSortLoadsAllAuthorizedAssociationsBeforePaging() throws Exception {
        Row small=visit(TENANT,A,"北京","Same",0,"c".repeat(64));
        Row largeLater=visit(TENANT,A,"北京","Same",20,"b".repeat(64));
        Row largeEarlier=visit(TENANT,B,"北京","Same",10,"b".repeat(64));
        // 当前销售过滤不可缩小关联总数；另租户相同设备也不能增加数量。
        visit(OTHER,OA,"北京","Hidden",5,"c".repeat(64));risk.ensureRegistry();
        assertAllOutputs(List.of(largeEarlier.id(),largeLater.id(),small.id()),
                List.of("cityName:asc","deviceVisitCount:desc","completedAt:asc"),null);
    }

    @Test void selectingSalesAloneDefaultsToNewestFirstWithinEachRealAccount() throws Exception {
        Row older=visit(TENANT,A,"北京","Same",0,null),newer=visit(TENANT,A,"北京","Same",10,null),
                otherOlder=visit(TENANT,B,"北京","Same",5,null),otherNewer=visit(TENANT,B,"北京","Same",15,null);
        assertAllOutputs(List.of(newer.id(),older.id(),otherNewer.id(),otherOlder.id()),List.of("salespersonName:asc"),null);
    }

    @Test void rejectsBadSortsOnEveryEndpointAndRetainsLegacySingleSort() throws Exception {
        Row later=visit(TENANT,A,"北京","Same",20,null),earlier=visit(TENANT,A,"北京","Same",0,null);
        var old=mvc.perform(get("/sales-checkin/admin/api/v1/submissions").with(admin(null)).param("sortBy","completedAt").param("sortDirection","asc"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(old).get("items").get(0).get("id").asText()).isEqualTo(earlier.id().toString());
        List<List<String>> invalid=List.of(List.of(""),List.of("completedAt:"),List.of("id:asc"),List.of("completedAt:ASC"),
                List.of("cityName:asc","cityName:desc"),List.of("completedAt:asc,cityName:desc"),List.of("cityName:desc;DROP TABLE x"),
                List.of("x".repeat(65)),java.util.Collections.nCopies(9,"completedAt:asc"));
        for(String endpoint:List.of("/api/v1/submissions","/export.csv","/export.xlsx")) {
            for(var sorts:invalid)mvc.perform(get("/sales-checkin/admin"+endpoint).with(admin(null)).param("sort",sorts.toArray(String[]::new)))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/sales-checkin/admin"+endpoint).param("sort","salespersonName:asc")).andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM temp_sales_checkin_submission",Long.class)).isEqualTo(2);
    }

    private void assertAllOutputs(List<UUID> expected,List<String> sorts,String scopeCity) throws Exception {
        List<UUID> pages=new ArrayList<>();int pageSize=2;
        for(int page=0;page<(expected.size()+pageSize-1)/pageSize;page++) {
            var result=mvc.perform(get("/sales-checkin/admin/api/v1/submissions").with(admin(scopeCity))
                    .param("sort",sorts.toArray(String[]::new)).param("sortBy","obsolete-ignored").param("sortDirection","ignored")
                    .param("page",Integer.toString(page)).param("size",Integer.toString(pageSize))).andExpect(status().isOk()).andReturn();
            var data=json.readTree(result.getResponse().getContentAsString());assertThat(data.get("totalElements").asLong()).isEqualTo(expected.size());
            for(var item:data.get("items"))pages.add(UUID.fromString(item.get("id").asText()));
        }
        assertThat(pages).containsExactlyElementsOf(expected);
        String csv=mvc.perform(get("/sales-checkin/admin/export.csv").with(admin(scopeCity)).param("sort",sorts.toArray(String[]::new))
                .param("sortBy","obsolete-ignored")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv.lines().skip(1).filter(line->!line.isBlank()).map(line->UUID.fromString(line.split(",",2)[0].replace("\"",""))).toList())
                .containsExactlyElementsOf(expected);
        byte[] xlsx=mvc.perform(get("/sales-checkin/admin/export.xlsx").with(admin(scopeCity)).param("sort",sorts.toArray(String[]::new))
                .param("sortDirection","ignored")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet=book.getSheet("打卡明细");List<UUID> ids=new ArrayList<>();
            for(int row=5;row<=sheet.getLastRowNum();row++)ids.add(UUID.fromString(sheet.getRow(row).getCell(23).getStringCellValue()));
            assertThat(ids).containsExactlyElementsOf(expected);
        }
    }
    private Row visit(UUID tenant,UUID person,String city,String name,long offset,String device) {
        UUID id=new UUID(4,++sequence);Instant at=NOW.plusSeconds(offset);
        jdbc.update("""
                INSERT INTO temp_sales_checkin_submission(id,tenant_id,client_submission_id,submission_key_hash,status,city,
                    salesperson_id,salesperson_name_snapshot,store_id,store_name_snapshot,customer_name,visit_result,
                    privacy_accepted,created_at,updated_at,submitted_at,device_token_hash,
                    storefront_photo_object_key,storefront_photo_content_type,storefront_photo_size_bytes,storefront_photo_sha256,storefront_photo_original_filename)
                VALUES(?,?,?,?,'SUBMITTED',?,?,?,?,?,'合成客户','组合排序合成数据',0,?,?,?,?,'fixture/photo','image/jpeg',4,?,'合成照片.jpg')
                """,bin(id),bin(tenant),bin(UUID.randomUUID()),"f".repeat(64),city,bin(person),name,bin(tenant.equals(TENANT)?STORE:OTHER_STORE),
                "合成门店"+sequence,utc(at.minusSeconds(30)),utc(at),utc(at),device,"e".repeat(64));
        return new Row(id,person,name,at);
    }
    private void person(UUID tenant,UUID person) {
        // 同租户姓名+归属城市必须唯一；不同城市的同名账号仍可在同一业务城市留下同名拜访快照。
        String identityCity=person.equals(B)?"深圳":person.equals(C)?"广州":"北京";
        jdbc.update("INSERT INTO temp_sales_checkin_salesperson(id,tenant_id,name,city,employment_status,status,created_at,updated_at) VALUES(?,?,'合成销售',?,'在职','ACTIVE',?,?)",
                bin(person),bin(tenant),identityCity,utc(NOW),utc(NOW));
    }
    private void store(UUID tenant,UUID store) {
        jdbc.update("""
                INSERT INTO temp_sales_checkin_store(id,tenant_id,client_store_id,city,attribute,name,operating_status,contact_name,
                    area_range,facility_count,business_types_json,intended_businesses_json,cooperation_intent,tags_json,status,created_at,updated_at)
                VALUES(?,?,?,'北京','台球','合成门店','营业中','合成联系人','100平','10',JSON_ARRAY(),JSON_ARRAY(),'待沟通',JSON_ARRAY(),'ACTIVE',?,?)
                """,bin(store),bin(tenant),bin(UUID.randomUUID()),utc(NOW),utc(NOW));
    }
    private RequestPostProcessor admin(String city) {
        return request->{request.setAttribute(TemporaryCheckinAdminPrincipal.REQUEST_ATTRIBUTE,new TemporaryCheckinAdminPrincipal(
                UUID.randomUUID(),UUID.randomUUID(),"sort-fixture","合成管理员",city==null?"GLOBAL_ADMIN":"CITY_ADMIN",city==null?null:UUID.randomUUID(),city,false,"fixture-csrf"));return request;};
    }
    private record Row(UUID id,UUID person,String name,Instant at) { }
    private static byte[] bin(UUID id) {return SalesUuidCodec.encode(id);}
    private static LocalDateTime utc(Instant at) {return LocalDateTime.ofInstant(at,ZoneOffset.UTC);}
}
