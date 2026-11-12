package com.rigour.integration.infrastructure.thirdparty.huolala.media;

import com.rigour.integration.application.port.out.*;
import com.rigour.integration.application.port.out.thirdparty.huolala.HuolalaDomainClient;
import com.rigour.integration.application.port.out.thirdparty.huolala.HuolalaVouchers;
import com.rigour.integration.application.service.thirdparty.huolala.HuolalaEvidence;
import com.rigour.integration.application.service.thirdparty.huolala.HuolalaOrderNormalizer;
import com.rigour.integration.infrastructure.config.ProductMediaProperties;
import com.rigour.order.api.v1.model.LogisticsImportModels.*;
import com.rigour.shared.context.CallerIdentity;

import org.springframework.beans.factory.annotation.*;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 凭证独立持久队列；COS成功后可单独重试Order投递，不重复创建费用。 */
@Component
public class HuolalaVoucherWorker implements HuolalaVouchers {
    private final JdbcTemplate jdbc;
    private final ProductMediaStorage storage;
    private final HuolalaDomainClient domains;
    private final TaskExecutor executor;
    private final long maxBytes;
    private final Set<String> allowedHosts;
    private final AtomicBoolean running = new AtomicBoolean();
    private final HttpClient http;

    @Autowired
    public HuolalaVoucherWorker(
            JdbcTemplate jdbc,
            ProductMediaStorage storage,
            HuolalaDomainClient domains,
            @Qualifier("huolalaExecutor") TaskExecutor executor,
            ProductMediaProperties properties,
            @Value("${rigour.huolala.voucher-allowed-hosts:*.huolala.cn}") String hosts) {
        this(
                jdbc,
                storage,
                domains,
                executor,
                properties,
                hosts,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    HuolalaVoucherWorker(
            JdbcTemplate jdbc,
            ProductMediaStorage storage,
            HuolalaDomainClient domains,
            TaskExecutor executor,
            ProductMediaProperties properties,
            String hosts,
            HttpClient http) {
        this.http = http;
        this.jdbc = jdbc;
        this.storage = storage;
        this.domains = domains;
        this.executor = executor;
        maxBytes = Math.min(properties.getMaxBytes(), 10 * 1024 * 1024L);
        allowedHosts = new HashSet<>(Arrays.asList(hosts.toLowerCase(Locale.ROOT).split(",")));
    }

    public void enqueue(UUID tenant, String connector, CarrierOrder order, JsonNode detail) {
        for (var file : HuolalaEvidence.attachments(detail)) {
            String id =
                    HuolalaOrderNormalizer.voucherId(
                            connector, order.orderNo(), file.lineId(), file.url());
            jdbc.update(
                    "INSERT INTO"
                        + " integration_huolala_voucher(id,tenant_id,connector_id,enterprise_id,order_no,line_id,source_url,file_name,state,attempts,next_attempt)"
                        + " VALUES(?,?,?,?,?,?,?,?,'PENDING',0,CURRENT_TIMESTAMP(6)) ON DUPLICATE"
                        + " KEY UPDATE next_attempt=CASE WHEN state='FAILED' AND"
                        + " source_url<>VALUES(source_url) THEN CURRENT_TIMESTAMP(6) ELSE"
                        + " next_attempt END, attempts=CASE WHEN state='FAILED' AND"
                        + " source_url<>VALUES(source_url) THEN 0 ELSE attempts END, state=CASE"
                        + " WHEN state='FAILED' AND source_url<>VALUES(source_url) THEN 'PENDING'"
                        + " ELSE state END,source_url=VALUES(source_url)",
                    id,
                    tenant.toString(),
                    connector,
                    order.enterpriseId(),
                    order.orderNo(),
                    file.lineId(),
                    file.url(),
                    file.name());
        }
    }

    @Scheduled(fixedDelayString = "${rigour.huolala.voucher-poll-ms:5000}")
    public void dispatch() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(
                    () -> {
                        try {
                            // 每轮有界批量领取，避免大量图片按5秒/张排队直至源地址过期。
                            for (int i = 0; i < 20 && !Thread.currentThread().isInterrupted(); i++)
                                if (!processOne()) break;
                        } finally {
                            running.set(false);
                        }
                    });
        } catch (RuntimeException rejected) {
            running.set(false);
        }
    }

    boolean processOne() {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM integration_huolala_voucher WHERE (state IN"
                                + " ('PENDING','READY','FAILED') OR state='WORKING' AND"
                                + " lease_until<CURRENT_TIMESTAMP(6)) AND"
                                + " next_attempt<=CURRENT_TIMESTAMP(6) ORDER BY"
                                + " next_attempt,id LIMIT 1");
        if (rows.isEmpty()) return false;
        var r = rows.getFirst();
        String id = r.get("id").toString(), tenant = r.get("tenant_id").toString();
        String lease = UUID.randomUUID().toString();
        if (jdbc.update(
                        "UPDATE integration_huolala_voucher SET"
                                + " state='WORKING',lease_token=?,lease_until=?,attempts=attempts+1"
                                + " WHERE tenant_id=? AND id=? AND (state IN"
                                + " ('PENDING','READY','FAILED') OR state='WORKING' AND"
                                + " lease_until<CURRENT_TIMESTAMP(6)) AND"
                                + " next_attempt<=CURRENT_TIMESTAMP(6)",
                        lease,
                        java.sql.Timestamp.from(Instant.now().plusSeconds(120)),
                        tenant,
                        id)
                != 1) return false;
        try {
            Instant savedAt =
                    r.get("saved_at") instanceof java.sql.Timestamp t
                            ? t.toInstant()
                            : Instant.now();
            String key = (String) r.get("file_key"),
                    type = (String) r.get("content_type"),
                    name = r.get("file_name").toString();
            if (key == null) {
                URI uri = validateUri(r.get("source_url").toString());
                var response =
                        http.send(
                                HttpRequest.newBuilder(uri)
                                        .timeout(Duration.ofSeconds(20))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofInputStream());
                byte[] bytes;
                try (var input = response.body()) {
                    if (response.statusCode() != 200) throw new IllegalStateException("凭证暂未取得");
                    bytes = input.readNBytes(Math.toIntExact(maxBytes + 1));
                }
                if (bytes.length == 0 || bytes.length > maxBytes)
                    throw new IllegalStateException("凭证大小无效");
                type = contentType(bytes);
                String extension =
                        type.equals("image/png")
                                ? "png"
                                : type.equals("image/jpeg")
                                        ? "jpg"
                                        : type.equals("image/webp")
                                                ? "webp"
                                                : type.equals("image/gif") ? "gif" : "pdf";
                key =
                        tenant
                                + "/integration-huolala-vouchers/"
                                + r.get("connector_id")
                                + "/"
                                + r.get("order_no")
                                + "/"
                                + id
                                + "."
                                + extension;
                name = name + "." + extension;
                storage.put(tenant, key, name, type, bytes);
                savedAt = Instant.now();
                jdbc.update(
                        "UPDATE integration_huolala_voucher SET"
                            + " file_key=?,content_type=?,file_name=?,saved_at=CURRENT_TIMESTAMP(6)"
                            + " WHERE tenant_id=? AND id=? AND lease_token=?",
                        key,
                        type,
                        name,
                        tenant,
                        id,
                        lease);
            }
            domains.voucher(
                    caller(UUID.fromString(tenant)),
                    new VoucherUpdate(
                            r.get("connector_id").toString(),
                            r.get("enterprise_id").toString(),
                            r.get("order_no").toString(),
                            r.get("line_id").toString(),
                            new Voucher(id, name, key, type, "READY", savedAt)));
            jdbc.update(
                    "UPDATE integration_huolala_voucher SET"
                        + " state='DONE',lease_token=NULL,lease_until=NULL,last_error=NULL WHERE"
                        + " tenant_id=? AND id=? AND lease_token=?",
                    tenant,
                    id,
                    lease);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // 不记录带签名的源URL、响应正文、凭据。保留COS文件以重试领域投递。
            int attempt = ((Number) r.getOrDefault("attempts", 0)).intValue() + 1;
            long seconds = Math.min(21600, 30L << Math.min(attempt - 1, 10));
            jdbc.update(
                    "UPDATE integration_huolala_voucher SET"
                        + " state='FAILED',next_attempt=?,lease_token=NULL,lease_until=NULL,last_error=?"
                        + " WHERE tenant_id=? AND id=? AND lease_token=?",
                    java.sql.Timestamp.from(Instant.now().plusSeconds(seconds)),
                    e.getClass().getSimpleName(),
                    tenant,
                    id,
                    lease);
            try {
                domains.voucher(
                        caller(UUID.fromString(tenant)),
                        new VoucherUpdate(
                                r.get("connector_id").toString(),
                                r.get("enterprise_id").toString(),
                                r.get("order_no").toString(),
                                r.get("line_id").toString(),
                                new Voucher(
                                        id,
                                        r.get("file_name").toString(),
                                        null,
                                        null,
                                        "FAILED",
                                        null)));
            } catch (RuntimeException unavailable) {
                // Order不可用不丢失任务，下一轮继续投递；错误正文和源URL不对外展示。
            }
        }
        return true;
    }

    URI validateUri(String url) throws UnknownHostException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || uri.getRawUserInfo() != null
                || uri.getPort() != -1 && uri.getPort() != 443
                || allowedHosts.stream()
                        .noneMatch(
                                h ->
                                        h.startsWith("*.")
                                                ? host.toLowerCase(Locale.ROOT)
                                                        .endsWith(h.substring(1))
                                                : host.equalsIgnoreCase(h)))
            throw new IllegalArgumentException("凭证下载域名未配置");
        for (var address : InetAddress.getAllByName(host))
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) throw new IllegalArgumentException("凭证下载地址无效");
        return uri;
    }

    static String contentType(byte[] b) {
        if (b.length >= 8
                && b[0] == (byte) 137
                && b[1] == 80
                && b[2] == 78
                && b[3] == 71
                && b[4] == 13
                && b[5] == 10
                && b[6] == 26
                && b[7] == 10) return "image/png";
        if (b.length >= 3 && b[0] == (byte) 255 && b[1] == (byte) 216 && b[2] == (byte) 255)
            return "image/jpeg";
        if (b.length >= 5
                && new String(b, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
            return "application/pdf";
        if (b.length >= 12
                && new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                && new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))
            return "image/webp";
        if (b.length >= 6
                && (new String(b, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF89a")
                        || new String(b, 0, 6, java.nio.charset.StandardCharsets.US_ASCII)
                                .equals("GIF87a"))) return "image/gif";
        throw new IllegalArgumentException("不支持的平台凭证格式");
    }

    private static CallerIdentity caller(UUID tenant) {
        return new CallerIdentity(
                "SERVICE",
                UUID.fromString("691faa79-2d5d-45c3-9868-34d079500001"),
                tenant,
                null,
                null,
                UUID.randomUUID(),
                0,
                0,
                0,
                Set.of("INTEGRATION_HUOLALA"),
                Set.of("integration:huolala:sync"));
    }
}
