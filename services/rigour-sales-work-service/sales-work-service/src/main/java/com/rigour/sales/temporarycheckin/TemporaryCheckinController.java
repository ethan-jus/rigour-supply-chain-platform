package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CompletedSubmissionView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CreateStoreRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.CreateSubmissionRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.DraftSubmissionView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.MediaDeleteView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.MediaUploadView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.LocationContextView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.OptionsResponse;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.ResolveLocationRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.StoreView;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
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
 * 无登录临时打卡公开接口。该控制器不读取任何租户请求头，租户由服务端配置固定；
 * 草稿后的媒体写入与完成动作只能使用浏览器生成的提交密钥继续。
 */
@RestController
@RequestMapping("/sales-checkin/api/v1")
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
public class TemporaryCheckinController {

    private final TemporaryCheckinService service;

    public TemporaryCheckinController(TemporaryCheckinService service) {
        this.service = service;
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
    public StoreView createStore(@RequestBody CreateStoreRequest request) {
        return service.createStore(request);
    }

    @PostMapping("/locations/resolve")
    public LocationContextView resolveLocation(@RequestBody ResolveLocationRequest request) {
        return service.resolveLocation(request);
    }

    @PostMapping("/submissions")
    public DraftSubmissionView createSubmission(@RequestBody CreateSubmissionRequest request) {
        return service.createDraft(request);
    }

    @PutMapping(path = "/submissions/{id}/media/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MediaUploadView uploadMedia(
            @PathVariable("id") UUID submissionId,
            @PathVariable("kind") String kind,
            @RequestHeader(name = "X-Submission-Key", required = false) String submissionKey,
            @RequestPart("file") MultipartFile file) {
        return service.uploadMedia(submissionId, kind, submissionKey, file);
    }

    @DeleteMapping("/submissions/{id}/media/{kind}")
    public MediaDeleteView deleteMedia(
            @PathVariable("id") UUID submissionId,
            @PathVariable("kind") String kind,
            @RequestHeader(name = "X-Submission-Key", required = false) String submissionKey) {
        return service.deleteDraftMedia(submissionId, kind, submissionKey);
    }

    @PostMapping("/submissions/{id}/complete")
    public CompletedSubmissionView complete(
            @PathVariable("id") UUID submissionId,
            @RequestHeader(name = "X-Submission-Key", required = false) String submissionKey) {
        return service.complete(submissionId, submissionKey);
    }
}
