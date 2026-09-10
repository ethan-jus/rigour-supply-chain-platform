package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CompletedSubmissionView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.ClientDiagnosticEventRequest;
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
import java.time.LocalDate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
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

    @PostMapping("/diagnostics/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordClientDiagnosticEvent(
            @RequestBody ClientDiagnosticEventRequest request,
            HttpServletRequest servletRequest) {
        service.recordClientDiagnosticEvent(
                request, TemporaryCheckinRequestFacts.from(servletRequest));
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
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "salespersonId", required = false) UUID salespersonId,
            @RequestParam(name = "longitude", required = false) java.math.BigDecimal longitude,
            @RequestParam(name = "latitude", required = false) java.math.BigDecimal latitude,
            HttpServletRequest servletRequest) {
        return service.searchAuthorizedStores(city, query, limit, salespersonId,longitude,latitude,
                TemporaryCheckinRequestFacts.from(servletRequest));
    }

    @PostMapping("/stores")
    public StoreView createStore(@RequestBody CreateStoreRequest request, HttpServletRequest servletRequest) {
        return service.createStore(request, TemporaryCheckinRequestFacts.from(servletRequest));
    }

    /** GPS 最终不可用时的显式例外入口；记录会标记为定位未核验。 */
    @PostMapping("/stores/unverified-location")
    public StoreView createStoreWithoutVerifiedLocation(
            @RequestBody CreateStoreRequest request, HttpServletRequest servletRequest) {
        return service.createStoreWithoutVerifiedLocation(
                request, TemporaryCheckinRequestFacts.from(servletRequest));
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

    /** GPS 最终不可用时的显式例外入口；仍要求身份、门店、现场照片和完整业务记录。 */
    @PostMapping("/submissions/unverified-location")
    public DraftSubmissionView createSubmissionWithoutVerifiedLocation(
            @RequestBody CreateSubmissionRequest request, HttpServletRequest servletRequest) {
        return service.createDraftWithoutVerifiedLocation(
                request, TemporaryCheckinRequestFacts.from(servletRequest));
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

    @PutMapping(path = "/submissions/{id}/media/photos/{photoId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaUploadView uploadPhoto(@PathVariable("id") UUID id,@PathVariable("photoId") UUID photoId,
            @RequestHeader(name="X-Submission-Key",required=false) String key,
            @RequestPart("file") MultipartFile file,
            @RequestParam(name="captureSource",required=false) String captureSource,HttpServletRequest request) {
        return service.uploadPhoto(id,photoId,key,file,captureSource,TemporaryCheckinRequestFacts.from(request));
    }

    @DeleteMapping("/submissions/{id}/media/photos/{photoId}")
    public MediaDeleteView deletePhoto(@PathVariable("id") UUID id,@PathVariable("photoId") UUID photoId,
            @RequestHeader(name="X-Submission-Key",required=false) String key,HttpServletRequest request) {
        return service.deleteDraftPhoto(id,photoId,key,TemporaryCheckinRequestFacts.from(request));
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

    @GetMapping("/submissions/by-client/{clientSubmissionId}")
    public TemporaryCheckinModels.SubmissionReceipt receipt(
            @PathVariable("clientSubmissionId") UUID clientId,
            @RequestHeader(name = "X-Submission-Key", required = false) String key,
            HttpServletRequest request) {
        return service.receiptByClient(clientId, key, TemporaryCheckinRequestFacts.from(request));
    }

    @GetMapping("/submissions/mine")
    public ResponseEntity<TemporaryCheckinModels.SubmissionReceiptPage> ownReceipts(
            @RequestParam(name = "salespersonId", required = false) UUID salespersonId,
            @RequestParam(name = "dateFrom", required = false) LocalDate dateFrom,
            @RequestParam(name = "dateTo", required = false) LocalDate dateTo,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "sortDir", required = false) String sortDir,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            HttpServletRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(service.ownReceipts(salespersonId, dateFrom, dateTo, status, sortDir, page, size,
                        TemporaryCheckinRequestFacts.from(request)));
    }

    @GetMapping("/submissions/{id}/mine")
    public ResponseEntity<TemporaryCheckinModels.OwnSubmissionDetail> ownSubmission(
            @PathVariable("id") UUID submissionId, HttpServletRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(service.ownSubmission(submissionId, TemporaryCheckinRequestFacts.from(request)));
    }

    @GetMapping("/submissions/{id}/mine/media/{mediaId}")
    public ResponseEntity<?> ownMedia(
            @PathVariable("id") UUID submissionId,
            @PathVariable("mediaId") String mediaId,
            @RequestParam(name = "variant", defaultValue = "original") String variant,
            @RequestParam(name = "download", defaultValue = "false") boolean download,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) String rangeHeader,
            HttpServletRequest request) {
        return TemporaryCheckinMediaResponses.respond(service.ownMedia(submissionId, mediaId, variant,
                TemporaryCheckinRequestFacts.from(request)), rangeHeader, download);
    }

    /** Route conversion failures are caller errors, not the platform's generic 500 response. */
    @org.springframework.web.bind.annotation.ExceptionHandler(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<TemporaryCheckinModels.ErrorResponse> invalidParameter() {
        return ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new TemporaryCheckinModels.ErrorResponse("TEMP_CHECKIN_BAD_REQUEST",
                        "参数格式无效，请检查日期、编号和分页"));
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
