package com.rigour.integration.infrastructure.domain;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.integration.application.port.out.CrmDhbDomainSyncClient;
import com.rigour.merchant.api.v1.model.CustomerSyncJob;
import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class HttpCrmDhbDomainSyncClientTest {
    final UUID connector = UUID.randomUUID();
    final CallerIdentity caller =
            new CallerIdentity(
                    "SERVICE",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    null,
                    UUID.randomUUID(),
                    0,
                    0,
                    0,
                    Set.of(),
                    Set.of("integration:dhb:read"));
    final ObjectMapper json = new ObjectMapper();

    HttpCrmDhbDomainSyncClient client(RestClient.Builder builder) {
        var signer = mock(TrustedContextSigner.class);
        when(signer.sign(anyString(), anyString(), any(), anyMap()))
                .thenReturn(new TrustedContextSigner.SignedContext("test", "1", "signature"));
        return new HttpCrmDhbDomainSyncClient(builder, signer, "http://crm.test");
    }

    MockClientHttpResponse response(String uri, String status) {
        UUID id = UUID.fromString(uri.substring(uri.lastIndexOf('/') + 1));
        var result = "SUCCEEDED".equals(status) ? new SyncResult(id, "SUCCEEDED", List.of()) : null;
        return new MockClientHttpResponse(
                json.writeValueAsBytes(
                        ApiResponse.success(
                                new CustomerSyncJob(
                                        id,
                                        connector,
                                        status,
                                        "客户处理进度",
                                        Instant.now(),
                                        Instant.now(),
                                        result))),
                HttpStatus.OK);
    }

    @Test
    void shortRequestsPollTheOriginalJobAndKeepTrustedHeaders() {
        var methods = new ArrayList<HttpMethod>();
        var paths = new ArrayList<String>();
        var client =
                client(
                        RestClient.builder()
                                .requestInterceptor(
                                        (request, body, execution) -> {
                                            methods.add(request.getMethod());
                                            paths.add(request.getURI().getPath());
                                            assertThat(
                                                            request.getHeaders()
                                                                    .getFirst(
                                                                            com.rigour.shared
                                                                                    .context
                                                                                    .RequestHeaders
                                                                                    .CONTEXT_SIGNATURE))
                                                    .isEqualTo("signature");
                                            return response(
                                                    request.getURI().getPath(),
                                                    methods.size() == 1 ? "RUNNING" : "SUCCEEDED");
                                        }));
        assertThat(
                        client.syncLatestCustomersInBackground(
                                        caller,
                                        connector,
                                        UUID.randomUUID(),
                                        100,
                                        UUID.randomUUID(),
                                        s -> {})
                                .status())
                .isEqualTo("SUCCEEDED");
        assertThat(methods).containsExactly(HttpMethod.POST, HttpMethod.GET);
        assertThat(paths).containsOnly(paths.getFirst());
    }

    @Test
    void gatewayTimeoutRetriesOnlyTheSameIdempotentSubmission() {
        var paths = new ArrayList<String>();
        var client =
                client(
                        RestClient.builder()
                                .requestInterceptor(
                                        (request, body, execution) -> {
                                            paths.add(request.getURI().getPath());
                                            return paths.size() == 1
                                                    ? new MockClientHttpResponse(
                                                            new byte[0], HttpStatus.GATEWAY_TIMEOUT)
                                                    : response(
                                                            request.getURI().getPath(),
                                                            "SUCCEEDED");
                                        }));
        assertThat(
                        client.syncLatestCustomersInBackground(
                                        caller, connector, UUID.randomUUID(), 100, null, s -> {})
                                .status())
                .isEqualTo("SUCCEEDED");
        assertThat(paths).hasSize(2).containsOnly(paths.getFirst());
    }

    @Test
    void persistentBadGatewayIsUnknownRatherThanAConfirmedBusinessFailure() {
        var calls = new AtomicInteger();
        var client =
                client(
                        RestClient.builder()
                                .requestInterceptor(
                                        (request, body, execution) -> {
                                            calls.incrementAndGet();
                                            return new MockClientHttpResponse(
                                                    new byte[0], HttpStatus.BAD_GATEWAY);
                                        }));
        assertThatThrownBy(
                        () ->
                                client.syncLatestCustomersInBackground(
                                        caller, connector, UUID.randomUUID(), 100, null, s -> {}))
                .isInstanceOf(CrmDhbDomainSyncClient.OutcomeUnknown.class);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void permissionDenialIsDefinitiveAndNotRetried() {
        var calls = new AtomicInteger();
        var client =
                client(
                        RestClient.builder()
                                .requestInterceptor(
                                        (request, body, execution) -> {
                                            calls.incrementAndGet();
                                            return new MockClientHttpResponse(
                                                    new byte[0], HttpStatus.FORBIDDEN);
                                        }));
        assertThatThrownBy(
                        () ->
                                client.syncLatestCustomersInBackground(
                                        caller, connector, UUID.randomUUID(), 100, null, s -> {}))
                .isInstanceOf(RestClientResponseException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
}
