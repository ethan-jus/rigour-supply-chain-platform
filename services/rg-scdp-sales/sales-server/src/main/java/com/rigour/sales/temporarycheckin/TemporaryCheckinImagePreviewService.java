package com.rigour.sales.temporarycheckin;

import com.rigour.shared.file.FileStorage;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 经业务鉴权后读取小图：同一原件跨旧新照片编号复用，缺失时受限生成并保留 lease/删除保护。 */
@Component
@ConditionalOnProperty(prefix="rigour.sales.temporary-checkin",name="enabled",havingValue="true")
final class TemporaryCheckinImagePreviewService {
    private final TemporaryCheckinDerivativeRepository repository;
    private final TemporaryCheckinAdminThumbnailer thumbnailer;
    private final FileStorage storage;
    private final Clock clock;
    private final UUID tenant;

    TemporaryCheckinImagePreviewService(TemporaryCheckinDerivativeRepository repository,
            TemporaryCheckinAdminThumbnailer thumbnailer,FileStorage storage,Clock clock,
            TemporaryCheckinProperties properties) {
        this.repository=repository;this.thumbnailer=thumbnailer;this.storage=storage;this.clock=clock;
        this.tenant=properties.requireTenantId();
    }

    byte[] thumbnail(UUID submission,String mediaId,TemporaryCheckinRepository.MediaReference original) {
        byte[] cached=repository.readyImage(tenant,submission,original.objectKey(),original.sha256());
        if(cached!=null) return cached;
        repository.enqueue(tenant,submission,mediaId,"IMAGE",original.objectKey(),original.sha256(),
                original.contentType(),original.originalFilename(),original.sizeBytes(),clock.instant());
        var job=repository.find(tenant,submission,mediaId,original.sha256());
        if(job==null) throw TemporaryCheckinException.conflict("缩略图正在准备，请稍后重试");
        UUID lease=UUID.randomUUID();
        AtomicBoolean claimed=new AtomicBoolean();
        try {
            var thumbnail=thumbnailer.create(new TemporaryCheckinService.AdminMedia(
                    ()->storage.open(tenant.toString(),original.objectKey()),original.sizeBytes(),
                    original.contentType(),original.originalFilename()),()-> {
                if(!repository.claimRequestedImage(tenant,job.id(),lease,clock.instant()))
                    throw TemporaryCheckinException.conflict("缩略图尚未就绪，请稍后重试或查看原图");
                claimed.set(true);
            });
            if(!repository.success(tenant,job.id(),lease,null,thumbnail.bytes(),null,null,clock.instant()))
                throw TemporaryCheckinException.notFound("照片已删除或发生变化");
            return thumbnail.bytes();
        } catch(RuntimeException error) {
            if(claimed.get()) repository.failed(tenant,job.id(),lease,
                    TemporaryCheckinDerivativeWorker.failureCode(error),null,clock.instant());
            cached=repository.readyImage(tenant,submission,original.objectKey(),original.sha256());
            if(cached!=null) return cached;
            throw error;
        }
    }
}
