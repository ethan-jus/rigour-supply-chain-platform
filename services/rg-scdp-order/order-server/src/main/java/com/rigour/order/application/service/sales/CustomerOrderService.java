package com.rigour.order.application.service.sales;
import com.rigour.order.api.v1.CustomerOrders;
import com.rigour.order.application.port.out.CustomerOrderStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.exception.RequestValidationException;
import org.springframework.stereotype.Service;
@Service
public class CustomerOrderService {
 private final CustomerOrderStore store;
 public CustomerOrderService(CustomerOrderStore store){this.store=store;}
 public CustomerOrders read(String id,boolean unpaid,int begin,int size){
  AuthorizationContext.requirePermission("order:read");
  if(id==null||!id.matches("[1-9][0-9]{0,18}")||begin<0||size<1||size>100)throw new RequestValidationException("客户订单查询条件无效");
  return store.read(AuthorizationContext.requireCurrent().tenantId().toString(),id,unpaid,begin,size);
 }
}
