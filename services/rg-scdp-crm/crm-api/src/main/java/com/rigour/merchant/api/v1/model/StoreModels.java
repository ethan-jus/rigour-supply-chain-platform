package com.rigour.merchant.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class StoreModels {
    private StoreModels() {}
    public record Area(String code, String name, String level, String parentCode, String regionCode) {}
    public record Context(String areaVersion, String scopeLabel, boolean national, List<Area> areas,
            Set<String> permissions, String employeeId, String emptyScopeReason) {}
    public record Row(String id, String name, String address, String publicPhone,
            String storeTypeCode, String primaryPoi, Instant createdAt, String createdBy, Instant updatedAt, String updatedBy,
            String areaCode, String provinceName, String cityName, String countyName,
            String regionCode, BigDecimal longitude, BigDecimal latitude, String coordinateSystem,
            String recordStatus, String poolType, String ownerEmployeeId, String ownerName,
            String cooperationStatus, String customerTier, String tierUnratedReason,
            String activityStatus, String potentialLevel, Instant lastVisitAt, Instant lastOrderAt,
            String phase, Instant deadlineAt, Instant cooldownUntil, String claimBlockedReason,
            long revision, String currentOwnershipId, String holdReason, Instant holdUntil, Long validVisitCount, String businessStatus, String businessStatusReason, Instant firstCooperatedAt) {}
    public record StoreMatch(String id,String name,String address,String areaCode,String provinceName,String cityName,
            String countyName,String recordStatus,String businessStatus,String poolType) {}
    public record Page(long total, List<Row> items, String nextCursor) {}
    public record LeadPage(long total, List<Lead> items, int page, int size) {}
    public record Query(String view, String keyword, String region, String province, String city,
            String county, String cooperation, String tier, String activity, String potential,
            String cursor, int size, String storeType, String pool, String recordStatus,
            String phase, String protection, String businessStatus) {
        public Query(String view,String keyword,String region,String province,String city,String county,String cooperation,String tier,String activity,String potential,String cursor,int size,String storeType,String pool,String recordStatus,String phase,String protection) {
            this(view,keyword,region,province,city,county,cooperation,tier,activity,potential,cursor,size,storeType,pool,recordStatus,phase,protection,null);
        }
        public Query(String view, String keyword, String region, String province, String city,
                String county, String cooperation, String tier, String activity, String potential,
                String cursor, int size, String storeType) {
            this(view, keyword, region, province, city, county, cooperation, tier, activity, potential,
                    cursor, size, storeType, null, null, null, null);
        }
        public Query(String view, String keyword, String region, String province, String city,
                String county, String cooperation, String tier, String activity, String potential,
                String cursor, int size) {
            this(view, keyword, region, province, city, county, cooperation, tier, activity, potential, cursor, size, null);
        }
    }
    public record MapWindow(Double west, Double south, Double east, Double north, Double zoom) {}
    public record MapBounds(double west, double south, double east, double north) {}
    public record MapPoint(String id, String storeId, String name, double longitude, double latitude,
            String coordinateSystem, long count, long privateCount, long cooperatedCount, MapBounds bounds,
            String areaCode, String areaLevel) {}
    public record MapResult(long total, long located, long visible, MapBounds bounds, List<MapPoint> points, String level) {}
    public record Claim(String requestId, long revision) {}
    public record Release(String requestId, long revision, String reason) {}
    public record LeadCommand(String requestId, String name, String address, String areaCode,
            BigDecimal longitude, BigDecimal latitude, String publicPhone, String storeTypeCode, String coordinateSystem) {
        public LeadCommand(String requestId,String name,String address,String areaCode,BigDecimal longitude,BigDecimal latitude,String publicPhone) {
            this(requestId,name,address,areaCode,longitude,latitude,publicPhone,null,"GCJ02");
        }
    }
    public record AmapAdmission(String requestId, String poiId) {}
    public record Admission(String storeId, String name) {}
    public record BusinessStatusChange(String requestId,long revision,String status,String reason,boolean management) {}
    public record BusinessStatusUpdate(String status,String reason) {}
    public record Resubmit(long revision, LeadCommand proposal) {}
    public record Location(String areaCode, BigDecimal longitude, BigDecimal latitude, String coordinateSystem) {}
    public record Poi(String id, String name, String address, String publicPhone, String typeCode,
            String areaCode, BigDecimal longitude, BigDecimal latitude) {}
    public record Update(long revision, String requestId, String name, String address, String publicPhone,
            String storeTypeCode, String reason, Location location, String amapPoiId, BusinessStatusUpdate businessStatus) {
        public Update(long revision, String requestId, String name, String address, String publicPhone, String storeTypeCode, String reason, Location location, String amapPoiId) {
            this(revision,requestId,name,address,publicPhone,storeTypeCode,reason,location,amapPoiId,null);
        }
        public Update(long revision, String requestId, String name, String address, String publicPhone, String storeTypeCode, String reason) {
            this(revision,requestId,name,address,publicPhone,storeTypeCode,reason,null,null,null);
        }
    }
    public record Event(String id, String type, String actorUserId, Instant occurredAt, String beforeData, String afterData, String reason) {}
    public record Review(String requestId, long revision, boolean approve, String reason, String existingStoreId, boolean distinctConfirmed) {
        public Review(String requestId,long revision,boolean approve,String reason) {this(requestId,revision,approve,reason,null,false);}
    }
    public record Lead(String id, String name, String address, String areaCode,
            BigDecimal longitude, BigDecimal latitude, String status, Instant submittedAt,
            Instant reviewedAt, String reviewReason, String resultStoreId, long revision, String publicPhone, String storeTypeCode, String coordinateSystem, String submitterUserId, String reviewerUserId) {}
    public record Ownership(String id, String ownerEmployeeId, String acquireType, String phase,
            Instant startedAt, Instant endedAt, String endReason, Instant deadlineAt) {}
    public record Coverage(long total, long publicCount, long privateCount, long cooperatedCount,
            long visitedCount, long connectedCount, long tierA, long tierB, long tierC,
            long unratedCount, long unknownVisitCount, long unknownWecomCount) {}
}
