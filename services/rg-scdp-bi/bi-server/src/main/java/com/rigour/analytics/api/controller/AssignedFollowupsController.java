package com.rigour.analytics.api.controller;

import com.rigour.analytics.application.service.AssignedFollowups;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics/me/followups")
public class AssignedFollowupsController {
    private final AssignedFollowups tasks;

    public AssignedFollowupsController(AssignedFollowups tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public ApiResponse<AssignedFollowups.Page> mine(
            @RequestParam(defaultValue = "false") boolean closed,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(tasks.list(closed, page, size));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> report(
            @PathVariable String id, @RequestBody AssignedFollowups.Reply reply) {
        tasks.respond(id, reply);
        return ApiResponse.success(null);
    }
}
