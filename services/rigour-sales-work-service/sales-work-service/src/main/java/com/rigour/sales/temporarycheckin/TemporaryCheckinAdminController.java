package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAccessPolicy.AdminScope;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.AdminOptionsResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.AdminSubmissionPage;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 临时打卡后台列表、导出与媒体读取接口。生产只允许由回环监听后的 Nginx Basic Auth 入口访问，
 * Nginx 必须用 {@code $remote_user} 覆盖可信用户名头；本控制器同时强制城市范围与服务端固定租户。
 */
@RestController
@RequestMapping("/sales-checkin/admin")
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
public class TemporaryCheckinAdminController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");
    private final TemporaryCheckinService service;
    private final TemporaryCheckinAdminAccessPolicy accessPolicy;

    public TemporaryCheckinAdminController(
            TemporaryCheckinService service,
            TemporaryCheckinAdminAccessPolicy accessPolicy) {
        this.service = service;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/api/v1/options")
    public AdminOptionsResponse options(
            @RequestHeader(name = TemporaryCheckinAdminAccessPolicy.HEADER, required = false) String username) {
        return service.adminOptions(accessPolicy.requireScope(username));
    }

    @GetMapping("/api/v1/submissions")
    public AdminSubmissionPage submissions(
            @RequestHeader(name = TemporaryCheckinAdminAccessPolicy.HEADER, required = false) String username,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "salespersonId", required = false) UUID salespersonId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        AdminScope scope = accessPolicy.requireScope(username);
        return service.findAdminSubmissions(
                scope, from, to, city, salespersonId, status, query, page, size);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(
            @RequestHeader(name = TemporaryCheckinAdminAccessPolicy.HEADER, required = false) String username,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "salespersonId", required = false) UUID salespersonId,
            @RequestParam(name = "status", required = false) String status) {
        AdminScope scope = accessPolicy.requireScope(username);
        byte[] bytes = service.exportCsv(scope, from, to, city, salespersonId, status)
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(CSV)
                .contentLength(bytes.length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("sales-checkin-export.csv", StandardCharsets.UTF_8)
                        .build().toString())
                .body(bytes);
    }

    @GetMapping("/submissions/{id}/media/{kind}")
    public ResponseEntity<InputStreamResource> media(
            @RequestHeader(name = TemporaryCheckinAdminAccessPolicy.HEADER, required = false) String username,
            @PathVariable("id") UUID submissionId,
            @PathVariable("kind") String kind) {
        AdminScope scope = accessPolicy.requireScope(username);
        TemporaryCheckinService.AdminMedia content = service.openAdminMedia(scope, submissionId, kind);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(safeMediaType(content.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.originalFilename(), StandardCharsets.UTF_8)
                        .build().toString());
        if (content.sizeBytes() > 0) response.contentLength(content.sizeBytes());
        return response.body(new InputStreamResource(content.input()));
    }

    private static MediaType safeMediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
