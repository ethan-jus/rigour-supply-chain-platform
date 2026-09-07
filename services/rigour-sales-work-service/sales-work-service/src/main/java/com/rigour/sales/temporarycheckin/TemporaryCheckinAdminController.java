package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAccessPolicy.AdminScope;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.AdminOptionsResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.AdminSubmissionPage;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.DeleteMediaRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.DeleteMediaView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminModels.TranscriptionView;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 临时打卡后台列表、导出与媒体读取接口。应用级数据库会话过滤器先完成认证和 CSRF 校验，
 * 本控制器再强制总管理员/城市管理员数据范围与服务端固定租户。
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
    public AdminOptionsResponse options(HttpServletRequest request) {
        return service.adminOptions(accessPolicy.requireScope(request));
    }

    @GetMapping("/api/v1/submissions")
    public AdminSubmissionPage submissions(
            HttpServletRequest request,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "salespersonId", required = false) UUID salespersonId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "visitType", required = false) String visitType,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "locationStatus", required = false) String locationStatus,
            @RequestParam(name = "reviewStatus", required = false) String reviewStatus,
            @RequestParam(name = "mediaStatus", required = false) String mediaStatus,
            @RequestParam(name = "sortBy", required = false) String sortBy,
            @RequestParam(name = "sortDirection", required = false) String sortDirection,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        AdminScope scope = accessPolicy.requireScope(request);
        return service.findAdminSubmissions(
                scope, from, to, city, salespersonId, status, visitType, query, page, size,
                new TemporaryCheckinRepository.AdminReadOptions(locationStatus,reviewStatus,mediaStatus,sortBy,sortDirection));
    }

    @GetMapping(value = "/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(
            HttpServletRequest request,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "salespersonId", required = false) UUID salespersonId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "visitType", required = false) String visitType,
            @RequestParam(name = "locationStatus", required = false) String locationStatus,
            @RequestParam(name = "reviewStatus", required = false) String reviewStatus,
            @RequestParam(name = "mediaStatus", required = false) String mediaStatus,
            @RequestParam(name = "sortBy", required = false) String sortBy,
            @RequestParam(name = "sortDirection", required = false) String sortDirection,
            @RequestParam(name = "q", required = false) String query) {
        AdminScope scope = accessPolicy.requireScope(request);
        byte[] bytes = service.exportCsv(scope, from, to, city, salespersonId, status, visitType, query,
                new TemporaryCheckinRepository.AdminReadOptions(locationStatus,reviewStatus,mediaStatus,sortBy,sortDirection))
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

    @PostMapping("/api/v1/submissions/{id}/review")
    public TemporaryCheckinEvidenceRepository.ReviewEvent review(HttpServletRequest request,
            @PathVariable("id") UUID id, @RequestBody TemporaryCheckinAdminModels.ReviewRequest body) {
        return service.review(accessPolicy.requireScope(request),id,body);
    }

    @GetMapping("/api/v1/submissions/{id}/reviews")
    public List<TemporaryCheckinEvidenceRepository.ReviewEvent> reviews(HttpServletRequest request,
            @PathVariable("id") UUID id) {
        return service.reviews(accessPolicy.requireScope(request),id);
    }

    @GetMapping("/submissions/{id}/media/{kind}")
    public ResponseEntity<?> media(
            HttpServletRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(
                    name = HttpHeaders.RANGE, required = false) String rangeHeader,
            @PathVariable("id") UUID submissionId,
            @PathVariable("kind") String kind,
            @RequestParam(name = "download", defaultValue = "false") boolean download) {
        AdminScope scope = accessPolicy.requireScope(request);
        TemporaryCheckinService.AdminMedia content = service.openAdminMedia(scope, submissionId, kind);
        return TemporaryCheckinMediaResponses.respond(content, rangeHeader, download);
    }

    @GetMapping("/submissions/{id}/media/audio/{segmentId}")
    public ResponseEntity<?> audioSegment(
            HttpServletRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(
                    name = HttpHeaders.RANGE, required = false) String rangeHeader,
            @PathVariable("id") UUID submissionId,
            @PathVariable("segmentId") UUID segmentId,
            @RequestParam(name = "download", defaultValue = "false") boolean download,
            @RequestParam(name = "playback", defaultValue = "false") boolean playback) {
        AdminScope scope = accessPolicy.requireScope(request);
        TemporaryCheckinService.AdminMedia content =
                playback && !download ? service.openAdminAudioPlayback(scope,submissionId,segmentId)
                    : service.openAdminAudioSegment(scope, submissionId, segmentId);
        return TemporaryCheckinMediaResponses.respond(content, rangeHeader, download);
    }

    @DeleteMapping("/api/v1/submissions/{id}/media/{kind}")
    public DeleteMediaView deleteMedia(
            HttpServletRequest servletRequest,
            @PathVariable("id") UUID submissionId,
            @PathVariable("kind") String kind,
            @RequestBody(required = false) DeleteMediaRequest request) {
        AdminScope scope = accessPolicy.requireScope(servletRequest);
        return service.deleteAdminMedia(scope, submissionId, kind, request == null ? null : request.reason());
    }

    @DeleteMapping("/api/v1/submissions/{id}/media/audio/{segmentId}")
    public DeleteMediaView deleteAudioSegment(
            HttpServletRequest servletRequest,
            @PathVariable("id") UUID submissionId,
            @PathVariable("segmentId") UUID segmentId,
            @RequestBody(required = false) DeleteMediaRequest request) {
        AdminScope scope = accessPolicy.requireScope(servletRequest);
        return service.deleteAdminAudioSegment(
                scope, submissionId, segmentId, request == null ? null : request.reason());
    }

    @PostMapping("/api/v1/submissions/{id}/transcription")
    public TranscriptionView retryTranscription(
            HttpServletRequest request,
            @PathVariable("id") UUID submissionId) {
        AdminScope scope = accessPolicy.requireScope(request);
        return service.requestAdminTranscription(scope, submissionId);
    }

    @GetMapping("/submissions/{id}/media/{kind}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(
            HttpServletRequest request,
            @PathVariable("id") UUID submissionId,
            @PathVariable("kind") String kind) {
        AdminScope scope = accessPolicy.requireScope(request);
        byte[] thumbnail = service.adminThumbnail(scope, submissionId, kind);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(thumbnail.length)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.builder("inline")
                        .filename("preview-thumbnail.jpg", StandardCharsets.UTF_8)
                        .build().toString())
                .body(thumbnail);
    }

}
