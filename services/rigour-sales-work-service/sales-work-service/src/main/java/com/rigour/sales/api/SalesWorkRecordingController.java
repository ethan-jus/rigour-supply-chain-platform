package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesWorkApi;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.DiscardRecordingClipCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.DiscardRecordingClipView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.RecordingClipView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.RecordingSessionView;
import com.rigour.sales.application.service.SalesWorkRecordingService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 拜访录音采集 API：片段上传与本人会话查询。 */
@RestController
public final class SalesWorkRecordingController {

    private final SalesWorkRecordingService service;

    public SalesWorkRecordingController(SalesWorkRecordingService service) {
        this.service = service;
    }

    @PostMapping(path = SalesWorkApi.VISIT_RECORDINGS_PATH + "/clips",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RecordingClipView> uploadClip(
            @PathVariable("visitId") UUID visitId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("clientClipId") String clientClipId,
            @RequestParam("durationMs") Long durationMs,
            @RequestParam("recordedFrom")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedFrom,
            @RequestParam("recordedTo")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant recordedTo) {
        return ApiResponse.success(service.uploadClip(
                visitId, file, clientClipId, durationMs, recordedFrom, recordedTo));
    }

    @PostMapping(SalesWorkApi.VISIT_RECORDINGS_PATH + "/discarded-clips")
    public ApiResponse<DiscardRecordingClipView> discardClip(
            @PathVariable("visitId") UUID visitId,
            @RequestBody DiscardRecordingClipCommand command) {
        return ApiResponse.success(service.discardClip(visitId, command));
    }

    @GetMapping(SalesWorkApi.VISIT_RECORDINGS_PATH)
    public ApiResponse<RecordingSessionView> recordings(@PathVariable("visitId") UUID visitId) {
        return ApiResponse.success(service.recordings(visitId));
    }
}
