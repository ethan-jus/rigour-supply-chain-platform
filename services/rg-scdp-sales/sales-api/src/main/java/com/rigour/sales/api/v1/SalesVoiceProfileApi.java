package com.rigour.sales.api.v1;

import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

public interface SalesVoiceProfileApi {
    String PATH="/api/v1/sales/me/voice-profile";
    record Profile(boolean registered,String employeeName,long revision,Instant updatedAt) {}
    @GetMapping(PATH) ApiResponse<Profile> read();
    @PostMapping(value=PATH,consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<Profile> register(@RequestPart("file") MultipartFile file,
            @RequestParam("revision") long revision,@RequestParam("consent") boolean consent);
    @DeleteMapping(PATH) ApiResponse<Profile> delete(@RequestParam("revision") long revision);
}
