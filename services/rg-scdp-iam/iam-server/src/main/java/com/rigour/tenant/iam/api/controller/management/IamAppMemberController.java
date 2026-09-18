package com.rigour.tenant.iam.api.controller.management;

import com.rigour.tenant.iam.application.port.out.AppEmployeeClient;
import com.rigour.tenant.iam.application.service.settings.AppMemberModels.*;
import com.rigour.tenant.iam.application.service.settings.AppMemberService;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/management/supply")
public final class IamAppMemberController {
    private final AppMemberService service;

    public IamAppMemberController(AppMemberService service) {
        this.service = service;
    }

    @GetMapping("/users")
    public Page members(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) Long departmentId) {
        return service.members(IamAppSettingsController.actor(), keyword, page, pageSize, departmentId);
    }

    @GetMapping("/account-candidates")
    public List<Account> accounts(@RequestParam(required = false) String keyword) {
        return service.accounts(IamAppSettingsController.actor(), keyword);
    }

    @GetMapping("/employee-candidates")
    public AppEmployeeClient.Page employees(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step) {
        return service.employees(IamAppSettingsController.actor(), keyword, begin, step);
    }

    @PostMapping("/users")
    public Member create(@RequestBody Command c) {
        return service.save(IamAppSettingsController.actor(), null, c);
    }

    @PutMapping("/users/{id}")
    public Member update(@PathVariable UUID id, @RequestBody Command c) {
        return service.save(IamAppSettingsController.actor(), id, c);
    }

    @PutMapping("/users/{id}/status")
    public void status(@PathVariable UUID id, @RequestBody StatusCommand c) {
        service.status(IamAppSettingsController.actor(), id, c);
    }

    @DeleteMapping("/users/{id}")
    public void delete(@PathVariable UUID id, @RequestParam long version) {
        service.delete(IamAppSettingsController.actor(), id, version);
    }

    @PostMapping("/users/batch-roles/preview")
    public BatchPreview preview(@RequestBody BatchCommand c) {
        return service.preview(IamAppSettingsController.actor(), c);
    }

    @PostMapping("/users/batch-roles")
    public void batch(@RequestBody BatchCommand c) {
        service.assignBatch(IamAppSettingsController.actor(), c);
    }

    @PostMapping("/users/{id}/password")
    public void password(@PathVariable UUID id, @RequestBody PasswordCommand c) {
        service.resetPassword(IamAppSettingsController.actor(), id, c);
    }
}
