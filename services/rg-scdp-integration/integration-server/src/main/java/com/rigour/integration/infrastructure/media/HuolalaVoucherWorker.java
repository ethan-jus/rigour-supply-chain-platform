package com.rigour.integration.infrastructure.media;

import com.rigour.integration.application.port.out.*;
import com.rigour.integration.application.service.huolala.HuolalaOrderNormalizer;
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
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public HuolalaVoucherWorker(
            JdbcTemplate jdbc,
            ProductMediaStorage storage,
            HuolalaDomainClient domains,
            @Qualifier("huolalaExecutor") TaskExecutor executor,
            ProductMediaProperties properties,
            @Value("${rigour.huolala.voucher-allowed-hosts:*.huolala.cn}") String hosts) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.domains = domains;
        this.executor = executor;
        maxBytes = Math.min(properties.getMaxBytes(), 10 * 1024 * 1024L);
        allowedHosts = new HashSet<>(Arrays.asList(hosts.toLowerCase(Locale.ROOT).split(",")));
    }

    public void enqueue(UUID tenant, String connector, CarrierOrder order, JsonNode detail) {
        var item = detail.path("order_detail_item").path("order_item");
        int line = 0;
        for (var fee : item.path("order_price_arr"))
            enqueueUrls(
                    tenant,
                    connector,
                    order,
                    "fee-" + line++,
                    fee.path("img_urls"),
                    fee.path("bill_name").asText("费用") + "凭证");
        enqueueUrls(tenant, connector, order, "receipts", item.path("receipt_url"), "运输回单");
        enqueueUrls(tenant, connector, order, "billVouchers", item.path("img_urls"), "账单凭证");
    }

    private void enqueueUrls(
            UUID tenant,
            String connector,
            CarrierOrder order,
            String lineId,
            JsonNode urls,
            String name) {
        int image = 0;
        for (var value : urls) {
            String url = value.asText("");
            if (url.isBlank()) continue;
            String id = HuolalaOrderNormalizer.voucherId(connector, order.orderNo(), lineId, url);
            jdbc.update(
                    "INSERT INTO"
                        + " integration_huolala_voucher(id,tenant_id,connector_id,enterprise_id,order_no,line_id,source_url,file_name,state,attempts,next_attempt)"
                        + " VALUES(?,?,?,?,?,?,?,?,'PENDING',0,CURRENT_TIMESTAMP(6)) ON DUPLICATE"
                        + " KEY UPDATE"
                        + " source_url=VALUES(source_url),attempts=IF(state='FAILED',0,attempts),state=IF(state='FAILED','PENDING',state)",
                    id,
                    tenant.toString(),
                    connector,
                    order.enterpriseId(),
                    order.orderNo(),
                    lineId,
                    url,
                    name + (++image));
        }
    }

    @Scheduled(fixedDelayString = "${rigour.huolala.voucher-poll-ms:5000}")
    public void dispatch() {
        if (!running.compareAndSet(false, true)) return;
        try {
            executor.execute(
                    () -> {
                        try {
                            processOne();
                        } finally {
                            running.set(false);
                        }
                    });
        } catch (RuntimeException rejected) {
            running.set(false);
        }
    }

    void processOne() {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM integration_huolala_voucher WHERE (state IN"
                                + " ('PENDING','READY') OR state='WORKING' AND"
                                + " lease_until<CURRENT_TIMESTAMP(6)) AND"
                                + " next_attempt<=CURRENT_TIMESTAMP(6) AND attempts<12 ORDER BY"
                                + " next_attempt,id LIMIT 1");
        if (rows.isEmpty()) return;
        var r = rows.getFirst();
        String id = r.get("id").toString(), tenant = r.get("tenant_id").toString();
        String lease = UUID.randomUUID().toString();
        if (jdbc.update(
                        "UPDATE integration_huolala_voucher SET"
                            + " state='WORKING',lease_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL"
                            + " 2 MINUTE),attempts=attempts+1 WHERE tenant_id=? AND id=? AND (state"
                            + " IN ('PENDING','READY') OR state='WORKING' AND"
                            + " lease_until<CURRENT_TIMESTAMP(6))",
                        lease,
                        tenant,
                        id)
                != 1) return;
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
                                : type.equals("image/jpeg") ? "jpg" : "pdf";
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
            jdbc.update(
                    "UPDATE integration_huolala_voucher SET"
                        + " state=IF(attempts>=12,'FAILED',IF(file_key IS"
                        + " NULL,'PENDING','READY')),next_attempt=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL"
                        + " 5 MINUTE),lease_token=NULL,lease_until=NULL,last_error=? WHERE"
                        + " tenant_id=? AND id=? AND lease_token=?",
                    e.getClass().getSimpleName(),
                    tenant,
                    id,
                    lease);
        }
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
