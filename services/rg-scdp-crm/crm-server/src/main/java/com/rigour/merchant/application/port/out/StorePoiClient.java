package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.StoreModels.Poi;
import java.util.List;

public interface StorePoiClient {
    List<Poi> search(String tenant, String keyword, String areaCode);
    Poi detail(String tenant, String poiId);
}
