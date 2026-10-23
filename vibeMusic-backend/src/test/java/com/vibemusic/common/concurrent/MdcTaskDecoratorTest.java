package com.vibemusic.common.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MdcTaskDecorator 纯单元测试：提交线程 → worker 透传、任务后清理、无上下文安全、恢复语义。
 *
 * <p>用单线程池保证两个任务落在同一个 worker 线程上，才能证明"残留/清理"这件事。
 */
@DisplayName("MdcTaskDecorator MDC 跨线程透传")
class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();
    private final ExecutorService singleThread = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() throws Exception {
        MDC.clear();
        singleThread.shutdownNow();
        singleThread.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("调用线程有 MDC → 线程池任务内可见同一 traceId")
    void shouldPropagateTraceIdIntoWorkerThread() throws Exception {
        MDC.put("traceId", "req-abc-001");

        AtomicReference<String> inWorker = new AtomicReference<>();
        singleThread.submit(decorator.decorate(() -> inWorker.set(MDC.get("traceId"))))
                .get(2, TimeUnit.SECONDS);

        assertEquals("req-abc-001", inWorker.get(), "worker 线程必须拿到提交线程的 traceId");
    }

    @Test
    @DisplayName("任务结束后 worker MDC 被清理 → 后续任务看不到上一个任务的 traceId")
    void shouldCleanWorkerMdcAfterTask() throws Exception {
        MDC.put("traceId", "first-task");
        singleThread.submit(decorator.decorate(() -> { })).get(2, TimeUnit.SECONDS);

        // 提交方已无上下文；第二个任务故意不装饰，直接观察 worker 线程的真实残留状态
        MDC.clear();
        AtomicReference<String> seenByNextTask = new AtomicReference<>("sentinel");
        singleThread.submit(() -> seenByNextTask.set(MDC.get("traceId"))).get(2, TimeUnit.SECONDS);

        assertNull(seenByNextTask.get(), "worker 线程不能残留上一个任务的 traceId（否则日志串号）");
    }

    @Test
    @DisplayName("调用线程无 MDC → 任务内为 null 且不抛异常")
    void shouldRunWithoutMdcWhenCallerHasNone() throws Exception {
        MDC.clear();

        AtomicReference<String> inWorker = new AtomicReference<>("sentinel");
        assertDoesNotThrow(() -> singleThread
                .submit(decorator.decorate(() -> inWorker.set(MDC.get("traceId"))))
                .get(2, TimeUnit.SECONDS));

        assertNull(inWorker.get(), "调用线程无上下文时任务内 MDC 应为 null");
    }

    @Test
    @DisplayName("任务结束后恢复 worker 原有上下文（快照恢复，不是无条件 clear）")
    void shouldRestoreWorkerOwnContextAfterTask() throws Exception {
        // 先在 worker 线程放入"自有上下文"，模拟池化线程已有状态
        singleThread.submit(() -> MDC.put("traceId", "worker-own")).get(2, TimeUnit.SECONDS);

        MDC.put("traceId", "caller-task");
        AtomicReference<String> duringTask = new AtomicReference<>();
        singleThread.submit(decorator.decorate(() -> duringTask.set(MDC.get("traceId"))))
                .get(2, TimeUnit.SECONDS);

        MDC.clear();
        AtomicReference<String> afterTask = new AtomicReference<>();
        singleThread.submit(() -> afterTask.set(MDC.get("traceId"))).get(2, TimeUnit.SECONDS);

        assertEquals("caller-task", duringTask.get(), "任务内应以调用线程快照为准");
        assertEquals("worker-own", afterTask.get(), "任务结束后必须恢复 worker 原有上下文");
    }

    @Test
    @DisplayName("任务抛异常时 finally 仍清理 MDC")
    void shouldCleanMdcEvenWhenTaskThrows() throws Exception {
        MDC.put("traceId", "boom-task");

        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> singleThread.submit(decorator.decorate(() -> {
                    throw new IllegalStateException("boom");
                })).get(2, TimeUnit.SECONDS));

        MDC.clear();
        AtomicReference<String> seenByNextTask = new AtomicReference<>("sentinel");
        singleThread.submit(() -> seenByNextTask.set(MDC.get("traceId"))).get(2, TimeUnit.SECONDS);
        assertNull(seenByNextTask.get(), "异常路径也必须清理 worker MDC");
    }
}
