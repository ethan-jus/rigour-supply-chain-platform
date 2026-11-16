package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.NearbyModels;

import com.rigour.merchant.api.v1.model.StoreModels.Poi;
import java.util.List;

public interface StorePoiClient {
    NearbyModels.Coordinates convertGps(String tenant, double longitude, double latitude);
    List<Poi> search(String tenant, String keyword, String areaCode);
    Poi detail(String tenant, String poiId);
}
