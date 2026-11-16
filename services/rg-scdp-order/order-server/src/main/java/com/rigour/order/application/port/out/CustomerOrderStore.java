package com.rigour.order.application.port.out;
import com.rigour.order.api.v1.CustomerOrders;
public interface CustomerOrderStore { CustomerOrders read(String tenant,String storeId,boolean unpaidOnly,int begin,int size); }
