package com.rigour.tenant.iam.api.v1.model;
import java.util.List;
/** 域服务切换检查契约。只返回计数与版本，不输出业务明细或开放任意 SQL。 */
public record SupplyReadinessView(String domain,int contractVersion,String version,List<Check> checks) {
 public record Check(String code,String severity,long count,String message) {}
}
