package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.model.StoreContactModels.*;
import com.rigour.merchant.application.service.StoreContactService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/crm/stores/{store}")
public class StoreContactController {
    private final StoreContactService service;
    public StoreContactController(StoreContactService service){this.service=service;}
    @GetMapping("/contacts") public ApiResponse<List<Contact>> list(@PathVariable String store,@RequestParam(defaultValue="MINE") String view){return ApiResponse.success(service.list(store,view));}
    @PostMapping("/contacts") public ApiResponse<Contact> create(@PathVariable String store,@RequestParam(defaultValue="MINE") String view,@RequestBody Create c){return ApiResponse.success(service.create(store,view,c));}
    @PutMapping("/contacts/{contact}") public ApiResponse<Contact> update(@PathVariable String store,@PathVariable String contact,@RequestParam(defaultValue="MINE") String view,@RequestBody Update c){return ApiResponse.success(service.update(store,contact,view,c));}
    @PostMapping("/contacts/link") public ApiResponse<Contact> link(@PathVariable String store,@RequestParam(defaultValue="MINE") String view,@RequestBody Link c){return ApiResponse.success(service.link(store,view,c));}
    @PostMapping("/contacts/{contact}/methods") public ApiResponse<Contact> add(@PathVariable String store,@PathVariable String contact,@RequestParam(defaultValue="MINE") String view,@RequestBody AddMethod c){return ApiResponse.success(service.addMethod(store,contact,view,c));}
    @PutMapping("/contacts/{contact}/methods/{method}/verification") public ApiResponse<Contact> verify(@PathVariable String store,@PathVariable String contact,@PathVariable String method,@RequestParam(defaultValue="MINE") String view,@RequestBody VerifyMethod c){return ApiResponse.success(service.verifyMethod(store,contact,method,view,c));}
    @GetMapping("/contacts/{contact}/changes") public ApiResponse<List<Change>> changes(@PathVariable String store,@PathVariable String contact,@RequestParam(defaultValue="MINE") String view){return ApiResponse.success(service.changes(store,contact,view));}
    @GetMapping("/visits/{visit}/contacts") public ApiResponse<List<Contact>> reception(@PathVariable String store,@PathVariable String visit,@RequestParam(defaultValue="MINE") String view){return ApiResponse.success(service.reception(store,visit,view));}
    @PutMapping("/visits/{visit}/contacts") public ApiResponse<List<Contact>> reception(@PathVariable String store,@PathVariable String visit,@RequestBody Reception c){return ApiResponse.success(service.reception(store,visit,c));}
}
