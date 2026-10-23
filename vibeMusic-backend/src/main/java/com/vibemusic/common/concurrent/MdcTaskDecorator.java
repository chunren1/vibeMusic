package com.vibemusic.common.concurrent;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * MDC（traceId）跨线程池透传装饰器。
 *
 * <p>背景：{@code TraceIdFilter} 只在请求线程 {@code MDC.put("traceId", ...)}，
 * 而搜索/预热/取链三条链路都把工作提交到 {@code ThreadPoolConfig} 的线程池，
 * worker 线程没有 MDC → logback 的 {@code [%X{traceId}]} 打出空值，异步日志无法与请求关联。
 *
 * <p>语义：
 * <ol>
 *   <li>提交时对调用线程 MDC 取快照（可能为 null，例如启动预热/单测）；</li>
 *   <li>worker 执行前用快照覆盖 worker 自身 MDC（快照为 null 则 clear，
 *       避免线程池复用把上一个任务的 traceId 带给下一个任务）；</li>
 *   <li>执行结束（含异常）在 finally 中精确恢复到任务开始前的 worker 状态，
 *       而不是一律 clear —— 保证不破坏调用方可能存在的自有上下文。</li>
 * </ol>
 *
 * <p>本类无状态、线程安全，可被多个线程池共享同一实例。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 提交线程的上下文快照：必须在提交线程取，worker 里再取就晚了
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        return () -> {
            // 记录 worker 任务开始前的状态，供 finally 精确恢复（而非无脑 clear）
            Map<String, String> workerContextBefore = MDC.getCopyOfContextMap();
            try {
                if (callerContext != null) {
                    MDC.setContextMap(callerContext);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                if (workerContextBefore != null) {
                    MDC.setContextMap(workerContextBefore);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
