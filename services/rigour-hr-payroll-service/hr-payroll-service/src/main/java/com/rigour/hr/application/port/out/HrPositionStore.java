package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.api.v1.model.HrPositionCommand;
import com.rigour.hr.api.v1.model.HrPositionView;
import java.util.Optional;

/** HR 岗位职位持久化端口。 */
public interface HrPositionStore {
    HrPageView<HrPositionView> positions(String tenantId, int begin, int step,
                                         PositionSearchCriteria criteria);

    Optional<HrPositionView> position(String tenantId, Long id);

    boolean existsByPositionCode(String tenantId, String positionCode);

    HrPositionView create(String tenantId, String positionCode, HrPositionCommand command, String actorId);

    HrPositionView update(String tenantId, Long id, HrPositionCommand command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    record PositionSearchCriteria(String positionCode,
                                  String positionName,
                                  String positionType,
                                  String statusCode,
                                  String sourceSystem) {
    }
}
