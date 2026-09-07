package com.rigour.sales.temporarycheckin;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 真实Office Open XML导出；客户端无法通过导出扩大城市或租户权限。 */
@RestController
@ConditionalOnProperty(prefix="rigour.sales.temporary-checkin",name="enabled",havingValue="true")
class TemporaryCheckinWorkbookController {
    private final TemporaryCheckinWorkbookService service;
    private final TemporaryCheckinAdminAccessPolicy access;
    TemporaryCheckinWorkbookController(TemporaryCheckinWorkbookService service,TemporaryCheckinAdminAccessPolicy access) {
        this.service=service;this.access=access;
    }
    @GetMapping("/sales-checkin/admin/export.xlsx")
    ResponseEntity<byte[]> export(HttpServletRequest request,
            @RequestParam(name="from",required=false) LocalDate from,
            @RequestParam(name="to",required=false) LocalDate to,
            @RequestParam(name="city",required=false) String city,
            @RequestParam(name="salespersonId",required=false) UUID salespersonId,
            @RequestParam(name="status",required=false) String status,
            @RequestParam(name="visitType",required=false) String visitType,
            @RequestParam(name="q",required=false) String query,
            @RequestParam(name="locationStatus",required=false) String locationStatus,
            @RequestParam(name="reviewStatus",required=false) String reviewStatus,
            @RequestParam(name="mediaStatus",required=false) String mediaStatus,
            @RequestParam(name="sortBy",required=false) String sortBy,
            @RequestParam(name="sortDirection",required=false) String sortDirection) {
        byte[] body=service.export(access.requireScope(request),from,to,city,salespersonId,status,visitType,query,
                new TemporaryCheckinRepository.AdminReadOptions(locationStatus,reviewStatus,mediaStatus,sortBy,sortDirection));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(body.length).header(HttpHeaders.CACHE_CONTROL,"no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                        .filename("销售拜访打卡统计.xlsx",StandardCharsets.UTF_8).build().toString()).body(body);
    }
}
