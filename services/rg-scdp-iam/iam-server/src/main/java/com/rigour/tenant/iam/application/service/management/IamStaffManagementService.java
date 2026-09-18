package com.rigour.tenant.iam.application.service.management;

import com.rigour.tenant.iam.application.port.out.IamStaffManagementStore;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.*;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 人员中心用例入口。 */
@Service
public final class IamStaffManagementService {
    private final IamStaffManagementStore store;

    public IamStaffManagementService(IamStaffManagementStore store) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
    }

    public List<PositionView> positions(Actor actor) {
        return store.positions(actor);
    }

    public PositionView createPosition(Actor actor, PositionCommand command) {
        return store.createPosition(actor, command);
    }

    public PositionView updatePosition(Actor actor, UUID id, PositionCommand command) {
        return store.updatePosition(actor, id, command);
    }

    public List<StaffView> staff(Actor actor, String keyword, String status) {
        return store.staff(actor, keyword, status);
    }

    public StaffView createStaff(Actor actor, StaffCommand command) {
        return store.createStaff(actor, command);
    }

    public StaffView updateStaff(Actor actor, UUID id, StaffCommand command) {
        return store.updateStaff(actor, id, command);
    }

    public StaffSyncResultView syncDinghuobaoStaff(Actor actor, DhbStaffSyncRequest request) {
        return store.syncDinghuobaoStaff(actor, request);
    }

    public List<StaffDisplayView> resolveStaffDisplay(Actor actor, StaffDisplayRequest request) {
        return store.resolveStaffDisplay(actor, request);
    }

    public List<DhbStaffResolvedView> resolveDinghuobaoStaff(Actor actor, DhbStaffResolveRequest request) {
        return store.resolveDinghuobaoStaff(actor, request);
    }
}
