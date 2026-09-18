package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.settings.AppMemberModels.*;

import java.util.List;
import java.util.UUID;

public interface AppMemberStore {
    Page members(Actor a, String keyword, int page, int size, Long departmentId);

    List<Account> accounts(Actor a, String keyword);

    Member save(Actor a, UUID id, Command c);

    void status(Actor a, UUID id, StatusCommand c);

    void delete(Actor a, UUID id, long version);

    BatchPreview preview(Actor a, BatchCommand c);

    void assignBatch(Actor a, BatchCommand c);

    void resetPassword(Actor a, UUID id, PasswordCommand c);
}
