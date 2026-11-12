package com.rigour.integration.api.controller.thirdparty;

import com.rigour.integration.api.v1.model.HuolalaModels.*;
import com.rigour.integration.application.service.thirdparty.huolala.HuolalaService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/integration/huolala")
public final class HuolalaController {
    private final HuolalaService service;

    public HuolalaController(HuolalaService service) {
        this.service = service;
    }

    @GetMapping("/connection")
    public ApiResponse<Connection> connection() {
        return ApiResponse.success(service.connection());
    }

    @PutMapping("/connection")
    public ApiResponse<Connection> save(@RequestBody SyncSettings command) {
        return ApiResponse.success(
                service.save(
                        new SaveConnection(
                                null,
                                null,
                                null,
                                false,
                                null,
                                command.enabled(),
                                command.intervalMinutes(),
                                command.startDate(),
                                command.revision())));
    }

    @PostMapping("/authorization")
    public ApiResponse<AuthorizationStart> authorize() {
        return ApiResponse.success(service.authorize());
    }

    @GetMapping("/employees")
    public ApiResponse<List<Employee>> employees() {
        return ApiResponse.success(service.employees());
    }

    @GetMapping("/employee-candidates")
    public ApiResponse<List<EmployeeCandidate>> candidates(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.candidates(keyword));
    }

    @PostMapping("/employee-bindings")
    public ApiResponse<List<Employee>> bind(@RequestBody BindEmployee command) {
        return ApiResponse.success(service.bind(command));
    }

    @GetMapping("/runs")
    public ApiResponse<List<Run>> runs() {
        return ApiResponse.success(service.runs());
    }

    @GetMapping("/runs/{run}/issues")
    public ApiResponse<java.util.List<SyncIssue>> issues(@PathVariable String run) {
        return ApiResponse.success(service.issues(run));
    }

    @PostMapping("/sync")
    public ApiResponse<Run> sync(@RequestBody(required = false) SyncCommand command) {
        return ApiResponse.success(service.sync(command));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(RuntimeException error) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("BAD_REQUEST", error.getMessage(), List.of()));
    }
}
