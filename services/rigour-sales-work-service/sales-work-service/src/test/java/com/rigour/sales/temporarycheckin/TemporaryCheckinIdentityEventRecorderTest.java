package com.rigour.sales.temporarycheckin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rigour.sales.temporarycheckin.TemporaryCheckinIdentityEventRecorder.Fact;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.SalespersonRow;
import com.rigour.sales.temporarycheckin.TemporaryCheckinSalesIdentityService.AuthorizedRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** 用受控阻塞写入验证审计背压和故障不能传回销售请求，避免依赖真实数据库故障复现。 */
class TemporaryCheckinIdentityEventRecorderTest {
    private static final UUID TENANT=UUID.fromString("10000000-0000-0000-0000-000000000041");
    private static final UUID SALES=UUID.fromString("20000000-0000-0000-0000-000000000041");
    private static final Instant VERIFIED=Instant.parse("2026-09-08T03:00:00.123456789Z");

    @Test void blockedWriterHasBoundedQueueAndDoesNotBlockSuccessfulVerification() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var written=new CountDownLatch(2);
        List<Fact> facts=new CopyOnWriteArrayList<>();
        try(var recorder=new TemporaryCheckinIdentityEventRecorder(TENANT,fact->{
            entered.countDown();await(release);facts.add(fact);written.countDown();
        },1)) {
            recorder.record(authorized("PERSONAL_CODE","a".repeat(64)));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(2),()->{
                    recorder.record(authorized("PERSONAL_CODE","a".repeat(64)));
                    recorder.record(authorized("PERSONAL_CODE","a".repeat(64)));
                });
                assertThat(recorder.queuedTasks()).isEqualTo(1);
                assertThat(recorder.droppedEvents()).isEqualTo(1);
            } finally {release.countDown();}
            assertThat(written.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(facts).hasSize(2).allSatisfy(fact->{
                assertThat(fact.tenantId()).isEqualTo(TENANT);
                assertThat(fact.salespersonId()).isEqualTo(SALES);
                assertThat(fact.deviceHash()).isEqualTo("a".repeat(64));
                assertThat(fact.city()).isEqualTo("北京");
                assertThat(fact.occurredAt()).isEqualTo(VERIFIED.truncatedTo(ChronoUnit.MICROS));
            });
            assertThat(facts.get(0).eventId()).isNotEqualTo(facts.get(1).eventId());
        }
    }

    @Test void writerFailureIsCountedAndLaterEventsStillRun() {
        var calls=new AtomicInteger();var saved=new CountDownLatch(1);
        try(var recorder=new TemporaryCheckinIdentityEventRecorder(TENANT,fact->{
            if(calls.incrementAndGet()==1)throw new IllegalStateException("synthetic failure");
            saved.countDown();
        },2)) {
            recorder.record(authorized("PERSONAL_CODE","b".repeat(64)));
            until(()->recorder.droppedEvents()==1);
            recorder.record(authorized("PERSONAL_CODE","b".repeat(64)));
            await(saved);
            assertThat(calls.get()).isEqualTo(2);
            assertThat(recorder.droppedEvents()).isEqualTo(1);
        }
    }

    @Test void closingDoesNotWaitForBlockedDatabaseAndFutureEventsAreRejectedSafely() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var recorder=new TemporaryCheckinIdentityEventRecorder(TENANT,fact->{
            entered.countDown();await(release);
        },1);
        try {
            recorder.record(authorized("PERSONAL_CODE","c".repeat(64)));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            recorder.record(authorized("PERSONAL_CODE","c".repeat(64)));
            assertTimeoutPreemptively(Duration.ofSeconds(2),recorder::close);
            assertThat(recorder.queuedTasks()).isZero();
            recorder.record(authorized("PERSONAL_CODE","c".repeat(64)));
            assertThat(recorder.droppedEvents()).isGreaterThanOrEqualTo(2);
        } finally {release.countDown();recorder.close();}
    }

    @Test void anonymousOrIncompleteRequestsCannotCreateSuccessEvents() {
        var writes=new AtomicInteger();
        try(var recorder=new TemporaryCheckinIdentityEventRecorder(TENANT,fact->writes.incrementAndGet(),1)) {
            recorder.record(null);
            recorder.record(authorized("LEGACY_ANONYMOUS","a".repeat(64)));
            recorder.record(authorized("PERSONAL_CODE",null));
            recorder.record(authorized("PERSONAL_CODE","invalid-cookie-value"));
            var actor=authorized("PERSONAL_CODE","a".repeat(64));
            recorder.record(new AuthorizedRequest(actor.salesperson(),actor.identityMethod(),null,null,actor.deviceTokenHash(),null));
            assertThat(recorder.queuedTasks()).isZero();
        }
        assertThat(writes.get()).isZero();
    }

    private static AuthorizedRequest authorized(String method,String device) {
        var person=mock(SalespersonRow.class);
        when(person.id()).thenReturn(SALES);when(person.name()).thenReturn("合成销售");when(person.city()).thenReturn("北京");
        return new AuthorizedRequest(person,method,VERIFIED,VERIFIED.plusSeconds(3600),device,null);
    }
    private static void await(CountDownLatch latch) {
        try {if(!latch.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic latch timed out");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted synthetic writer");}
    }
    private static void until(BooleanSupplier ready) {
        assertTimeoutPreemptively(Duration.ofSeconds(2),()->{while(!ready.getAsBoolean())Thread.sleep(5);});
    }
}
