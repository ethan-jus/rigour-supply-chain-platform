package com.rigour.order.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.rigour.order.application.port.out.ErpOrderRepairCatalog.Query;
import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.exception.BusinessException;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 外部引用验证只允许签名GET，证明ERP模糊列表、截断、默认SKU与字典故障均不能误放行。 */
class HttpOrderRepairReferencesTest {
    private static final String TENANT = "11111111-1111-4111-8111-111111111111";
    private static final String LIST = "https://erp.test/api/v1/erp/product-management/products?begin=0&step=200&productCode=P1";

    @Test void signedReadsRequireExactCodeAndDoNotChooseDefaultSku() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(LIST)).andExpect(method(HttpMethod.GET))
                .andExpect(header(RequestHeaders.TENANT_ID, TENANT))
                .andExpect(header(RequestHeaders.PERMISSIONS, "erp:product:read"))
                .andExpect(header(RequestHeaders.SESSION_VERSION, "0"))
                .andExpect(header(RequestHeaders.USER_SECURITY_VERSION, "0"))
                .andExpect(header(RequestHeaders.CONTEXT_KEY_ID, "v1"))
                .andRespond(withSuccess("""
                        {"code":"OK","data":{"total":2,"begin":0,"step":200,"items":[
                        {"id":1,"productCode":"P1","productName":"Exact","revision":1},
                        {"id":2,"productCode":"P10","productName":"Similar","revision":1}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://erp.test/api/v1/erp/product-management/products/1"))
                .andRespond(withSuccess("""
                        {"code":"OK","data":{"id":1,"productCode":"P1","productName":"Exact","revision":1,"unitCode":"BUCKET",
                        "variants":[{"id":11,"variantCode":"S1","specificationSnapshot":"A","defaultFlag":true,"revision":1},
                        {"id":12,"variantCode":"S2","specificationSnapshot":"B","revision":1}]}}
                        """, MediaType.APPLICATION_JSON));
        var client = new HttpErpOrderRepairCatalog(builder, signer(), "https://erp.test");
        assertThat(client.candidates(TENANT, new Query("P1", null, null, null))).hasSize(2);
        server.verify();
    }

    @Test void rejectsTruncatedResultsInsteadOfReportingUnique() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(LIST)).andRespond(withSuccess("""
                {"code":"OK","data":{"total":201,"begin":0,"step":200,"items":[{"id":1,"productCode":"P1"}]}}
                """, MediaType.APPLICATION_JSON));
        var client = new HttpErpOrderRepairCatalog(builder, signer(), "https://erp.test");
        assertThatThrownBy(() -> client.candidates(TENANT, new Query("P1", null, "S1", null))).isInstanceOf(BusinessException.class);
        server.verify();
    }

    @Test void rejectsDeletedOrUnavailableErpDetail() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(LIST)).andRespond(withSuccess("""
                {"code":"OK","data":{"total":1,"begin":0,"step":200,"items":[{"id":1,"productCode":"P1","revision":1}]}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://erp.test/api/v1/erp/product-management/products/1")).andRespond(withResourceNotFound());
        var client = new HttpErpOrderRepairCatalog(builder, signer(), "https://erp.test");
        assertThatThrownBy(() -> client.candidates(TENANT, new Query("P1", null, "S1", null))).isInstanceOf(BusinessException.class);
        server.verify();
    }

    @Test void dictionaryIsReadOnlyTenantScopedAndFiltersWrongDictionary() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://settings.test/api/v1/business-settings/dictionaries/effective?dictionaryCode=PRODUCT_UNIT"))
                .andExpect(method(HttpMethod.GET)).andExpect(header(RequestHeaders.TENANT_ID, TENANT))
                .andExpect(header(RequestHeaders.PERMISSIONS, "business-settings:dict:read"))
                .andExpect(header(RequestHeaders.SESSION_VERSION, "0"))
                .andRespond(withSuccess("""
                        {"code":"OK","data":{"dictionary":{"dictionaryCode":"PRODUCT_UNIT","revision":1},"items":[
                        {"dictionaryCode":"PRODUCT_UNIT","dictionaryItemCode":"BOX","dictionaryItemLevel":1,"ordinal":0,"revision":1},
                        {"dictionaryCode":"WRONG","dictionaryItemCode":"INVALID","dictionaryItemLevel":1,"ordinal":0,"revision":1}]}}
                        """, MediaType.APPLICATION_JSON));
        assertThat(new HttpOrderRepairUnitDictionary(builder, signer(), "https://settings.test").validUnits(TENANT)).containsExactly("BOX");
        server.verify();
    }

    @Test void dictionaryFailureCannotApproveUnit() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://settings.test/api/v1/business-settings/dictionaries/effective?dictionaryCode=PRODUCT_UNIT"))
                .andRespond(withServerError());
        var client = new HttpOrderRepairUnitDictionary(builder, signer(), "https://settings.test");
        assertThatThrownBy(() -> client.validUnits(TENANT)).isInstanceOf(BusinessException.class);
        server.verify();
    }

    private static TrustedContextSigner signer() {
        var properties = new ContextTrustProperties();
        properties.setActiveKeyId("v1");
        properties.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
