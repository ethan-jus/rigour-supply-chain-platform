package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.*;
import java.util.List;

public interface CostLedgerReferenceStore {
    List<CostLedgerPartyView> parties(String tenant, String kind, String code,
            Long id, String keyword, int begin, String action);
    List<ErpCostSourceView> sources(String tenant, CostLedgerSourceQuery query, String action);
}
