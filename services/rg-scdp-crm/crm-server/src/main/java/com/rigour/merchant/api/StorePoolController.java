package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.application.service.StorePoolService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/crm/stores")
public class StorePoolController {
    private final StorePoolService service;
    public StorePoolController(StorePoolService service){this.service=service;}
    @GetMapping("/context")
    public ApiResponse<Context> context(@RequestParam(defaultValue="PUBLIC")String view){return ApiResponse.success(service.context(view));}
    @GetMapping
    public ApiResponse<Page> list(@ModelAttribute Query query){return ApiResponse.success(service.list(query));}
    @GetMapping("/map")
    public ApiResponse<MapResult> map(@ModelAttribute Query query,@ModelAttribute MapWindow window){return ApiResponse.success(service.map(query,window));}
    @GetMapping("/map/stores")
    public ApiResponse<Page> mapStores(@ModelAttribute Query query,@ModelAttribute MapWindow window,@RequestParam String coordinateSystem){return ApiResponse.success(service.mapStores(query,window,coordinateSystem));}
    @GetMapping("/{id}")
    public ApiResponse<Row> detail(@PathVariable String id,@RequestParam(defaultValue="PUBLIC")String view){return ApiResponse.success(service.detail(id,view));}
    @GetMapping("/{id}/ownerships")
    public ApiResponse<List<Ownership>> ownerships(@PathVariable String id,@RequestParam(defaultValue="PUBLIC")String view){return ApiResponse.success(service.ownerships(id,view));}
    @PostMapping("/{id}/claim")
    public ApiResponse<Void> claim(@PathVariable String id,@RequestBody Claim command){service.claim(id,command);return ApiResponse.success(null);}
    @PostMapping("/{id}/release")
    public ApiResponse<Void> release(@PathVariable String id,@RequestBody Release command){service.release(id,command);return ApiResponse.success(null);}
    @GetMapping("/leads")
    public ApiResponse<List<Lead>> leads(@RequestParam(defaultValue="false")boolean review){return ApiResponse.success(service.leads(review));}
    @PostMapping("/leads")
    public ApiResponse<Lead> submit(@RequestBody LeadCommand command){return ApiResponse.success(service.submit(command));}
    @PostMapping("/leads/{id}/review")
    public ApiResponse<Lead> review(@PathVariable String id,@RequestBody Review command){return ApiResponse.success(service.review(id,command));}
    @GetMapping("/coverage")
    public ApiResponse<Coverage> coverage(@ModelAttribute Query query){return ApiResponse.success(service.coverage(query));}
    @GetMapping("/{id}/events")
    public ApiResponse<List<Event>> events(@PathVariable String id,@RequestParam(defaultValue="PUBLIC")String view){return ApiResponse.success(service.events(id,view));}
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable String id,@RequestBody Update command){service.update(id,command);return ApiResponse.success(null);}
    @PostMapping("/policy/initialize")
    public ApiResponse<Void> initialize(){service.initializePolicy();return ApiResponse.success(null);}
}
