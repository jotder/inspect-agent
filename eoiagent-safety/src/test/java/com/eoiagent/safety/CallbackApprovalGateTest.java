package com.eoiagent.safety;

import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;
import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** CallbackApprovalGate: blocking, timeout disposition, idempotency, dry-run, and fail-closed (T-202). */
class CallbackApprovalGateTest {

    private static ApprovalRequest req(String tool) {
        ToolCall call = new ToolCall(tool, Map.of(), new RunId("run-1"));
        return new ApprovalRequest(new RunId("run-1"), call, "please approve " + tool,
                new DryRunResult(false, "n/a", Map.of()));
    }

    @Test
    void returnsHandlerDecisionUnchanged() { // AC3
        CallbackApprovalGate approve = CallbackApprovalGate.builder()
                .handler(r -> ApprovalDecision.APPROVED).timeout(Duration.ofSeconds(5)).build();
        CallbackApprovalGate deny = CallbackApprovalGate.builder()
                .handler(r -> ApprovalDecision.DENIED).timeout(Duration.ofSeconds(5)).build();

        assertThat(approve.request(req("run_pipeline"))).isEqualTo(ApprovalDecision.APPROVED);
        assertThat(deny.request(req("run_pipeline"))).isEqualTo(ApprovalDecision.DENIED);
    }

    @Test
    void blocksUntilHandlerReturns() throws Exception { // AC3 (blocking)
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CallbackApprovalGate gate = CallbackApprovalGate.builder()
                .timeout(Duration.ofSeconds(5))
                .handler(r -> {
                    handlerEntered.countDown();
                    await(release);
                    return ApprovalDecision.APPROVED;
                }).build();

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ApprovalDecision> pending = caller.submit(() -> gate.request(req("run_pipeline")));
            assertThat(handlerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(pending.isDone()).isFalse(); // still blocked on the human

            release.countDown();
            assertThat(pending.get(2, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.APPROVED);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void timeoutYieldsConfiguredDisposition() { // AC5
        CallbackApprovalGate timedOut = CallbackApprovalGate.builder()
                .timeout(Duration.ofMillis(100)).onTimeout(ApprovalDecision.TIMED_OUT)
                .handler(r -> { await(new CountDownLatch(1)); return ApprovalDecision.APPROVED; }) // never returns
                .build();
        CallbackApprovalGate deniedOnTimeout = CallbackApprovalGate.builder()
                .timeout(Duration.ofMillis(100)).onTimeout(ApprovalDecision.DENIED)
                .handler(r -> { await(new CountDownLatch(1)); return ApprovalDecision.APPROVED; })
                .build();

        assertThat(timedOut.request(req("run_pipeline"))).isEqualTo(ApprovalDecision.TIMED_OUT);
        assertThat(deniedOnTimeout.request(req("run_pipeline"))).isEqualTo(ApprovalDecision.DENIED);
    }

    @Test
    void cachesDecisionPerRunAndCall() { // AC6
        AtomicInteger handlerCalls = new AtomicInteger();
        CallbackApprovalGate gate = CallbackApprovalGate.builder()
                .timeout(Duration.ofSeconds(5))
                .handler(r -> { handlerCalls.incrementAndGet(); return ApprovalDecision.APPROVED; })
                .build();
        ApprovalRequest request = req("run_pipeline");

        ApprovalDecision first = gate.request(request);
        ApprovalDecision second = gate.request(request);

        assertThat(first).isEqualTo(ApprovalDecision.APPROVED);
        assertThat(second).isEqualTo(ApprovalDecision.APPROVED);
        assertThat(handlerCalls.get()).isEqualTo(1); // handler invoked once, not re-prompted
    }

    @Test
    void decisionStoreHitBypassesHandlerAndIsCached() { // AC9
        AtomicInteger handlerCalls = new AtomicInteger();
        AtomicInteger finds = new AtomicInteger();
        CallbackApprovalGate gate = CallbackApprovalGate.builder()
                .timeout(Duration.ofSeconds(5))
                .handler(r -> { handlerCalls.incrementAndGet(); return ApprovalDecision.DENIED; })
                .decisionStore(new DecisionStore() {
                    @Override
                    public java.util.Optional<ApprovalDecision> find(ApprovalRequest req) {
                        finds.incrementAndGet();
                        return java.util.Optional.of(ApprovalDecision.APPROVED);
                    }

                    @Override
                    public void record(ApprovalRequest req, ApprovalDecision decision) {
                    }
                })
                .build();
        ApprovalRequest request = req("run_pipeline");

        assertThat(gate.request(request)).isEqualTo(ApprovalDecision.APPROVED);
        assertThat(gate.request(request)).isEqualTo(ApprovalDecision.APPROVED); // local cache
        assertThat(handlerCalls.get()).isZero();  // never prompted
        assertThat(finds.get()).isEqualTo(1);     // one-shot friendly: consulted once per request
    }

    @Test
    void decisionStoreMissOrFailureFallsThroughAndRecordsDecision() { // AC9 (fail toward the human)
        java.util.List<ApprovalDecision> recorded = new java.util.concurrent.CopyOnWriteArrayList<>();
        CallbackApprovalGate miss = CallbackApprovalGate.builder()
                .timeout(Duration.ofSeconds(5))
                .handler(r -> ApprovalDecision.APPROVED)
                .decisionStore(new DecisionStore() {
                    @Override
                    public java.util.Optional<ApprovalDecision> find(ApprovalRequest req) {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public void record(ApprovalRequest req, ApprovalDecision decision) {
                        recorded.add(decision);
                    }
                })
                .build();
        CallbackApprovalGate throwing = CallbackApprovalGate.builder()
                .timeout(Duration.ofSeconds(5))
                .handler(r -> ApprovalDecision.DENIED)
                .decisionStore(new DecisionStore() {
                    @Override
                    public java.util.Optional<ApprovalDecision> find(ApprovalRequest req) {
                        throw new IllegalStateException("store down");
                    }

                    @Override
                    public void record(ApprovalRequest req, ApprovalDecision decision) {
                        throw new IllegalStateException("store down");
                    }
                })
                .build();

        assertThat(miss.request(req("run_pipeline"))).isEqualTo(ApprovalDecision.APPROVED);
        assertThat(recorded).containsExactly(ApprovalDecision.APPROVED);
        assertThat(throwing.request(req("run_pipeline"))).isEqualTo(ApprovalDecision.DENIED);
    }

    @Test
    void dryRunResolvesProviderOrReportsUnsupported() { // AC7
        CallbackApprovalGate gate = CallbackApprovalGate.builder()
                .handler(r -> ApprovalDecision.DENIED)
                .dryRunProvider("run_pipeline",
                        call -> new DryRunResult(true, "would run pipeline " + call.toolName(),
                                Map.of("rows", 42)))
                .build();

        DryRunResult supported = gate.dryRun(new ToolCall("run_pipeline", Map.of(), new RunId("r")));
        DryRunResult missing = gate.dryRun(new ToolCall("edit_config", Map.of(), new RunId("r")));

        assertThat(supported.supported()).isTrue();
        assertThat(supported.predictedEffects()).containsEntry("rows", 42);
        assertThat(missing.supported()).isFalse();
        assertThat(missing.preview()).contains("no dry-run available for edit_config");
    }

    @Test
    void dryRunProviderFailureReportsUnsupportedNotThrow() { // AC7 (failure mode)
        CallbackApprovalGate gate = CallbackApprovalGate.builder()
                .handler(r -> ApprovalDecision.DENIED)
                .dryRunProvider("run_pipeline", call -> { throw new IllegalStateException("boom"); })
                .build();

        DryRunResult result = gate.dryRun(new ToolCall("run_pipeline", Map.of(), new RunId("r")));

        assertThat(result.supported()).isFalse();
        assertThat(result.preview()).contains("dry-run failed for run_pipeline");
    }

    @Test
    void headlessWithNoHandlerDeniesFailClosed() { // AC8
        CallbackApprovalGate gate = CallbackApprovalGate.builder()
                .timeout(Duration.ZERO).onTimeout(ApprovalDecision.DENIED).build(); // no handler

        assertThat(gate.request(req("run_pipeline"))).isEqualTo(ApprovalDecision.DENIED);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
