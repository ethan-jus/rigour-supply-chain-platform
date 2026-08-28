package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CompletedSubmissionView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CreateStoreRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CreateSubmissionRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.DraftSubmissionView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.MediaDeleteView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.MediaUploadView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.LocationContextView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.IdentityVerifyRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.OptionsResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.SalesIdentityView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.ResolveLocationRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.SearchNewStoreRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.StoreView;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 临时打卡公开接口。租户由服务端固定；个人码验证后的 HttpOnly Cookie
 * 绑定销售与设备，草稿密钥另外保护后续媒体写入和完成动作。
 */
@RestController
@RequestMapping("/sales-checkin/api/v1")
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
public class TemporaryCheckinController {

    private final TemporaryCheckinService service;
    private final TemporaryCheckinSalesIdentityService identityService;

    public TemporaryCheckinController(
            TemporaryCheckinService service,
            TemporaryCheckinSalesIdentityService identityService) {
        this.service = service;
        this.identityService = identityService;
    }

    @PostMapping("/identity/verify")
    public ResponseEntity<SalesIdentityView> verifyIdentity(
            @RequestBody IdentityVerifyRequest request,
            HttpServletRequest servletRequest) {
        var verification = identityService.verify(request, TemporaryCheckinRequestFacts.from(servletRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, verification.deviceCookie().toString())
                .header(HttpHeaders.SET_COOKIE, verification.identityCookie().toString())
                .body(verification.view());
    }

    @GetMapping("/identity/me")
    public SalesIdentityView currentIdentity(HttpServletRequest request) {
        return identityService.current(TemporaryCheckinRequestFacts.from(request));
    }

    @PostMapping("/identity/logout")
    public ResponseEntity<Void> logoutIdentity(HttpServletRequest request) {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        identityService.clearIdentityCookie(
                                TemporaryCheckinRequestFacts.from(request)).toString())
                .build();
    }

    @GetMapping("/options")
    public OptionsResponse options(@RequestParam(name = "city", required = false) String city) {
        return service.options(city);
    }

    @GetMapping("/stores")
    public List<StoreView> stores(
            @RequestParam(name = "city") String city,
            @RequestParam(name = "q") String query,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.searchStores(city, query, limit);
    }

    @PostMapping("/stores")
    public StoreView createStore(@RequestBody CreateStoreRequest request, HttpServletRequest servletRequest) {
        return service.createStore(request, TemporaryCheckinRequestFacts.from(servletRequest));
    }

    @PostMapping("/locations/resolve")
    public LocationContextView resolveLocation(
            @RequestBody ResolveLocationRequest request,
            HttpServletRequest servletRequest) {
        return service.resolveLocation(
                request, TemporaryCheckinRequestFacts.from(servletRequest));
    }

    @PostMapping("/locations/search-new-store")
    public LocationContextView searchNewStore(
            @RequestBody SearchNewStoreRequest request,
            HttpServletRequest servletRequest) {
        return service.searchNewStore(
                request, TemporaryCheckinRequestFacts.from(servletRequest));
    }

    @PostMapping("/submissions")
    public DraftSubmissionView createSubmission(
            @RequestBody CreateSubmissionRequest request, HttpServletRequest servletRequest) {
        return service.createDraft(request, TemporaryCheckinRequestFacts.from(servletRequest));
    }

    @PutMapping(path = "/submissions/{id}/media/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaUploadView uploadMedia(
            @PathVariable("id") UUID submissionId,
            @PathVariable("kind") String kind,
            @RequestHeader(name = "X-Submission-Key", required = false) String submissionKey,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest servletRequest) {
        return service.uploadMedia(submissionId, kind, submissionKey, file,
                TemporaryCheckinRequestFacts.from(servletRequest));
    }

    @PutMapping(path = "/submissions/{id}/media/audio/{segmentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaUploadView uploadAudioSegment(
            @PathVariable("id") UUID submissionId,
            @PathVariable("segmentId") UUID segmentId,
            @RequestHeader(name = "X-Submission-Key", required = false) String submissionKey,
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "captureSource", required = false) String captureSource,
            @RequestParam(name = "clientStartedAt", required = false) String clientStartedAt,
            @RequestParam(name = "clientDurationMs", required = false) String clientDurationMs,
            @RequestParam(name = "fileLastModifiedAt", required = false) String fileLastModifiedAt,
            HttpServletRequest servletRequest) {
        return service.uploadAudioSegment(submissionId, segmentId, submissionKey, file,
                captureSource, clientStartedAt, clientDurationMs, fileLastModifiedAt,
                TemporaryCheckinRequestFacts.from(servletRequest));
    }

    @DeleteMapping("/submissions/{id}/media/{kind}")
    public MediaDeleteView deleteMedia(
            @PathVariable("id") UUID submissionId,
            @PathVariable("kind") String kind,
            @RequestHeader(name = "X-Submission-Key", required = false) String submissionKey,
            HttpServletRequest servletRequest) {
        return service.deleteDraftMedia(submissionId, kind, submissionKey,
                TemporaryCheckinRequestFacts.from(servletRequest));
    }

    @DeleteMapping("/submissions/{id}/media/audio/{segmentId}")
    public MediaDeleteView deleteAudioSegment(
            @PathVariable("id") UUID submissionId,
            @PathVariable("segmentId") UUID segmentId,
            @RequestHeader(name = "X-Submission-Key", required = false) String submissionKey,
            HttpServletRequest servletRequest) {
        return service.deleteDraftAudioSegment(submissionId, segmentId, submissionKey,
                TemporaryCheckinRequestFacts.from(servletRequest));
    }

    @PostMapping("/submissions/{id}/complete")
    public CompletedSubmissionView complete(
            @PathVariable("id") UUID submissionId,
            @RequestHeader(name = "X-Submission-Key", required = false) String submissionKey,
            HttpServletRequest servletRequest) {
        return service.complete(submissionId, submissionKey,
                TemporaryCheckinRequestFacts.from(servletRequest));
    }
}
