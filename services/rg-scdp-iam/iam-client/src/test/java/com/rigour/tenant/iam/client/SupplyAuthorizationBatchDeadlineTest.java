package com.rigour.tenant.iam.client;

import static org.assertj.core.api.Assertions.*;

import com.rigour.shared.context.CallerIdentity;
import com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

class SupplyAuthorizationBatchDeadlineTest {
    private static final String A = "order:read", B = "hr:expense:read";
    private final UUID user = UUID.randomUUID();
    private final CallerIdentity caller =
            new CallerIdentity(
                    "TENANT",
                    user,
                    UUID.randomUUID(),
                    user,
                    null,
                    UUID.randomUUID(),
                    1,
                    2,
                    3,
                    Set.of(),
                    Set.of());

    private SupplyAuthorizationView view(String action) {
        var all = new SupplyAuthorizationView.Limit("ALL", List.of());
        return new SupplyAuthorizationView(
                "ACTIVE",
                caller.tenantId(),
                user,
                "E1",
                1,
                2,
                3,
                4,
                Set.of(A, B),
                action,
                true,
                List.of(),
                all,
                all);
    }

    private class Client implements SupplyAuthorizationClient {
        final AtomicInteger singles = new AtomicInteger(), batches = new AtomicInteger();
        Function<List<String>, List<SupplyAuthorizationView>> response =
                actions ->
                        actions.stream()
                                .map(SupplyAuthorizationBatchDeadlineTest.this::view)
                                .toList();

        public boolean supportsBatchAuthorization() {
            return true;
        }

        public SupplyAuthorizationView authorization(CallerIdentity identity, String action) {
            singles.incrementAndGet();
            return view(action);
        }

        public List<SupplyAuthorizationView> authorizations(
                CallerIdentity identity, List<String> actions) {
            assertThat(SupplyAuthorizationContext.current()).isEmpty();
            assertThat(identity).isEqualTo(caller);
            batches.incrementAndGet();
            return response.apply(actions);
        }
    }

    private ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Test
    void slowBatchCannotHoldTheRequestPastDeadlineOrPublishALateResponse() throws Exception {
        var client = new Client();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var interrupted = new AtomicInteger();
        client.response =
                actions -> {
                    entered.countDown();
                    while (true) {
                        try {
                            release.await();
                            break;
                        } catch (InterruptedException e) {
                            interrupted.incrementAndGet();
                        }
                    }
                    returned.countDown();
                    return actions.stream().map(this::view).toList();
                };
        var executor = pool();
        try (var context =
                SupplyAuthorizationContext.open(
                        client, caller, view("supply:application:access"))) {
            long start = System.nanoTime();
            assertThatThrownBy(
                            () ->
                                    context.prefetchActions(
                                            List.of(A, B), executor, Duration.ofMillis(60)))
                    .hasMessageContaining("授权查询繁忙");
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(client.singles).hasValue(0);
            SupplyAuthorizationContext.requireAction(A);
            assertThat(client.singles).hasValue(1);
            release.countDown();
            assertThat(returned.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.get()).isPositive();
            SupplyAuthorizationContext.requireAction(B);
            assertThat(client.singles).hasValue(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void queuedBatchTimesOutWithoutStartingAndItsCancelledTaskIsPurged() throws Exception {
        var client = new Client();
        var release = new CountDownLatch(1);
        var running = new CountDownLatch(1);
        var executor = pool();
        try (var context =
                SupplyAuthorizationContext.open(
                        client, caller, view("supply:application:access"))) {
            executor.submit(
                    () -> {
                        running.countDown();
                        await(release);
                    });
            assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(
                                () ->
                                        context.prefetchActions(
                                                List.of(A), executor, Duration.ofMillis(60)))
                        .hasMessageContaining("授权查询繁忙");
                assertThat(executor.getQueue()).isEmpty();
                assertThat(client.batches).hasValue(0);
                assertThat(client.singles).hasValue(0);
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void fullBoundedQueueRejectsBatchWithoutFallbackOrDroppingUnrelatedWork() throws Exception {
        var client = new Client();
        var release = new CountDownLatch(1);
        var running = new CountDownLatch(1);
        var executor = pool();
        try (var context =
                SupplyAuthorizationContext.open(
                        client, caller, view("supply:application:access"))) {
            executor.submit(
                    () -> {
                        running.countDown();
                        await(release);
                    });
            assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> {});
            try {
                assertThatThrownBy(
                                () ->
                                        context.prefetchActions(
                                                List.of(A), executor, Duration.ofSeconds(2)))
                        .hasMessageContaining("授权查询繁忙");
                assertThat(executor.getQueue()).hasSize(1);
                assertThat(client.batches).hasValue(0);
                assertThat(client.singles).hasValue(0);
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
