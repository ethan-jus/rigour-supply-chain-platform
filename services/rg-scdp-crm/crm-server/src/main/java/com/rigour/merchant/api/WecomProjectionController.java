package com.rigour.merchant.api;
import com.rigour.merchant.api.v1.model.WecomModels.Snapshot;
import com.rigour.merchant.application.service.WecomService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/internal/v1/crm/wecom")
public class WecomProjectionController {
    private final WecomService service;
    public WecomProjectionController(WecomService service){this.service=service;}
    @PutMapping("/snapshot") public ApiResponse<Void> snapshot(@RequestBody Snapshot c){service.accept(c);return ApiResponse.success(null);}
}
