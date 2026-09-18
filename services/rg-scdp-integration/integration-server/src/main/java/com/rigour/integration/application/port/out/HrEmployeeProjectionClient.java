package com.rigour.integration.application.port.out;

import com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolveCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncResult;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;

/** Integration 向 HR 投影外部员工主数据的出站端口。 */
public interface HrEmployeeProjectionClient {
    ExternalEmployeeSyncResult sync(CallerIdentity caller, String sourceSystem,
                                    List<ExternalEmployeeRowCommand> rows);

    default List<ExternalEmployeeResolvedView> resolve(CallerIdentity caller,
                                                       ExternalEmployeeResolveCommand command) {
        return List.of();
    }
}
