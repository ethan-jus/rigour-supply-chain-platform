package com.rigour.analytics.infrastructure.integration;

import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class HttpBiProductImagesTest {
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpBiProductImages source = new HttpBiProductImages(builder, signer(), "http://erp.test");
    private static final String URL = "http://erp.test/api/v1/erp/product-management/products?begin=0&step=200&productIds=101,102";

    @Test void batchesOnlyRequestedIdsAndUsesTenantBoundServiceRead() {
        server.expect(requestTo(URL))
                .andExpect(header(RequestHeaders.TENANT_ID, "T"))
                .andExpect(header(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE"))
                .andExpect(header(RequestHeaders.PERMISSIONS, "erp:product:read"))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andExpect(request -> assertThat(request.getHeaders().getFirst(RequestHeaders.CONTEXT_SIGNATURE)).isNotBlank())
                .andRespond(withSuccess("""
                    {"code":"OK","data":{"items":[
                    {"id":"101","mainImageUrl":"https://img.test/101.png"},
                    {"id":"102","mainImageUrl":null},
                    {"id":"999","mainImageUrl":"https://img.test/999.png"}]}}
                    """, MediaType.APPLICATION_JSON));
        assertThat(source.urls("T", List.of("101", "102", "101", "UNKNOWN")))
                .containsExactlyEntriesOf(Map.of("101", "https://img.test/101.png"));
        server.verify();
    }

    @Test void unavailableImagesDoNotFailDashboard() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(source.urls("T", List.of("101", "102"))).isEmpty();
        server.verify();
    }

    @Test void noActualProductIdsNeverQueriesEntireCatalog() {
        assertThat(source.urls("T", List.of("UNKNOWN"))).isEmpty();
        server.verify();
    }

    private static TrustedContextSigner signer() {
        var properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
