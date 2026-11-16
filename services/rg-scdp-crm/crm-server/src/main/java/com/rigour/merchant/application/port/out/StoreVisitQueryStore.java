package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.StoreVisitQueryModels.Page;
import com.rigour.merchant.application.service.StoreScope;
import java.time.Instant;

public interface StoreVisitQueryStore {
    Page list(StoreScope scope,Instant from,Instant to,String kind,String status,String keyword,int page,int size);
}
