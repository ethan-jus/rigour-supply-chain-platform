package com.rigour.merchant.api;
import com.rigour.merchant.api.v1.model.WecomModels.*;
import com.rigour.merchant.api.v1.model.StoreContactModels.Contact;
import com.rigour.merchant.application.service.WecomService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/crm/wecom")
public class WecomController {
    private final WecomService service;
    public WecomController(WecomService service){this.service=service;}
    @GetMapping("/stores/{id}/summary") public ApiResponse<StoreSummary> storeSummary(@PathVariable String id,@RequestParam(defaultValue="MINE") String view){return ApiResponse.success(service.storeSummary(id,view));}
    @GetMapping("/summary") public ApiResponse<Summary> summary(@RequestParam(defaultValue="MINE") String view){return ApiResponse.success(service.summary(view));}
    @GetMapping("/contacts") public ApiResponse<Page> list(@RequestParam(defaultValue="MINE") String view,@RequestParam(defaultValue="false") boolean pending,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.list(view,pending,cursor,size));}
    @PostMapping("/contacts/{identity}/bind") public ApiResponse<Contact> bind(@PathVariable String identity,@RequestBody Bind c){return ApiResponse.success(service.bind(identity,c));}
}
