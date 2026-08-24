package com.rigour.integration.client;

import com.rigour.integration.api.v1.DhbConnectorLeaseApi;
import com.rigour.integration.api.v1.model.DhbConnectorLeaseModels.LeaseCommand;
import com.rigour.integration.api.v1.model.DhbConnectorLeaseModels.LeaseView;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.sync.SyncConflictClassifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 领域服务复用的订货宝同步任务租约客户端。
 *
 * <p>租约覆盖领域服务的一整轮同步，而不是某一个分页HTTP请求；后台心跳只续租当前令牌，
 * 最终释放也必须精确匹配令牌。获取失败直接保留Integration返回的稳定409错误。</p>
 */
public final class ConnectorSyncLeaseClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ConnectorSyncLeaseClient.class);
    private static final String LEASE_PERMISSION = "integration:dhb:lease";

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final URI baseUri;
    private final String serviceName;
    private final ScheduledExecutorService heartbeatExecutor;

    public ConnectorSyncLeaseClient(RestClient.Builder builder, TrustedContextSigner signer,
                                    String baseUrl, String serviceName) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.baseUri = baseUri(baseUrl);
        if (serviceName == null || serviceName.isBlank()) throw new IllegalArgumentException("serviceName不能为空");
        this.serviceName = serviceName.strip();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "connector-lease-" + this.serviceName);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 在同一连接器任务租约内执行同步；业务异常原样抛出，释放失败只记录告警。 */
    public <T> T execute(UUID tenantId, UUID connectorId, Supplier<T> action) {
        Objects.requireNonNull(action, "action不能为空");
        return executeInternal(tenantId, connectorId, ignored -> action.get(), false);
    }

    /**
     * 在连接器租约内执行需要提交成功状态或推进游标的同步。
     *
     * <p>调用方必须在每次不可逆的成功提交前调用 {@link LeaseGuard#ensureActive()}。
     * 该检查会携带当前随机令牌同步续租，只有 Integration 仍确认当前调用方持有租约时才返回；
     * 因此不能用后台心跳“最近看起来成功”替代提交前的所有权栅栏。</p>
     */
    public <T> T executeWithLeaseGuard(UUID tenantId, UUID connectorId,
                                       Function<LeaseGuard, T> action) {
        Objects.requireNonNull(action, "action不能为空");
        return executeInternal(tenantId, connectorId, action, true);
    }

    private <T> T executeInternal(UUID tenantId, UUID connectorId,
                                  Function<LeaseGuard, T> action,
                                  boolean requireFence) {
        Objects.requireNonNull(tenantId, "tenantId不能为空");
        Objects.requireNonNull(connectorId, "connectorId不能为空");
        Objects.requireNonNull(action, "action不能为空");
        CallerIdentity caller = serviceCaller(tenantId);
        LeaseView lease;
        try {
            lease = validLease(acquire(caller, connectorId), connectorId, null);
        } catch (RuntimeException error) {
            if (SyncConflictClassifier.isAlreadyRunning(error)) {
                throw new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                        "当前租户的订货宝连接器已有同步任务运行中", List.of());
            }
            throw error;
        }
        AtomicReference<Instant> expiresAt = new AtomicReference<>(lease.expiresAt());
        AtomicBoolean lost = new AtomicBoolean(false);
        Object renewalMonitor = new Object();
        LeaseGuard guard = () -> {
            synchronized (renewalMonitor) {
                if (lost.get()) throw leaseLost();
                try {
                    LeaseView renewed = validLease(
                            renew(caller, connectorId, lease.token()), connectorId, lease.token());
                    expiresAt.set(renewed.expiresAt());
                } catch (RuntimeException error) {
                    lost.set(true);
                    log.warn("订货宝连接器提交前租约栅栏失败 tenantId={} connectorId={} service={} errorType={}",
                            tenantId, connectorId, serviceName, error.getClass().getSimpleName());
                    throw leaseLost();
                }
            }
        };
        long heartbeatSeconds = Math.max(5L, lease.ttlSeconds() / 3L);
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            synchronized (renewalMonitor) {
                if (lost.get()) return;
                try {
                    LeaseView renewed = validLease(
                            renew(caller, connectorId, lease.token()), connectorId, lease.token());
                    expiresAt.set(renewed.expiresAt());
                } catch (RuntimeException error) {
                    if (!Instant.now().isBefore(expiresAt.get())) lost.set(true);
                    log.warn("订货宝连接器租约续租异常 tenantId={} connectorId={} service={} expired={} errorType={}",
                            tenantId, connectorId, serviceName, lost.get(),
                            error.getClass().getSimpleName());
                }
            }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        try {
            T result = action.apply(guard);
            if (!requireFence && lost.get()) throw leaseLost();
            return result;
        } finally {
            heartbeat.cancel(false);
            try {
                release(caller, connectorId, lease.token());
            } catch (RuntimeException error) {
                log.warn("订货宝连接器租约释放异常 tenantId={} connectorId={} service={} errorType={}",
                        tenantId, connectorId, serviceName, error.getClass().getSimpleName());
            }
        }
    }

    private static LeaseView validLease(LeaseView response, UUID expectedConnectorId,
                                        String expectedToken) {
        LeaseView value = Objects.requireNonNull(response, "Integration连接器租约返回空响应");
        if (!expectedConnectorId.equals(value.connectorId())
                || value.token() == null || value.token().isBlank()
                || expectedToken != null && !expectedToken.equals(value.token())
                || value.expiresAt() == null || !value.expiresAt().isAfter(Instant.now())
                || value.ttlSeconds() < 1) {
            throw new IllegalStateException("Integration连接器租约响应不完整或与请求不匹配");
        }
        return value;
    }

    private static BusinessException leaseLost() {
        return new BusinessException(ErrorCode.SYNC_ALREADY_RUNNING,
                "订货宝连接器同步租约已丢失，本轮结果不得作为成功批次", List.of());
    }

    /** 领域服务在成功终态或游标事务提交前执行的连接器租约所有权栅栏。 */
    @FunctionalInterface
    public interface LeaseGuard {
        void ensureActive();
    }

    private LeaseView acquire(CallerIdentity caller, UUID connectorId) {
        URI uri = leaseUri(connectorId, null, null);
        return restClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> signedHeaders("POST", uri, caller).forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId()).body(new LeaseCommand(serviceName))
                .retrieve().body(LeaseView.class);
    }

    private LeaseView renew(CallerIdentity caller, UUID connectorId, String token) {
        URI uri = leaseUri(connectorId, token, "renew");
        return restClient.post().uri(uri).headers(headers -> signedHeaders("POST", uri, caller)
                .forEach(headers::set)).header(RequestHeaders.REQUEST_ID, requestId())
                .retrieve().body(LeaseView.class);
    }

    private void release(CallerIdentity caller, UUID connectorId, String token) {
        URI uri = leaseUri(connectorId, token, null);
        restClient.delete().uri(uri).headers(headers -> signedHeaders("DELETE", uri, caller)
                .forEach(headers::set)).header(RequestHeaders.REQUEST_ID, requestId()).retrieve().toBodilessEntity();
    }

    private URI leaseUri(UUID connectorId, String token, String suffix) {
        var builder = UriComponentsBuilder.fromUri(baseUri).path(DhbConnectorLeaseApi.BASE_PATH)
                .pathSegment(connectorId.toString());
        if (token != null) builder.pathSegment(token);
        if (suffix != null) builder.pathSegment(suffix);
        return builder.build().encode().toUri();
    }

    private Map<String, String> signedHeaders(String method, URI uri, CallerIdentity caller) {
        Map<String, String> headers = new LinkedHashMap<>();
        put(headers, RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        put(headers, RequestHeaders.PRINCIPAL_ID, caller.principalId());
        put(headers, RequestHeaders.TENANT_ID, caller.tenantId());
        put(headers, RequestHeaders.USER_ID, caller.userId());
        put(headers, RequestHeaders.PLATFORM_USER_ID, caller.platformUserId());
        put(headers, RequestHeaders.SESSION_ID, caller.sessionId());
        put(headers, RequestHeaders.SESSION_VERSION, caller.sessionVersion());
        put(headers, RequestHeaders.USER_SECURITY_VERSION, caller.userSecurityVersion());
        put(headers, RequestHeaders.TENANT_POLICY_VERSION, caller.tenantPolicyVersion());
        put(headers, RequestHeaders.ROLES, joined(caller.roles()));
        put(headers, RequestHeaders.PERMISSIONS, joined(caller.permissions()));
        TrustedContextSigner.SignedContext signed = signer.sign(method, uri.getRawPath(), uri.getRawQuery(), headers);
        headers.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        headers.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        headers.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return headers;
    }

    private CallerIdentity serviceCaller(UUID tenantId) {
        UUID serviceId = UUID.nameUUIDFromBytes(("service:" + serviceName).getBytes(StandardCharsets.UTF_8));
        return new CallerIdentity("SERVICE", serviceId, tenantId, null, null, UUID.randomUUID(),
                0, 0, 0, Set.of("DHB_SYNC_LEASE_CLIENT"), Set.of(LEASE_PERMISSION));
    }

    private static URI baseUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Integration地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Integration地址必须使用http或https");
        }
        return uri;
    }

    private static void put(Map<String, String> headers, String name, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) headers.put(name, String.valueOf(value));
    }

    private static String joined(Set<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", new TreeSet<>(values));
    }

    private static String requestId() {
        String value = RequestContext.getRequestId();
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }

    @Override public void close() { heartbeatExecutor.shutdownNow(); }
}
