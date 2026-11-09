package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.application.port.out.StoreOrderSubmissionStore;
import com.rigour.merchant.api.v1.model.StoreWorkModels.OrderPermit;
import com.rigour.shared.core.exception.StateConflictException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcStoreOrderSubmissionStore implements StoreOrderSubmissionStore {
    private final JdbcTemplate jdbc;
    private final OrderDataScope scopes;
    private final JdbcStoreOrderOutbox outbox;
    // 账期订单正式提交即生效；正常履约后的退货不删除已成立的合作事实。
    public JdbcStoreOrderSubmissionStore(JdbcTemplate jdbc,OrderDataScope scopes,JdbcStoreOrderOutbox outbox){this.jdbc=jdbc;this.scopes=scopes;this.outbox=outbox;}
    @Override @Transactional public Submission reserve(String tenant,String user,long id,int revision,String store,String ownership,String request,Instant now){
        scopes.requireOrder(tenant,id,"order:submit");
        var rows=jdbc.queryForList("SELECT * FROM order_sales_order WHERE tenant_id=? AND id=? AND deleted=0 FOR UPDATE",tenant,id);
        if(rows.isEmpty())throw new StateConflictException("订单不存在");
        var o=rows.getFirst();
        if(o.get("store_request_id")!=null){
            if(!request.equals(o.get("store_request_id"))||!user.equals(o.get("store_operator_user_id"))||!store.equals(Objects.toString(o.get("store_id"),null))||!ownership.equals(Objects.toString(o.get("ownership_id"),null)))throw new StateConflictException("订单已进入其他门店提交流程");
            return new Submission(store,ownership,request,(String)o.get("store_submission_status"),((Number)o.get("revision")).intValue());
        }
        if(!"DRAFT".equals(o.get("order_status_code"))||o.get("source_system_code")!=null||!"COMPLETE".equals(o.get("data_quality_status_code"))||((Number)o.get("revision")).intValue()!=revision)throw new StateConflictException("仅允许资料完整的本系统新草稿按当前版本提交");
        if(((java.math.BigDecimal)o.get("payable_amount")).signum()<=0)throw new StateConflictException("有效成交必须为正商品金额");
        jdbc.update("UPDATE order_sales_order SET store_id=?,ownership_id=?,store_request_id=?,store_operator_user_id=?,store_submission_status='PENDING_AUTH',store_authorization_expires_at=?,revision=revision+1 WHERE tenant_id=? AND id=?",store,ownership,request,user,ts(now.plusSeconds(120)),tenant,id);
        outbox.enqueue(tenant,id);
        return new Submission(store,ownership,request,"PENDING_AUTH",revision+1);
    }
    @Override @Transactional public void authorize(String tenant,String user,long id,String request,OrderPermit p,Instant now){
        int n=jdbc.update("UPDATE order_sales_order SET store_submission_status='AUTHORIZED',store_authorization_revision=?,store_authorized_at=?,store_salesperson_id=?,store_department_id=?,store_name_snapshot=? WHERE tenant_id=? AND id=? AND store_id=? AND ownership_id=? AND store_request_id=? AND store_operator_user_id=? AND store_submission_status IN ('PENDING_AUTH','AUTHORIZED') AND store_authorization_expires_at>? AND order_status_code='DRAFT' AND deleted=0",p.ownershipRevision(),ts(p.acceptedAt()),p.salespersonId(),p.departmentId(),p.storeName(),tenant,id,p.storeId(),p.ownershipId(),request,user,ts(now));
        if(n!=1)throw new StateConflictException("订单受理已过期或状态变化，不能迟到提交");
        outbox.enqueue(tenant,id);
    }
    private static Timestamp ts(Instant i){return Timestamp.from(i);}
}
