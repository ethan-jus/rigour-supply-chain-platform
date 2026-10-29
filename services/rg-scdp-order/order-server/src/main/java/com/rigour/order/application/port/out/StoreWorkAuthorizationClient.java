package com.rigour.order.application.port.out;
import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
public interface StoreWorkAuthorizationClient {
    OrderPermit authorize(String store, OrderAuthorization command);
}
