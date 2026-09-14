package com.rigour.sales.temporarycheckin;

import com.rigour.sales.infrastructure.persistence.SalesUuidCodec;
import com.rigour.sales.temporarycheckin.TemporaryCheckinSalesIdentityService.AuthorizedRequest;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 成功验证后的有界异步事实记录；只采集最少授权字段，失败可丢失且明确不代表完整登录轨迹。 */
@Component
@ConditionalOnProperty(prefix="rigour.sales.temporary-checkin",name="enabled",havingValue="true")
class TemporaryCheckinIdentityEventRecorder implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(TemporaryCheckinIdentityEventRecorder.class);
    private final UUID tenant;
    private final Consumer<Fact> writer;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped=new AtomicLong();
    private final AtomicLong lastWarning=new AtomicLong();

    @Autowired
    TemporaryCheckinIdentityEventRecorder(JdbcTemplate jdbc,TemporaryCheckinProperties properties) {
        this(properties.requireTenantId(),databaseWriter(jdbc),64);
    }
    /** 允许用受控写入器验证阻塞、队列饱和和关闭；生产仍只使用一个最多64项排队的执行器。 */
    TemporaryCheckinIdentityEventRecorder(UUID tenant,Consumer<Fact> writer,int queueCapacity) {
        this.tenant=tenant;this.writer=writer;
        executor=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(queueCapacity),task->{
            Thread thread=new Thread(task,"temporary-checkin-identity-audit");thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
    }
    void record(AuthorizedRequest authorized) {
        if(authorized==null||!"PERSONAL_CODE".equals(authorized.identityMethod())||authorized.salesperson()==null
                ||authorized.verifiedAt()==null||authorized.deviceTokenHash()==null
                ||!authorized.deviceTokenHash().matches("[a-f0-9]{64}"))return;
        // 不把 AuthorizedRequest 放进队列；它可能间接持有销售凭据摘要及其他与本审计无关的请求事实。
        var person=authorized.salesperson();
        Fact fact=new Fact(UUID.randomUUID(),tenant,authorized.deviceTokenHash(),person.id(),person.name(),person.city(),
                authorized.verifiedAt().truncatedTo(ChronoUnit.MICROS));
        try {
            executor.execute(()->{
                try {writer.accept(fact);}
                catch(RuntimeException unavailable) {failure("DATABASE_UNAVAILABLE");}
            });
        } catch(RejectedExecutionException busy) {failure("QUEUE_UNAVAILABLE");}
    }
    private void failure(String reason) {
        long count=dropped.incrementAndGet(),now=System.nanoTime(),before=lastWarning.get();
        if((before==0||now-before>=TimeUnit.SECONDS.toNanos(30))&&lastWarning.compareAndSet(before,now))
            log.warn("临时打卡身份事件未保存 reason={} dropped={}；正常验证不受影响，审计轨迹可能不完整",reason,count);
    }
    int queuedTasks() {return executor.getQueue().size();}
    long droppedEvents() {return dropped.get();}
    @Override @PreDestroy public void close() {
        int discarded=executor.shutdownNow().size();
        if(discarded>0) {dropped.addAndGet(discarded);log.warn("临时打卡身份审计关闭时放弃{}项待写事件，历史不可视为完整",discarded);}
    }
    private static Consumer<Fact> databaseWriter(JdbcTemplate source) {
        JdbcTemplate writes=new JdbcTemplate(java.util.Objects.requireNonNull(source.getDataSource()));
        writes.setQueryTimeout(2);
        return fact->writes.update("""
                INSERT INTO temp_sales_checkin_identity_event(event_id,tenant_id,device_token_hash,salesperson_id,
                    salesperson_name_snapshot,identity_city,event_type,occurred_at)
                VALUES(?,?,?,?,?,?,'PERSONAL_CODE_VERIFIED',?)
                """,SalesUuidCodec.encode(fact.eventId()),SalesUuidCodec.encode(fact.tenantId()),fact.deviceHash(),
                SalesUuidCodec.encode(fact.salespersonId()),fact.salespersonName(),fact.city(),LocalDateTime.ofInstant(fact.occurredAt(),ZoneOffset.UTC));
    }
    record Fact(UUID eventId,UUID tenantId,String deviceHash,UUID salespersonId,String salespersonName,String city,Instant occurredAt) { }
}
