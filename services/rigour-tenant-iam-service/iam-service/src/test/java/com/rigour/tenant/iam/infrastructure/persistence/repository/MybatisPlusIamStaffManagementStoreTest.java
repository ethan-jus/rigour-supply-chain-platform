package com.rigour.tenant.iam.infrastructure.persistence.repository;

import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import com.rigour.tenant.iam.application.service.management.ManagementModels.Actor;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.DhbStaffRowCommand;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.DhbStaffSyncRequest;
import com.rigour.tenant.iam.application.service.management.StaffManagementModels.StaffSyncResultView;
import com.rigour.tenant.iam.infrastructure.persistence.dataobject.ExternalStaffBindingDO;
import com.rigour.tenant.iam.infrastructure.persistence.dataobject.PositionDO;
import com.rigour.tenant.iam.infrastructure.persistence.dataobject.StaffProfileDO;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.ExternalStaffBindingMapper;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.PositionMapper;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.StaffAssignmentMapper;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.StaffManagementMapper;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.StaffProfileMapper;
import com.rigour.tenant.iam.infrastructure.persistence.mapper.StaffUserBindingMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MybatisPlusIamStaffManagementStoreTest {

    private static final UUID TENANT_ID = UUID.fromString("019facf0-0000-7000-8000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("019facf1-0000-7000-8000-000000000002");

    @Mock
    private PositionMapper positionMapper;
    @Mock
    private StaffProfileMapper staffProfileMapper;
    @Mock
    private StaffAssignmentMapper staffAssignmentMapper;
    @Mock
    private StaffUserBindingMapper staffUserBindingMapper;
    @Mock
    private ExternalStaffBindingMapper externalStaffBindingMapper;
    @Mock
    private StaffManagementMapper staffManagementMapper;

    @Test
    void dinghuobaoStaffSyncDoesNotCreateIamPositionFromSourceTitle() {
        when(staffProfileMapper.selectCount(any())).thenReturn(0L);
        when(externalStaffBindingMapper.selectOne(any())).thenReturn(null);
        when(staffProfileMapper.insert(any(StaffProfileDO.class))).thenReturn(1);
        when(externalStaffBindingMapper.insert(any(ExternalStaffBindingDO.class))).thenReturn(1);

        StaffSyncResultView result = store().syncDinghuobaoStaff(serviceActor(),
                new DhbStaffSyncRequest(List.of(new DhbStaffRowCommand(
                        null, "DEFAULT", "dhb-001", "salesman", "外部账号",
                        "订货宝员工", "业务员", "部门", null, null,
                        "role", null, null, null, null, "T",
                        null, null, "hash-1", "{}"))));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(positionMapper, never()).selectOne(any());
        verify(positionMapper, never()).insert(any(PositionDO.class));
    }

    private MybatisPlusIamStaffManagementStore store() {
        AtomicLong sequence = new AtomicLong(1);
        IdentifierGenerator ids = () -> UUID.nameUUIDFromBytes(
                ("iam-store-test-" + sequence.getAndIncrement()).getBytes(StandardCharsets.UTF_8));
        return new MybatisPlusIamStaffManagementStore(positionMapper, staffProfileMapper, staffAssignmentMapper,
                staffUserBindingMapper, externalStaffBindingMapper, staffManagementMapper, ids);
    }

    private static Actor serviceActor() {
        return new Actor("SERVICE", ACTOR_ID, TENANT_ID);
    }
}
