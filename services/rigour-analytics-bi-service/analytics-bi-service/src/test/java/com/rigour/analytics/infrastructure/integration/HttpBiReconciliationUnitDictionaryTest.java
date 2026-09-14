package com.rigour.analytics.infrastructure.integration;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.exception.BusinessException;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpBiReconciliationUnitDictionaryTest {
    private static final String URL="http://settings.test/api/v1/business-settings/dictionaries/resolve?dictionaryCode=PRODUCT_UNIT";
    private final RestClient.Builder builder=RestClient.builder();
    private final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    private final HttpBiReconciliationUnitDictionary source=new HttpBiReconciliationUnitDictionary(builder,signer(),"http://settings.test");
    private final UUID tenant=UUID.randomUUID(), user=UUID.randomUUID();
    private final CallerIdentity actor=new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,
            Set.of("role"),Set.of("analytics:dashboard:read","analytics:reconciliation:write"));

    @Test void getUsesTenantSignedLeastPrivilegeServiceIdentityAndRealLabels() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header(RequestHeaders.PRINCIPAL_SCOPE,"SERVICE"))
                .andExpect(header(RequestHeaders.TENANT_ID,tenant.toString()))
                .andExpect(header(RequestHeaders.PERMISSIONS,"business-settings:dict:read"))
                .andExpect(headerDoesNotExist(RequestHeaders.USER_ID))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(request->assertThat(request.getHeaders().getFirst(RequestHeaders.CONTEXT_SIGNATURE)).isNotBlank())
                .andRespond(withSuccess("""
                        {"code":"OK","data":{"dictionary":{"dictionaryCode":"PRODUCT_UNIT"},"items":[
                        {"dictionaryCode":"PRODUCT_UNIT","dictionaryItemCode":"BUCKET","dictionaryItemName":"桶"},
                        {"dictionaryCode":"PRODUCT_UNIT","dictionaryItemCode":"BOX","dictionaryItemName":"箱"}]}}
                        """,MediaType.APPLICATION_JSON));
        assertThat(source.productUnits(actor)).containsExactlyInAnyOrderEntriesOf(Map.of("BUCKET","桶","BOX","箱"));
        server.verify();
    }

    @ParameterizedTest @ValueSource(strings={"{}","{\"code\":\"OK\",\"data\":{\"dictionary\":{\"dictionaryCode\":\"OTHER\"},\"items\":[]}}",
            "{\"code\":\"OK\",\"data\":{\"dictionary\":{\"dictionaryCode\":\"PRODUCT_UNIT\"},\"items\":[]}}",
            "{\"code\":\"OK\",\"data\":{\"dictionary\":{\"dictionaryCode\":\"PRODUCT_UNIT\"},\"items\":[{\"dictionaryItemCode\":\"B\",\"dictionaryItemName\":\"桶\"}]}}"})
    void malformedOrWrongDictionaryNeverBecomesEmptySuccessfulMapping(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        var error=catchThrowableOfType(()->source.productUnits(actor),BusinessException.class);
        assertThat(error.getDetails().getFirst().reason()).isEqualTo("BI_PRODUCT_UNIT_DICTIONARY_UNAVAILABLE");
        assertThat(error.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        server.verify();
    }

    @Test void duplicateCodesFailButDuplicateNamesArePreservedForAmbiguityDetection() {
        String row="{\"dictionaryCode\":\"PRODUCT_UNIT\",\"dictionaryItemCode\":\"BOX\",\"dictionaryItemName\":\"箱\"}";
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"code\":\"OK\",\"data\":{\"dictionary\":{\"dictionaryCode\":\"PRODUCT_UNIT\"},\"items\":["+row+","+row+"]}}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->source.productUnits(actor)).isInstanceOf(BusinessException.class);
        server.verify();
    }

    @Test void upstreamErrorIsSafeAndDoesNotInventMapping() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN).body("private SQL account token"));
        var error=catchThrowableOfType(()->source.productUnits(actor),BusinessException.class);
        assertThat(error.getMessage()).contains("字典").doesNotContain("private","account","token");
        assertThat(error.getCause()).isNull();
        server.verify();
    }
    @Test void missingTenantFailsBeforeHttpCall() {
        assertThatThrownBy(()->source.productUnits(null)).isInstanceOf(BusinessException.class);
        server.verify();
    }
    private static TrustedContextSigner signer() {
        var properties=new ContextTrustProperties();
        properties.setKeysBase64(Map.of("v1",Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
