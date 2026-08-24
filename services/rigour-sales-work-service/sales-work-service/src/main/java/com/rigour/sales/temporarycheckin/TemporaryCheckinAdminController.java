package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAccessPolicy.AdminScope;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.AdminOptionsResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.AdminSubmissionPage;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
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
    private final TemporaryCheckinAdminThumbnailer thumbnailer;

    public TemporaryCheckinAdminController(
            TemporaryCheckinService service,
            TemporaryCheckinAdminAccessPolicy accessPolicy,
            TemporaryCheckinAdminThumbnailer thumbnailer) {
        this.service = service;
        this.accessPolicy = accessPolicy;
        this.thumbnailer = thumbnailer;
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
    public ResponseEntity<?> media(
            @RequestHeader(name = TemporaryCheckinAdminAccessPolicy.HEADER, required = false) String username,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) String rangeHeader,
            @PathVariable("id") UUID submissionId,
            @PathVariable("kind") String kind,
            @RequestParam(name = "download", defaultValue = "false") boolean download) {
        AdminScope scope = accessPolicy.requireScope(username);
        TemporaryCheckinService.AdminMedia content = service.openAdminMedia(scope, submissionId, kind);
        ContentDisposition disposition = ContentDisposition.builder(download ? "attachment" : "inline")
                .filename(content.originalFilename(), StandardCharsets.UTF_8)
                .build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(safeMediaType(content.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            return rangedResponse(content, rangeHeader, disposition);
        }
        if (content.sizeBytes() > 0) response.contentLength(content.sizeBytes());
        return response.body(new InputStreamResource(content.open()));
    }

    @GetMapping("/submissions/{id}/media/{kind}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(
            @RequestHeader(name = TemporaryCheckinAdminAccessPolicy.HEADER, required = false) String username,
            @PathVariable("id") UUID submissionId,
            @PathVariable("kind") String kind) {
        AdminScope scope = accessPolicy.requireScope(username);
        TemporaryCheckinService.AdminMedia content = service.openAdminMedia(scope, submissionId, kind);
        TemporaryCheckinAdminThumbnailer.Thumbnail thumbnail = thumbnailer.create(content);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(thumbnail.bytes().length)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.builder("inline")
                        .filename(thumbnail.filename(), StandardCharsets.UTF_8)
                        .build().toString())
                .body(thumbnail.bytes());
    }

    private static ResponseEntity<?> rangedResponse(
            TemporaryCheckinService.AdminMedia content,
            String rangeHeader,
            ContentDisposition disposition) {
        long size = content.sizeBytes();
        if (size <= 0) return rangeNotSatisfiable(size);
        try {
            List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
            if (ranges.size() != 1) return rangeNotSatisfiable(size);
            long start = ranges.get(0).getRangeStart(size);
            long end = ranges.get(0).getRangeEnd(size);
            if (start < 0 || end < start || end >= size) {
                return rangeNotSatisfiable(size);
            }
            InputStream input = content.open();
            try {
                input.skipNBytes(start);
            } catch (IOException exception) {
                closeQuietly(input);
                return rangeNotSatisfiable(size);
            }
            long length = end - start + 1;
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(safeMediaType(content.contentType()))
                    .contentLength(length)
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .body(new InputStreamResource(new LimitedInputStream(input, length)));
        } catch (IllegalArgumentException exception) {
            return rangeNotSatisfiable(size);
        }
    }

    private static ResponseEntity<Void> rangeNotSatisfiable(long size) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + Math.max(size, 0))
                .build();
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // 响应已经确定为416，关闭失败不再掩盖范围错误。
        }
    }

    private static MediaType safeMediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (IllegalArgumentException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        private LimitedInputStream(InputStream input, long remaining) {
            super(input);
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = super.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining <= 0) return -1;
            int count = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = super.skip(Math.min(count, remaining));
            remaining -= skipped;
            return skipped;
        }
    }
}
