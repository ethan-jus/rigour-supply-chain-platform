package com.rigour.order.application.service.sales;

import com.rigour.order.application.port.out.*;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.merchant.api.v1.model.StoreWorkModels.OrderAuthorization;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.shared.core.exception.StateConflictException;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class StoreOrderSubmissionService {
    public record Command(String storeId,String ownershipId,String requestId,int revision){}
    private final StoreOrderSubmissionStore store;
    private final StoreWorkAuthorizationClient crm;
    private final OrderSalesOrderService orders;
    private final Clock clock;
    public StoreOrderSubmissionService(StoreOrderSubmissionStore store,StoreWorkAuthorizationClient crm,OrderSalesOrderService orders,Clock clock){this.store=store;this.crm=crm;this.orders=orders;this.clock=clock;}
    public SalesOrderDetailView submit(long id,Command c){
        var actor=AuthorizationContext.requireCurrent();AuthorizationContext.requirePermission("order:submit");
        if(!"TENANT".equals(actor.principalScope())||actor.userId()==null)throw new com.rigour.shared.context.AuthorizationDeniedException("store-order-user");
        if(c==null||c.storeId()==null||!c.storeId().matches("[1-9][0-9]{0,18}")||c.ownershipId()==null||!c.ownershipId().matches("[1-9][0-9]{0,18}")||c.requestId()==null||!c.requestId().matches("[A-Za-z0-9_-]{16,64}")||c.revision()<1)throw new RequestValidationException("门店订单提交参数无效");
        // 复用订单读范围和正式提交校验；不以 CRM 门店权限代替订单本身的权限。
        orders.salesOrder(id);
        var pending=store.reserve(actor.tenantId().toString(),actor.userId().toString(),id,c.revision(),c.storeId(),c.ownershipId(),c.requestId(),clock.instant());
        if("COMPLETED".equals(pending.status()))return orders.salesOrder(id);
        if("EXPIRED".equals(pending.status()))throw new StateConflictException("订单作业受理已过期，请重新建立草稿");
        var permit=crm.authorize(c.storeId(),new OrderAuthorization(Long.toString(id),c.ownershipId(),c.requestId()));
        try {
            store.authorize(actor.tenantId().toString(),actor.userId().toString(),id,c.requestId(),permit,clock.instant());
            return orders.submit(id,pending.revision());
        } catch (StateConflictException conflict) {
            // 同一请求并发重试时，另一请求可能已经完成正式提交。
            var latest=store.reserve(actor.tenantId().toString(),actor.userId().toString(),id,c.revision(),c.storeId(),c.ownershipId(),c.requestId(),clock.instant());
            if("COMPLETED".equals(latest.status()))return orders.salesOrder(id);
            throw conflict;
        }
    }
}
