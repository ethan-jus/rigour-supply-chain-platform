package com.rigour.order.application.service.dhb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.api.v1.model.DhbOrderImportResult;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.application.port.out.DhbOrderSyncClient;
import com.rigour.shared.context.ContextTrustProperties;
import com.rigour.shared.context.RequestContextFilter;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DhbOrderSyncServiceTest {
    private static final String TENANT_ID = "019fb000-0000-7000-8000-000000000002";

    @Test
    void callsIntegrationBeforeImportingIntoOrderCenter() throws Exception {
        DhbOrderSyncClient integration = mock(DhbOrderSyncClient.class);
        DhbOrderImportService importer = mock(DhbOrderImportService.class);
        DhbOrderSyncService service = new DhbOrderSyncService(integration, importer);
        UUID connectorId = UUID.fromString("019fb000-0000-7000-8000-000000000010");
        DhbOrderImportBatch batch = new DhbOrderImportBatch(null, null, null, null);
        when(integration.collect(any(), eq(connectorId), any())).thenReturn(new DhbOrderSyncClient.Collected(
                UUID.fromString("019fb000-0000-7000-8000-000000000011"), "ORDER", 8,
                Set.of("ORDER", "ORDER_DETAIL"), batch));
        when(importer.importBatch(TENANT_ID, batch)).thenReturn(new DhbOrderImportResult(2, 1, 1, 2));

        final com.rigour.order.api.v1.model.DhbOrderSyncResult[] result = new com.rigour.order.api.v1.model.DhbOrderSyncResult[1];
        RequestContextFilter filter = new RequestContextFilter(signer());
        filter.doFilter(signedRequest(), new MockHttpServletResponse(),
                (request, response) -> result[0] = service.run(connectorId, new DhbOrderSyncCommand(true, 5)));

        assertThat(result[0].status()).isEqualTo("SUCCEEDED");
        assertThat(result[0].fetched()).isEqualTo(8);
        assertThat(result[0].changed()).isEqualTo(6);
        InOrder ordered = inOrder(integration, importer);
        ordered.verify(integration).collect(any(), eq(connectorId), any());
        ordered.verify(importer).importBatch(TENANT_ID, batch);
    }

    private static MockHttpServletRequest signedRequest() {
        TrustedContextSigner signer = signer();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/orders/dhb/sync-tasks/019fb000-0000-7000-8000-000000000010/run");
        Map<String, String> values = new LinkedHashMap<>();
        values.put(RequestHeaders.PRINCIPAL_SCOPE, "TENANT");
        values.put(RequestHeaders.PRINCIPAL_ID, "019fb000-0000-7000-8000-000000000001");
        values.put(RequestHeaders.USER_ID, "019fb000-0000-7000-8000-000000000001");
        values.put(RequestHeaders.TENANT_ID, TENANT_ID);
        values.put(RequestHeaders.SESSION_ID, "019fb000-0000-7000-8000-000000000003");
        values.put(RequestHeaders.SESSION_VERSION, "2");
        values.put(RequestHeaders.USER_SECURITY_VERSION, "3");
        values.put(RequestHeaders.TENANT_POLICY_VERSION, "4");
        values.put(RequestHeaders.ROLES, "ORDER_OPERATOR");
        values.put(RequestHeaders.PERMISSIONS, "integration:dhb:read,integration:dhb:write,order:read");
        TrustedContextSigner.SignedContext signature = signer.sign(request, values);
        values.forEach(request::addHeader);
        request.addHeader(RequestHeaders.CONTEXT_KEY_ID, signature.keyId());
        request.addHeader(RequestHeaders.CONTEXT_TIMESTAMP, signature.timestamp());
        request.addHeader(RequestHeaders.CONTEXT_SIGNATURE, signature.signature());
        return request;
    }

    private static TrustedContextSigner signer() {
        ContextTrustProperties properties = new ContextTrustProperties();
        properties.setKeysBase64(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new TrustedContextSigner(properties);
    }
}
