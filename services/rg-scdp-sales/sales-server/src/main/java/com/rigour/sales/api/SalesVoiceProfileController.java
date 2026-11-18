package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesVoiceProfileApi;
import com.rigour.sales.application.service.SalesVoiceProfileService;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.exception.RequestValidationException;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class SalesVoiceProfileController implements SalesVoiceProfileApi {
    private final SalesVoiceProfileService service;
    public SalesVoiceProfileController(SalesVoiceProfileService service){this.service=service;}
    @Override public ApiResponse<Profile> read(){return ApiResponse.success(service.read());}
    @Override public ApiResponse<Profile> register(MultipartFile file,long revision,boolean consent){
        if(file==null||file.getSize()>2_097_152)throw new RequestValidationException("录音文件过大，请重新录制");
        try{return ApiResponse.success(service.register(file.getBytes(),revision,consent));}
        catch(java.io.IOException e){throw new RequestValidationException("录音读取失败，请重新录制");}
    }
    @Override public ApiResponse<Profile> delete(long revision){return ApiResponse.success(service.delete(revision));}
}
