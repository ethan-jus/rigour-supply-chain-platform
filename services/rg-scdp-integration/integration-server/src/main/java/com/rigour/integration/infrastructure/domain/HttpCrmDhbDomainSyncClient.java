package com.rigour.integration.infrastructure.domain;

import com.rigour.integration.application.port.out.CrmDhbDomainSyncClient;
import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.merchant.api.v1.model.CustomerSyncJob;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ApiResponse;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.type.TypeReference;

/** Integration 到 CRM 内部订货宝同步接口的 HTTP 客户端。 */
public final class HttpCrmDhbDomainSyncClient implements CrmDhbDomainSyncClient {
    private static final TypeReference<ApiResponse<SyncResult>> SYNC_RESULT_RESPONSE =
            new TypeReference<>() { };

    private final RestClient restClient;
    private final RestClient jobClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;

    public HttpCrmDhbDomainSyncClient(RestClient.Builder builder,
                                      TrustedContextSigner signer,
                                      String baseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        var shortRequests = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        shortRequests.setConnectTimeout(java.time.Duration.ofSeconds(3));
        shortRequests.setReadTimeout(java.time.Duration.ofSeconds(10));
        this.jobClient = builder.clone().requestFactory(shortRequests).build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = SignedDomainRequest.baseUri(baseUrl, "CRM");
    }

    @Override
    public SyncResult syncLatestCustomersInBackground(CallerIdentity caller, UUID connectorId,
            UUID sourceTaskId, int maxPages, UUID initiatedBy, java.util.function.Consumer<String> progress) {
        UUID requestId = UUID.randomUUID();
        var command = new SyncCommand(connectorId,sourceTaskId,maxPages,null,null,"CUSTOMER",true,initiatedBy);
        CustomerSyncJob job;
        try {
            job = customerJob(caller,requestId,command);
        } catch (org.springframework.web.client.RestClientException lost) {
            if (lost instanceof org.springframework.web.client.RestClientResponseException response
                    && response.getStatusCode().is4xxClientError()
                    && response.getStatusCode().value() != 408
                    && response.getStatusCode().value() != 429) throw lost;
            // 同一个请求编号重试提交由 CRM 持久化去重，不能生成第二个任务。
            try { job = customerJob(caller,requestId,command); }
            catch (RuntimeException unavailable) { throw new OutcomeUnknown("无法确认 CRM 任务提交结果",unavailable); }
        }
        int errors = 0;
        String lastStage = null;
        while (true) {
            if (!Objects.equals(lastStage,job.stage())) { progress.accept(job.stage()); lastStage=job.stage(); }
            if ("SUCCEEDED".equals(job.status())) {
                if(job.result()==null) throw new OutcomeUnknown("CRM 未返回任务结果",null);
                return job.result();
            }
            if ("FAILED".equals(job.status())) throw new IllegalStateException(job.stage());
            if ("UNKNOWN".equals(job.status())) throw new OutcomeUnknown(job.stage(),null);
            try { Thread.sleep(2000); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new OutcomeUnknown("等待 CRM 状态被中断，原任务可能仍在运行",interrupted);
            }
            try { job=customerJob(caller,job.jobId(),null); errors=0; }
            catch (RuntimeException unavailable) {
                progress.accept("暂时无法取得 CRM 进度，正在重连；不会重复提交");
                if(++errors>=30) throw new OutcomeUnknown("CRM 状态持续不可达，需核对原任务",unavailable);
            }
        }
    }

    private CustomerSyncJob customerJob(CallerIdentity caller,UUID jobId,SyncCommand command) {
        String method=command==null?"GET":"POST";
        URI uri=UriComponentsBuilder.fromUri(baseUri).path("/internal/v1/crm/dhb/customer-sync-jobs/"+jobId).build().toUri();
        var spec=jobClient.method(org.springframework.http.HttpMethod.valueOf(method)).uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h->SignedDomainRequest.signedHeaders(signer,method,uri,caller).forEach(h::set));
        if(command!=null) spec.body(command);
        ApiResponse<CustomerSyncJob> response=spec.exchange((request,httpResponse)->SignedDomainRequest.readResponse(
                httpResponse,new TypeReference<ApiResponse<CustomerSyncJob>>(){},"CRM后台同步"));
        return SignedDomainRequest.required(response,"CRM");
    }

    @Override
    public SyncResult sync(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
                           int maxPages, Instant from, Instant to) {
        return syncObject(caller, connectorId, sourceTaskId, null, maxPages, from, to);
    }

    @Override
    public SyncResult syncObject(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
                           String objectType, int maxPages, Instant from, Instant to) {
        return request(caller, connectorId, sourceTaskId, objectType, maxPages, from, to, false, null);
    }

    @Override
    public SyncResult syncLatestCustomers(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
            int maxPages) {
        return request(caller, connectorId, sourceTaskId, "CUSTOMER", maxPages, null, null, true, null);
    }

    @Override
    public SyncResult syncLatestCustomers(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
            int maxPages, UUID initiatedBy) {
        return request(caller, connectorId, sourceTaskId, "CUSTOMER", maxPages, null, null, true, initiatedBy);
    }

    private SyncResult request(CallerIdentity caller, UUID connectorId, UUID sourceTaskId,
            String objectType, int maxPages, Instant from, Instant to, boolean incremental, UUID initiatedBy) {
        if (connectorId == null || sourceTaskId == null) {
            throw new IllegalArgumentException("CRM同步connectorId和sourceTaskId不能为空");
        }
        URI uri = UriComponentsBuilder.fromUri(baseUri)
                .path("/internal/v1/crm/dhb/sync")
                .build()
                .encode()
                .toUri();
        ApiResponse<SyncResult> response = restClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(headers -> SignedDomainRequest.signedHeaders(signer, "POST", uri, caller)
                        .forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, SignedDomainRequest.requestId())
                .body(new SyncCommand(connectorId, sourceTaskId, maxPages, from, to, objectType, incremental, initiatedBy))
                .exchange((request, httpResponse) -> SignedDomainRequest.readResponse(
                        httpResponse, SYNC_RESULT_RESPONSE, "CRM订货宝同步"));
        return SignedDomainRequest.required(response, "CRM");
    }

    private record SyncCommand(UUID connectorId, UUID sourceTaskId, Integer maxPages,
                               Instant from, Instant to, String objectType, boolean incremental, UUID initiatedBy) { }
}
