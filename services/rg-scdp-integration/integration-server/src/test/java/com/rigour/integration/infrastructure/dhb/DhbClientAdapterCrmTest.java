package com.rigour.integration.infrastructure.dhb;

import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbClient.Page;
import com.rigour.integration.application.port.out.DhbClient.PageRequest;
import com.rigour.integration.application.port.out.DhbClient.ShippingAddress;
import com.rigour.integration.application.port.out.DhbClient.ShippingAddressQuery;
import com.rigour.integration.application.port.out.DhbClient.Staff;
import com.rigour.integration.application.port.out.DhbClient.StaffQuery;
import com.rigour.integration.application.port.out.DhbClient.TimeWindow;
import com.rigour.integration.infrastructure.config.DhbClientProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DhbClientAdapterCrmTest {

    private static final DhbClient.Connector CONNECTOR = new DhbClient.Connector(
            UUID.fromString("00000000-0000-0000-0000-000000000101"),
            UUID.fromString("00000000-0000-0000-0000-000000000102"),
            "https://api.test/erp", "env://DHB_CRM_TEST");

    @Test
    void preservesAllDictionarySourceFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectToken(server);
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getClientTypeList","v":{"sKey":"opaque-token"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"rData":[{"typeID":"TYPE-1","typeName":"VIP","erpID":"ERP-T1","future_field":"kept"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getArea","v":{"sKey":"opaque-token"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"rData":[{"AreaID":"AREA-1","AreaName":"华东","ERPID":"ERP-A1","parentID":"AREA-ROOT","future_field":7}]}
                        """, MediaType.APPLICATION_JSON));

        DhbClientAdapter client = client(builder);

        assertThat(client.getCustomerTypes(CONNECTOR).getFirst())
                .satisfies(type -> {
                    assertThat(type.sourceId()).isEqualTo("TYPE-1");
                    assertThat(type.name()).isEqualTo("VIP");
                    assertThat(type.attributes()).containsEntry("future_field", "kept");
                });
        assertThat(client.getCustomerAreas(CONNECTOR).getFirst())
                .satisfies(area -> {
                    assertThat(area.sourceId()).isEqualTo("AREA-1");
                    assertThat(area.name()).isEqualTo("华东");
                    assertThat(area.parentSourceId()).isEqualTo("AREA-ROOT");
                    assertThat(area.attributes()).containsEntry("future_field", 7);
                });
        server.verify();
    }

    @Test
    void doesNotTreatParentFieldNameAsAreaParentCode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectToken(server);
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getArea","v":{"sKey":"opaque-token"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"rData":[{"AreaID":"AREA-1","AreaName":"全国","parentId":"parentId"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client(builder).getCustomerAreas(CONNECTOR).getFirst().parentSourceId()).isNull();
        server.verify();
    }

    @Test
    void mapsShippingAddressAndNestedStaffPagesWithoutDroppingProviderFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectToken(server);
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getShippingAddressList","v":{"sKey":"opaque-token","begin":0,"step":500,
                        "addressAbout":"仓库","clientGuid":"CLIENT-GUID-1","isDefault":"T",
                        "startTime":"2026-08-01 08:00:00","endTime":"2026-08-01 09:00:00"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"rTotal":1,"rData":[{
                          "addressId":"ADDR-1","addressGuid":"ADDR-GUID-1","clientId":"CLIENT-1",
                          "clientGuid":"CLIENT-GUID-1","clientNum":"C-001","consignee":"上海仓",
                          "contact":"张三","phone":"13800000000","address":"上海市浦东新区",
                          "isDefault":"T","updateDate":"2026-08-01 08:30:00",
                          "addressDetail":"世纪大道1号","areaName":"上海","future_field":{"x":1}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/erp"))
                .andExpect(content().json("""
                        {"f":"getStaffList","v":{"sKey":"opaque-token","page":1,"page_size":500,
                        "staff_type":"salesman","status":"T","keywords":"张",
                        "create_date_start":"2026-08-01 08:00:00","create_date_end":"2026-08-01 09:00:00",
                        "update_date_start":"2026-08-02 08:00:00","update_date_end":"2026-08-02 09:00:00"}}
                        """))
                .andRespond(withSuccess("""
                        {"rStatus":100,"rData":{"page_size":500,"page":1,"total_page":1,"total":1,"data":[{
                          "staff_id":"STAFF-1","staff_type":"salesman","accounts_name":"zhangsan",
                          "staff_name":"张三","title":"销售经理","branch_name":"华东区",
                          "accounts_mobile":"13800000000","about":"备注","role":"销售","invite_code":"INV-1",
                          "mobile":"13900000000","email":"zhang@example.com","qq":"123456",
                          "create_date":"2026-08-01 08:15:00","update_date":"2026-08-02 08:30:00",
                          "group_id_str":"G-1"}]}}
                        """, MediaType.APPLICATION_JSON));

        DhbClientAdapter client = client(builder);
        Page<ShippingAddress> addresses = client.getShippingAddresses(CONNECTOR,
                new ShippingAddressQuery(new PageRequest(0, 500), "仓库", "CLIENT-GUID-1", "T",
                        new TimeWindow(Instant.parse("2026-08-01T00:00:00Z"),
                                Instant.parse("2026-08-01T01:00:00Z"))));
        Page<Staff> staff = client.getStaff(CONNECTOR,
                new StaffQuery(new PageRequest(0, 500), "salesman", "T", "张",
                        new TimeWindow(Instant.parse("2026-08-01T00:00:00Z"),
                                Instant.parse("2026-08-01T01:00:00Z")),
                        new TimeWindow(Instant.parse("2026-08-02T00:00:00Z"),
                                Instant.parse("2026-08-02T01:00:00Z"))));

        assertThat(addresses.total()).isEqualTo(1);
        assertThat(addresses.items().getFirst()).satisfies(address -> {
            assertThat(address.sourceId()).isEqualTo("ADDR-GUID-1");
            assertThat(address.defaultAddress()).isTrue();
            assertThat(address.updatedAt()).isEqualTo(Instant.parse("2026-08-01T00:30:00Z"));
            assertThat(address.attributes()).containsKey("future_field");
        });
        assertThat(staff.total()).isEqualTo(1);
        assertThat(staff.items().getFirst()).satisfies(item -> {
            assertThat(item.sourceId()).isEqualTo("STAFF-1");
            assertThat(item.staffName()).isEqualTo("张三");
            assertThat(item.attributes()).containsEntry("group_id_str", "G-1");
        });
        server.verify();
    }

    private static void expectToken(MockRestServiceServer server) {
        server.expect(requestTo("https://api.test/erp"))
                .andRespond(withSuccess("""
                        {"rStatus":100,"rData":{"token":"opaque-token","expires_in":3600}}
                        """, MediaType.APPLICATION_JSON));
    }

    private static DhbClientAdapter client(RestClient.Builder builder) {
        DhbClientProperties properties = new DhbClientProperties();
        properties.setMaxAttempts(1);
        properties.setRequestsPerSecond(1000);
        properties.setRateLimitBurst(10);
        properties.setTokenSafetyWindow(Duration.ofSeconds(1));
        return new DhbClientAdapter(builder.build(),
                ignored -> new DhbSecretResolver.Credentials("fixture-account", "fixture-password"),
                properties);
    }
}
