package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.*;
import com.rigour.merchant.application.port.out.CustomerSyncJobStore;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;

/** CRM 自己执行客户同步；Integration 只使用短请求提交和查询，避免内部调用再次超时。 */
@Service
public class CustomerSyncJobService {
    private static final Logger log=LoggerFactory.getLogger(CustomerSyncJobService.class);
    private final CrmMasterDataSyncService delegate;
    private final CustomerSyncJobStore store;
    private final Clock clock;
    private final ExecutorService worker=new ThreadPoolExecutor(2,2,0L,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),r->new Thread(r,"crm-customer-sync"),new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(
            r->{var t=new Thread(r,"crm-sync-heartbeat");t.setDaemon(true);return t;});
    private final Set<Key> owned=ConcurrentHashMap.newKeySet();
    public CustomerSyncJobService(CrmMasterDataSyncService delegate,CustomerSyncJobStore store,Clock clock) {
        this.delegate=delegate;this.store=store;this.clock=clock;
        heartbeat.scheduleWithFixedDelay(this::beat,15,15,TimeUnit.SECONDS);
    }
    public CustomerSyncJob start(CallerIdentity caller,UUID requestId,UUID connector,UUID sourceTask,
            int maxPages,UUID initiatedBy) {
        CrmMasterDataSyncService.requireScheduledCaller(caller);
        if(requestId==null||connector==null||sourceTask==null||maxPages<1||maxPages>500)
            throw new IllegalArgumentException("客户后台同步参数不完整");
        var job=store.reserve(caller.tenantId(),requestId,connector,clock.instant());
        if(!job.jobId().equals(requestId)||!store.claim(caller.tenantId(),requestId,clock.instant()))
            return get(caller,job.jobId());
        var key=new Key(caller.tenantId(),requestId);owned.add(key);
        try {
            worker.execute(()->{
                try {
                    var result=delegate.runSelected(caller,connector,sourceTask,maxPages,null,null,
                            "CUSTOMER",true,null,initiatedBy,
                            stage->store.update(key.tenant(),key.id(),"RUNNING",stage,null,clock.instant()));
                    store.update(key.tenant(),key.id(),"SUCCEEDED","客户同步结束",result,clock.instant());
                } catch(RuntimeException error) {
                    log.error("客户后台同步失败 jobId={} type={}",key.id(),error.getClass().getSimpleName(),error);
                    store.update(key.tenant(),key.id(),"FAILED","客户同步失败，请查看 CRM 同步批次记录",null,clock.instant());
                } finally {owned.remove(key);}
            });
        } catch(RejectedExecutionException full) {
            owned.remove(key);
            store.update(key.tenant(),key.id(),"FAILED","后台任务队列已满，本次未执行",null,clock.instant());
        }
        return get(caller,requestId);
    }
    public CustomerSyncJob get(CallerIdentity caller,UUID jobId) {
        CrmMasterDataSyncService.requireScheduledCaller(caller);
        var job=store.find(caller.tenantId(),jobId)
                .orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND,"客户同步任务不存在",List.of()));
        if(Set.of("QUEUED","RUNNING").contains(job.status())&&job.heartbeatAt().isBefore(clock.instant().minusSeconds(90)))
            return new CustomerSyncJob(job.jobId(),job.connectorId(),"UNKNOWN",
                    "CRM 心跳中断，原任务结果待核实；请勿重复提交",job.startedAt(),job.heartbeatAt(),job.result());
        return job;
    }
    private void beat() {
        for(var key:owned)try{store.heartbeat(key.tenant(),key.id(),clock.instant());}
        catch(RuntimeException e){log.warn("客户任务心跳更新失败 jobId={}",key.id());}
    }
    @PreDestroy public void close(){heartbeat.shutdown();worker.shutdown();}
    private record Key(UUID tenant,UUID id){}
}
