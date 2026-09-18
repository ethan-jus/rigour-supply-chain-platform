package com.rigour.tenant.iam.application.port.out;

import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.*;
import java.util.List;
import java.util.UUID;

/** 人员中心持久化端口；实现必须按租户和IAM权限隔离。 */
public interface IamStaffManagementStore {
    List<PositionView> positions(Actor actor);
    PositionView createPosition(Actor actor, PositionCommand command);
    PositionView updatePosition(Actor actor, UUID id, PositionCommand command);
    List<StaffView> staff(Actor actor, String keyword, String status);
    StaffView createStaff(Actor actor, StaffCommand command);
    StaffView updateStaff(Actor actor, UUID id, StaffCommand command);
    StaffSyncResultView syncDinghuobaoStaff(Actor actor, DhbStaffSyncRequest request);
    List<StaffDisplayView> resolveStaffDisplay(Actor actor, StaffDisplayRequest request);
    List<DhbStaffResolvedView> resolveDinghuobaoStaff(Actor actor, DhbStaffResolveRequest request);
}
