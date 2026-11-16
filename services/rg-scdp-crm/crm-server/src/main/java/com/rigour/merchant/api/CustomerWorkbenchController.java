package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.model.NearbyModels;

import com.rigour.merchant.api.v1.model.CustomerWorkbenchModels.*;
import com.rigour.merchant.application.service.CustomerWorkbenchService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/crm/customer-workbench")
public class CustomerWorkbenchController {
    private final CustomerWorkbenchService service;
    public CustomerWorkbenchController(CustomerWorkbenchService service){this.service=service;}
    @GetMapping("/public-count") public ApiResponse<PublicCount> publicCount(){return ApiResponse.success(service.publicCount());}
    @GetMapping("/overview") public ApiResponse<Summary> summary(@RequestParam LocalDate from,@RequestParam LocalDate to){return ApiResponse.success(service.summary(from,to));}
    @GetMapping public ApiResponse<Page> list(@RequestParam String kind,@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) String relation,@RequestParam(required=false) String cursor,
        @RequestParam(defaultValue="20") int size){return ApiResponse.success(service.customers(kind,from,to,keyword,relation,cursor,size));}
    @GetMapping("/nearby") public ApiResponse<NearbyModels.Page<Item>> nearby(@RequestParam String kind,@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) String relation,@RequestParam(defaultValue="20") int size,
        @ModelAttribute("near") NearbyModels.Query near){return ApiResponse.success(service.nearby(kind,from,to,keyword,relation,size,near));}
    @GetMapping("/contacts") public ApiResponse<Contacts> contacts(@RequestParam(defaultValue="false") boolean newly,@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(required=false) String keyword,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.contacts(newly,from,to,keyword,cursor,size));}
    @GetMapping("/{id}/orders") public ApiResponse<Orders> orders(@PathVariable String id,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size){return ApiResponse.success(service.orders(id,cursor,size));}
}
