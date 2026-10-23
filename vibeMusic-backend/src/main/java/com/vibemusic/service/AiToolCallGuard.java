package com.vibemusic.service;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具调用的预算守卫：把 {@link AiToolService#executeTool} 限制在「本次请求剩余预算」内执行。
 * <p>
 * 为什么需要：AI 链路里工具调用发生在 LLM 两轮之间，一次慢工具（搜索兜底链最长数秒）
 * 就能把整份预算吃光。工具一旦超时，不抛异常打断整条链路，而是回一个错误 JSON
 * 让 LLM 基于已有信息收尾——对前端仍然是一次正常对话。
 * <p>
 * 实现说明：{@code Future.get(timeout)} 的 cancel 只能中断线程，无法中断已发出的阻塞式
 * HTTP 读，所以底层搜索/取链自身的内层超时（3s/5s）仍是最终保险；这里保证的是
 * <b>请求线程不会为了一个工具调用而等超过预算</b>。
 */
@Slf4j
public final class AiToolCallGuard {

    /** 单次工具调用最多占用的时长，防止一次工具调用吃掉整份总预算。 */
    public static final Duration MAX_TOOL_DURATION = Duration.ofSeconds(10);

    /** 工具被预算截断时回传给 LLM 的占位结果（JSON，格式与工具正常结果一致）。 */
    public static final String TIMEOUT_TOOL_RESULT = "{\"error\":\"工具调用超时，请基于已有信息回答\"}";

    /** 工具抛异常时的占位结果。 */
    public static final String FAILED_TOOL_RESULT = "{\"error\":\"工具执行失败，请基于已有信息回答\"}";

    /**
     * 工具执行线程池：daemon + 短存活，工具本身最长受 {@link #MAX_TOOL_DURATION} 截断，
     * 极端并发下退化为调用方线程执行（CallerRunsPolicy），不会无限堆积。
     */
    private static final ExecutorService TOOL_EXECUTOR = new ThreadPoolExecutor(
            4, 32, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable, "ai-tool-budget");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private AiToolCallGuard() {
    }

    /**
     * 在剩余预算内执行一次工具调用。
     *
     * @param toolService 工具服务
     * @param functionName 工具名
     * @param argumentsJson 参数 JSON
     * @param budget 本次请求的预算（已耗尽时直接返回超时占位，不再执行）
     * @return 工具结果 JSON（超时/失败时返回错误 JSON）
     */
    public static String executeWithin(AiToolService toolService, String functionName,
                                       String argumentsJson, AiCallBudget budget) {
        if (!budget.canCall()) {
            log.warn("[AI 预算] 预算耗尽，跳过工具调用: function={}", functionName);
            return TIMEOUT_TOOL_RESULT;
        }
        Duration timeout = AiCallBudget.shorter(MAX_TOOL_DURATION, budget.timeoutForNextCall());
        Future<String> future = TOOL_EXECUTOR.submit(() -> toolService.executeTool(functionName, argumentsJson));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[AI 预算] 工具调用超时已跳过: function={}, timeoutMs={}, remainingMs={}",
                    functionName, timeout.toMillis(), budget.remainingMillis());
            return TIMEOUT_TOOL_RESULT;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("[AI 预算] 工具调用被中断: function={}", functionName);
            return FAILED_TOOL_RESULT;
        } catch (ExecutionException e) {
            log.warn("[AI 预算] 工具调用执行失败: function={}, error={}", functionName, e.getMessage());
            return FAILED_TOOL_RESULT;
        }
    }
}
