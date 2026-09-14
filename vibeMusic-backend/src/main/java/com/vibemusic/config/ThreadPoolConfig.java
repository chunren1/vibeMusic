package com.vibemusic.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置：统一管理全局线程池，交由 Spring 托管生命周期，
 * 避免 static ExecutorService 在热重载/测试/重启时泄漏。
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "threadpool")
public class ThreadPoolConfig {

    // 搜索线程池配置
    private int searchCore = 20;
    private int searchMax = 40;
    private int searchQueue = 100;
    private int searchKeepAlive = 60;

    // 预热线程池配置
    private int warmCore = 1;
    private int warmMax = 1;
    private int warmQueue = 20;
    private int warmKeepAlive = 60;

    // 获取播放链接线程池配置
    private int getUrlCore = 3;
    private int getUrlMax = 3;
    private int getUrlQueue = 10;
    private int getUrlKeepAlive = 60;

    // 异步缓存清理线程池配置
    private int asyncCacheCore = 2;
    private int asyncCacheMax = 4;
    private int asyncCacheQueue = 20;
    private int asyncCacheKeepAlive = 30;

    @Bean(name = "searchExecutor")
    public ThreadPoolTaskExecutor searchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(searchCore);
        executor.setMaxPoolSize(searchMax);
        executor.setQueueCapacity(searchQueue);
        executor.setKeepAliveSeconds(searchKeepAlive);
        executor.setThreadNamePrefix("search-worker-");
        executor.setDaemon(true);
        // 过载快速失败：AbortPolicy 让 submit 同步抛 RejectedExecutionException，
        // 调用方转 BusinessException("稍后重试")，不再静默丢弃伪装成"搜不到"。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                log.warn("[SEARCH-POOL] 搜索线程池过载，拒绝任务（队列: {}/{}, 活跃线程: {}/{})",
                        e.getQueue().size(), searchQueue, e.getActiveCount(), e.getMaximumPoolSize());
                super.rejectedExecution(r, e);
            }
        });
        executor.initialize();
        log.info("[THREAD-POOL] searchExecutor 初始化完成: core={}, max={}, queue={}", searchCore, searchMax, searchQueue);
        return executor;
    }

    @Bean(name = "warmExecutor")
    public ThreadPoolTaskExecutor warmExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(warmCore);
        executor.setMaxPoolSize(warmMax);
        executor.setQueueCapacity(warmQueue);
        executor.setKeepAliveSeconds(warmKeepAlive);
        executor.setThreadNamePrefix("prewarm-worker-");
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                log.warn("[WARM-POOL] 预热任务被丢弃（队列已满），跳过预加载");
                super.rejectedExecution(r, e);
            }
        });
        executor.initialize();
        log.info("[THREAD-POOL] warmExecutor 初始化完成: core={}, max={}, queue={}", warmCore, warmMax, warmQueue);
        return executor;
    }

    @Bean(name = "getUrlExecutor")
    public ThreadPoolTaskExecutor getUrlExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(getUrlCore);
        executor.setMaxPoolSize(getUrlMax);
        executor.setQueueCapacity(getUrlQueue);
        executor.setKeepAliveSeconds(getUrlKeepAlive);
        executor.setThreadNamePrefix("get-url-");
        executor.setDaemon(true);
        // 过载快速失败：调用方（CompletableFuture）收异常即降级，不再静默丢弃伪装成"无链接"。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                log.warn("[GETURL-POOL] 取链线程池过载，拒绝任务（队列: {}/{}, 活跃线程: {}/{})",
                        e.getQueue().size(), getUrlQueue, e.getActiveCount(), e.getMaximumPoolSize());
                super.rejectedExecution(r, e);
            }
        });
        executor.initialize();
        log.info("[THREAD-POOL] getUrlExecutor 初始化完成: core={}, max={}, queue={}", getUrlCore, getUrlMax, getUrlQueue);
        return executor;
    }

    @Bean(name = "asyncCacheExecutor")
    public ThreadPoolTaskExecutor asyncCacheExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(asyncCacheCore);
        executor.setMaxPoolSize(asyncCacheMax);
        executor.setQueueCapacity(asyncCacheQueue);
        executor.setKeepAliveSeconds(asyncCacheKeepAlive);
        executor.setThreadNamePrefix("async-cache-");
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        log.info("[THREAD-POOL] asyncCacheExecutor 初始化完成: core={}, max={}, queue={}", asyncCacheCore, asyncCacheMax, asyncCacheQueue);
        return executor;
    }

    // Setters for @ConfigurationProperties
    public void setSearchCore(int searchCore) { this.searchCore = searchCore; }
    public void setSearchMax(int searchMax) { this.searchMax = searchMax; }
    public void setSearchQueue(int searchQueue) { this.searchQueue = searchQueue; }
    public void setSearchKeepAlive(int searchKeepAlive) { this.searchKeepAlive = searchKeepAlive; }
    public void setWarmCore(int warmCore) { this.warmCore = warmCore; }
    public void setWarmMax(int warmMax) { this.warmMax = warmMax; }
    public void setWarmQueue(int warmQueue) { this.warmQueue = warmQueue; }
    public void setWarmKeepAlive(int warmKeepAlive) { this.warmKeepAlive = warmKeepAlive; }
    public void setGetUrlCore(int getUrlCore) { this.getUrlCore = getUrlCore; }
    public void setGetUrlMax(int getUrlMax) { this.getUrlMax = getUrlMax; }
    public void setGetUrlQueue(int getUrlQueue) { this.getUrlQueue = getUrlQueue; }
    public void setGetUrlKeepAlive(int getUrlKeepAlive) { this.getUrlKeepAlive = getUrlKeepAlive; }
    public void setAsyncCacheCore(int asyncCacheCore) { this.asyncCacheCore = asyncCacheCore; }
    public void setAsyncCacheMax(int asyncCacheMax) { this.asyncCacheMax = asyncCacheMax; }
    public void setAsyncCacheQueue(int asyncCacheQueue) { this.asyncCacheQueue = asyncCacheQueue; }
    public void setAsyncCacheKeepAlive(int asyncCacheKeepAlive) { this.asyncCacheKeepAlive = asyncCacheKeepAlive; }
}