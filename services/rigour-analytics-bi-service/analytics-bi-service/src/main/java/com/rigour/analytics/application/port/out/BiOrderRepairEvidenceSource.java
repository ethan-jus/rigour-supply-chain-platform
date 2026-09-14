package com.rigour.analytics.application.port.out;

import com.rigour.order.api.v1.model.SalesOrderProductRepair.Evidence;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;

/** Order owns repair confirmations; BI reads current evidence without writing business records. */
public interface BiOrderRepairEvidenceSource {
    List<Evidence> current(CallerIdentity actor);
}
