package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.HistorySyncModels.*;

/** Order独占历史关联与资金接续持久化，不读取其他领域Schema。 */
public interface OrderHistorySyncStore {
    StoreView overview(String tenant, Long customerId);

    Intake sourceOrder(String tenant, SourceOrder c);

    Intake cancelSourceOrder(String tenant, String actor, CancelSourceOrder c);

    void confirmNew(String tenant, String actor, NewOrder c);

    void confirmHistoricalNew(String tenant, String actor, NewOrder c);

    void deleteUnlinkedHistory(String tenant, String actor, DeleteUnlinkedHistory c);

    java.util.Map<String, Long> normalizeGroup(String tenant, String actor, NormalizeGroup c);

    String bind(String tenant, String actor, Bind c);

    Intake receipt(String tenant, Receipt c);

    void allocate(String tenant, String actor, Allocate c);

    void allocateProducts(String tenant, String actor, AllocateProducts c);

    Performance performance(String tenant, String month);

    void confirmOwner(String tenant, String actor, OwnerReview c);
}
