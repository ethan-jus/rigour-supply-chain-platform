package com.rigour.tenant.iam.application.service.settings;

import com.rigour.tenant.iam.application.port.out.*;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppMemberModels.*;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public final class AppMemberService {
    private final AppMemberStore store;
    private final AppEmployeeClient employees;
    private final AppSettingsStore settings;

    public AppMemberService(
            AppMemberStore store, AppEmployeeClient employees, AppSettingsStore settings) {
        this.store = store;
        this.employees = employees;
        this.settings = settings;
    }

    public Page members(Actor a, String keyword, int page, int size, Long departmentId) {
        return store.members(a, keyword, page, size, departmentId);
    }

    public List<Account> accounts(Actor a, String keyword) {
        return store.accounts(a, keyword);
    }

    public AppEmployeeClient.Page employees(Actor a, String keyword, int begin, int step) {
        var permissions = settings.context(a).permissions();
        if (!permissions.contains("supply:user:create")
                && !permissions.contains("supply:user:rebind"))
            throw new org.springframework.security.access.AccessDeniedException("无权选择员工");
        if (begin < 0 || step < 1 || step > 100) throw new IllegalArgumentException("员工分页参数无效");
        return employees.search(a.tenantId(), keyword, begin, step);
    }

    public Member save(Actor a, UUID id, Command c) {
        return store.save(a, id, c);
    }

    public void status(Actor a, UUID id, StatusCommand c) {
        store.status(a, id, c);
    }

    public void delete(Actor a, UUID id, long v) {
        store.delete(a, id, v);
    }

    public BatchPreview preview(Actor a, BatchCommand c) {
        return store.preview(a, c);
    }

    public void assignBatch(Actor a, BatchCommand c) {
        store.assignBatch(a, c);
    }

    public void resetPassword(Actor a, UUID id, PasswordCommand c) {
        store.resetPassword(a, id, c);
    }
}
