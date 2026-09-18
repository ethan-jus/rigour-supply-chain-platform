package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskModels.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 后台风控专用接口，复用数据库会话与 CSRF 过滤器，禁止匿名登记和原始浏览器凭据输出。 */
@RestController
@RequestMapping("/sales-checkin/admin/api/v1/risk")
@ConditionalOnProperty(prefix="rigour.sales.temporary-checkin",name="enabled",havingValue="true")
public class TemporaryCheckinRiskController {
    private final TemporaryCheckinRiskService service;
    private final TemporaryCheckinAdminAccessPolicy access;
    public TemporaryCheckinRiskController(TemporaryCheckinRiskService service,TemporaryCheckinAdminAccessPolicy access) {this.service=service;this.access=access;}
    @GetMapping("/summaries")
    public ResponseEntity<BatchSummary> summaries(HttpServletRequest request,@RequestParam(name="submissionIds") String rawIds,
            @RequestParam(name="from",required=false) LocalDate from,@RequestParam(name="to",required=false) LocalDate to,
            @RequestParam(name="city",required=false) String city,@RequestParam(name="salespersonId",required=false) UUID sales) {
        var scope=access.requireScope(request);
        List<UUID> ids;
        try {if(rawIds.length()>3700)throw new IllegalArgumentException();ids=rawIds.isBlank()?List.of():Arrays.stream(rawIds.split(",",-1)).map(UUID::fromString).toList();}
        catch(IllegalArgumentException invalid){throw TemporaryCheckinException.badRequest("拜访编号无效或超过100条");}
        service.ensureRegistry();return noStore(service.summariesPrepared(scope,ids,from,to,city,sales));
    }
    @GetMapping({"/devices","/audios"})
    public ResponseEntity<Page<GroupSummary>> groups(HttpServletRequest request,
            @RequestParam(name="from",required=false) LocalDate from,@RequestParam(name="to",required=false) LocalDate to,
            @RequestParam(name="city",required=false) String city,@RequestParam(name="salespersonId",required=false) UUID sales,
            @RequestParam(name="q",required=false) String q,@RequestParam(name="duplicateOnly",defaultValue="false") boolean duplicate,
            @RequestParam(name="crossSalespersonOnly",defaultValue="false") boolean crossSales,
            @RequestParam(name="multiSalespersonOnly",defaultValue="false") boolean multiSales,
            @RequestParam(name="reviewStatus",required=false) String reviewStatus,
            @RequestParam(name="sortBy",required=false) String sort,@RequestParam(name="sortDirection",required=false) String direction,
            @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
        var scope=access.requireScope(request);boolean device=request.getRequestURI().endsWith("/devices");service.ensureRegistry();
        return noStore(service.list(scope,device?"DEVICE":"AUDIO",from,to,city,sales,q,!device&&duplicate,device?multiSales:crossSales,reviewStatus,sort,direction,page,size));
    }
    @GetMapping({"/devices/{id}","/audios/{id}"})
    public ResponseEntity<GroupDetail> detail(HttpServletRequest request,@PathVariable("id") long id,
            @RequestParam(name="from",required=false) LocalDate from,@RequestParam(name="to",required=false) LocalDate to,
            @RequestParam(name="city",required=false) String city,@RequestParam(name="salespersonId",required=false) UUID sales,
            @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size,
            @RequestParam(name="timelineScope",defaultValue="HISTORY") String timeline,@RequestParam(name="submissionId",required=false) UUID context,
            @RequestParam(name="salespersonChangesOnly",defaultValue="false") boolean changesOnly) {
        var scope=access.requireScope(request);service.ensureRegistry();return noStore(service.detail(scope,request.getRequestURI().contains("/devices/")?"DEVICE":"AUDIO",
                id,from,to,city,sales,page,size,timeline,context,changesOnly));
    }
    @GetMapping("/devices/{id}/identity-events")
    public ResponseEntity<IdentityEventPage> identityEvents(HttpServletRequest request,@PathVariable("id") long id,
            @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size,
            @RequestParam(name="salespersonChangesOnly",defaultValue="false") boolean changesOnly) {
        return noStore(service.identityEvents(access.requireScope(request),id,changesOnly,page,size));
    }
    @PostMapping("/devices/{id}/assignments")
    public ResponseEntity<AssignmentEvent> assign(HttpServletRequest request,@PathVariable("id") long id,@RequestBody AssignmentRequest body) {
        return noStore(service.assign(access.requireScope(request),id,body));
    }
    @GetMapping("/devices/{id}/assignments")
    public ResponseEntity<Page<AssignmentEvent>> assignments(HttpServletRequest request,@PathVariable("id") long id,
            @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
        return noStore(service.assignments(access.requireScope(request),id,page,size));
    }
    @PostMapping("/groups/{kind}/{id}/reviews")
    public ResponseEntity<ReviewEvent> review(HttpServletRequest request,@PathVariable("kind") String kind,@PathVariable("id") long id,@RequestBody ReviewRequest body) {
        return noStore(service.review(access.requireScope(request),kind,id,body));
    }
    @GetMapping("/groups/{kind}/{id}/reviews")
    public ResponseEntity<Page<ReviewEvent>> reviews(HttpServletRequest request,@PathVariable("kind") String kind,@PathVariable("id") long id,
            @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size) {
        return noStore(service.reviews(access.requireScope(request),kind,id,page,size));
    }
    private static <T> ResponseEntity<T> noStore(T body) {return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(body);}
}
