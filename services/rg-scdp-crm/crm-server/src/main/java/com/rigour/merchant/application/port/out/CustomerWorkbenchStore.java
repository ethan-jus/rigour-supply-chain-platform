package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.NearbyModels;

import com.rigour.merchant.api.v1.model.CustomerWorkbenchModels.*;
import com.rigour.merchant.application.service.StoreScope;
import java.time.Instant;

public interface CustomerWorkbenchStore {
    Page customers(StoreScope scope,String kind,Instant from,Instant to,String keyword,String relation,String cursor,int size);
    NearbyModels.Page<Item> nearby(StoreScope scope, String kind, Instant from, Instant to, String keyword, String relation, String cursor, int size, NearbyModels.Origin origin);
    Contacts contacts(StoreScope scope,boolean newlyCreated,Instant from,Instant to,String keyword,String cursor,int size);
    long contactPending(StoreScope scope);
    long customerCount(StoreScope scope,String kind,Instant from,Instant to);
    long newContactCount(StoreScope scope,Instant from,Instant to);
    Orders orders(StoreScope scope,String storeId,String cursor,int size);
}
