package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAccessPolicy.AdminScope;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAddressRepository.AddressRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAddressRepository.CacheRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinReverseGeocoder.GeocodeResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 管理员显式补解析设备采样点。已有快照优先，按租户缓存和限额，不自动扫描历史或更改定位质量。 */
@Service
@ConditionalOnProperty(prefix="rigour.sales.temporary-checkin",name="enabled",havingValue="true")
class TemporaryCheckinAddressService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final UUID tenant;
    private final TemporaryCheckinAddressRepository repository;
    private final TemporaryCheckinReverseGeocoder geocoder;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int dailyLimit;
    private final Duration retryCooldown;

    TemporaryCheckinAddressService(TemporaryCheckinProperties properties, TemporaryCheckinAddressRepository repository,
            TemporaryCheckinReverseGeocoder geocoder, ObjectMapper mapper, Clock clock,
            @Value("${rigour.sales.temporary-checkin.address-resolution.daily-limit:100}") int dailyLimit,
            @Value("${rigour.sales.temporary-checkin.address-resolution.retry-cooldown-minutes:15}") int retryMinutes) {
        if (dailyLimit < 0 || dailyLimit > 10_000 || retryMinutes < 1 || retryMinutes > 1_440)
            throw new IllegalStateException("地址补解析配额或重试冷却配置无效");
        this.tenant=properties.requireTenantId(); this.repository=repository; this.geocoder=geocoder;
        this.mapper=mapper; this.clock=clock; this.dailyLimit=dailyLimit; this.retryCooldown=Duration.ofMinutes(retryMinutes);
    }

    AddressView get(AdminScope scope, UUID id) {
        AddressRow row = require(scope,id);
        if (hasText(row.address())) return resolved(row,true);
        if (row.longitude()==null || row.latitude()==null) return unavailable(row,"MISSING_COORDINATES",null,null);
        CacheRow cached = repository.cache(tenant,TemporaryCheckinAddressRepository.coordinateHash(row.longitude(),row.latitude()));
        if (cached == null) return unavailable(row,"UNRESOLVED",null,null);
        // GET 无写入、无网络请求；缓存已有答案也须由显式 POST 确认附到当前记录。
        if ("RESOLVED".equals(cached.status())) return unavailable(row,"UNRESOLVED",null,null);
        if ("PROCESSING".equals(cached.status()) && (cached.leaseUntil()==null || !cached.leaseUntil().isAfter(clock.instant())))
            return unavailable(row,"UNRESOLVED",null,"PREVIOUS_ATTEMPT_INTERRUPTED");
        return unavailable(row,cached.status(),cached.retryAt()==null ? cached.leaseUntil() : cached.retryAt(),error(cached));
    }

    AddressView resolve(AdminScope scope, UUID id) {
        AddressRow row = require(scope,id);
        if (hasText(row.address())) return resolved(row,true);
        if (row.longitude()==null || row.latitude()==null) return unavailable(row,"MISSING_COORDINATES",null,null);
        String key = TemporaryCheckinAddressRepository.coordinateHash(row.longitude(),row.latitude());
        CacheRow cached = repository.cache(tenant,key);
        if (cached!=null && "RESOLVED".equals(cached.status())) return apply(scope,row,key,cached,true);
        Instant now=clock.instant();
        LocalDate day=now.atZone(ZONE).toLocalDate();
        var claim=repository.claim(tenant,row,key,scope.username(),day,now,dailyLimit,now.plus(LEASE));
        if ("RESOLVED".equals(claim.status())) return apply(scope,row,key,claim.cached(),true);
        if (!"CLAIMED".equals(claim.status())) {
            Instant retryAt="QUOTA_EXCEEDED".equals(claim.status()) ? day.plusDays(1).atStartOfDay(ZONE).toInstant() : claim.retryAt();
            return unavailable(row,claim.status(),retryAt,error(claim.cached()));
        }
        GeocodeResult result;
        try {
            result=geocoder.resolve(row.longitude(),row.latitude());
            if (result==null || ("RESOLVED".equals(result.status()) && !hasText(result.address())))
                result=GeocodeResult.failed("AMAP_ADDRESS_EMPTY");
        } catch (RuntimeException exception) {
            // 不把第三方响应、请求 URL 或密钥带进错误响应。
            result=GeocodeResult.failed("ADDRESS_RESOLUTION_FAILED");
        }
        Instant finished=clock.instant();
        boolean success="RESOLVED".equals(result.status());
        Instant retryAt=finished.plus(retryCooldown);
        if (!repository.finish(tenant,key,claim.token(),mapper.writeValueAsString(result),success,finished,retryAt))
            return unavailable(require(scope,id),"PROCESSING",finished.plusSeconds(5),null);
        CacheRow written=repository.cache(tenant,key);
        if (success) return apply(scope,row,key,written,false);
        // 重新核验删除/城市范围，网络等待期间记录变化不得回传记录内容。
        return unavailable(require(scope,id),"FAILED",retryAt,result.errorCode());
    }

    BatchAddressView resolveBatch(AdminScope scope, List<UUID> ids) {
        if (ids==null || ids.isEmpty() || ids.size()>5 || ids.stream().anyMatch(java.util.Objects::isNull)
                || ids.stream().distinct().count()!=ids.size())
            throw TemporaryCheckinException.badRequest("每批请选择1至5条不同的拜访记录");
        ids.forEach(id -> require(scope,id)); // 全批先验证权限，防止部分付费后才发现越权。
        List<AddressView> items=new ArrayList<>();
        for (UUID id:ids) items.add(resolve(scope,id));
        return new BatchAddressView(List.copyOf(items),items.size(),items.stream().filter(item -> "RESOLVED".equals(item.status())).count());
    }

    private AddressView apply(AdminScope scope, AddressRow row, String key, CacheRow cached, boolean cacheHit) {
        AddressRow current=require(scope,row.id());
        if (hasText(current.address())) return resolved(current,true);
        if (current.longitude()==null || current.latitude()==null || !key.equals(
                TemporaryCheckinAddressRepository.coordinateHash(current.longitude(),current.latitude())))
            throw TemporaryCheckinException.conflict("记录坐标已变化，请刷新后重试");
        GeocodeResult result=mapper.readValue(cached.resultJson(),GeocodeResult.class);
        repository.apply(tenant,current,key,result,cached.resolvedAt(),clock.instant());
        AddressRow saved=require(scope,row.id());
        if (!hasText(saved.address())) throw TemporaryCheckinException.conflict("地址未保存，记录可能已变化，请刷新后重试");
        return resolved(saved,cacheHit);
    }

    private AddressRow require(AdminScope scope,UUID id) {
        if (scope==null || scope.username()==null || scope.username().isBlank())
            throw TemporaryCheckinException.adminUnauthorized("请先登录后台");
        AddressRow row=repository.find(tenant,id,scope.city());
        if (row==null) throw TemporaryCheckinException.notFound("拜访记录不存在或不在权限范围内");
        return row;
    }
    private AddressView resolved(AddressRow row,boolean cached) {
        return new AddressView(row.id(),"RESOLVED",row.address(),row.formattedAddress(),
                row.source()==null ? "LEGACY_SNAPSHOT" : row.source(),row.resolvedAt(),"WGS84",
                row.conversionVersion(),null,null,"地址为已保存设备采样点的地图解释，定位质量保持不变",cached);
    }
    private AddressView unavailable(AddressRow row,String status,Instant retryAt,String error) {
        String message=switch(status) {
            case "MISSING_COORDINATES" -> "未保存设备经纬度，无法补解析；不会使用门店地址替代";
            case "PROCESSING" -> "已有地址解析正在进行，请稍后查看结果";
            case "RETRY_LATER" -> "上次解析失败，请在冷却时间后手动重试";
            case "QUOTA_EXCEEDED" -> "今日补解析额度已用完，已有地址仍可查看";
            case "FAILED" -> "本次地址解析未成功，原定位记录已保留，可稍后手动重试";
            default -> "地址尚未解析，可按已保存设备坐标补解析";
        };
        return new AddressView(row.id(),status,null,null,null,null,"WGS84",
                TemporaryCheckinAddressRepository.CONVERSION_VERSION,retryAt,error,message,false);
    }
    private String error(CacheRow cached) {
        if (cached==null || cached.resultJson()==null) return null;
        return mapper.readValue(cached.resultJson(),GeocodeResult.class).errorCode();
    }
    private static boolean hasText(String value) { return value!=null && !value.isBlank(); }
    record AddressView(UUID submissionId,String status,String locationAddress,String formattedAddress,String addressSource,
            Instant addressResolvedAt,String coordinateSystem,String conversionVersion,Instant retryAt,String errorCode,String message,boolean cached) { }
    record BatchAddressView(List<AddressView> items,int total,long resolved) { }
}
