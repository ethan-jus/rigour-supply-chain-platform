package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.NearbyModels;

import com.rigour.merchant.api.v1.model.StoreModels.Poi;
import java.util.List;

public interface StorePoiClient {
    NearbyModels.Coordinates convertGps(String tenant, double longitude, double latitude);
    List<Poi> search(String tenant, String keyword, String areaCode);
    List<Poi> around(String tenant, String keyword, double longitude, double latitude, int radiusMeters);
    com.rigour.merchant.api.v1.model.StoreModels.IntakeLocation resolve(String tenant, double longitude, double latitude, String system);
    Poi detail(String tenant, String poiId);
}
