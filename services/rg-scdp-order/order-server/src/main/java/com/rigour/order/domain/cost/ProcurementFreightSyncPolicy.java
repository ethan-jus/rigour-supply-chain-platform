package com.rigour.order.domain.cost;
import com.rigour.order.api.v1.model.LogisticsCostLedger.SourceSync;
import java.util.Objects;
/** 人工修改和已确认的实际费用始终优先于采购约定。 */
public final class ProcurementFreightSyncPolicy {
    private ProcurementFreightSyncPolicy(){}
    public enum Decision { UNCHANGED, REPLACE_DRAFT, SOURCE_DIFFERENCE }
    public static Decision decide(String status,SourceSync old,SourceSync incoming){
        if(old==null || incoming==null || !Objects.equals(old.originBizKey(),incoming.originBizKey()))throw new IllegalArgumentException("采购费用来源标识不一致");
        if(incoming.originVersion()<old.lastSeenVersion())return Decision.UNCHANGED;
        if(Objects.equals(incoming.lastAppliedFingerprint(),old.lastSeenFingerprint()))return Decision.UNCHANGED;
        return "DRAFT".equals(status) && !old.manuallyChanged()?Decision.REPLACE_DRAFT:Decision.SOURCE_DIFFERENCE;
    }
}
