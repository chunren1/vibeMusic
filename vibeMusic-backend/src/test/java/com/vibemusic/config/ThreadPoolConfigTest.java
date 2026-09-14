package com.vibemusic.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ThreadPoolConfig 线程池配置测试")
class ThreadPoolConfigTest {

    @Test
    @DisplayName("searchExecutor 初始化正确")
    void shouldCreateSearchExecutor() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        ThreadPoolTaskExecutor executor = config.searchExecutor();
        assertNotNull(executor);
        assertEquals(20, executor.getCorePoolSize());
        assertEquals(40, executor.getMaxPoolSize());
        assertEquals(100, executor.getQueueCapacity());
        assertTrue(executor.getThreadNamePrefix().contains("search-worker"));
        executor.shutdown();
    }

    @Test
    @DisplayName("warmExecutor 初始化正确")
    void shouldCreateWarmExecutor() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        ThreadPoolTaskExecutor executor = config.warmExecutor();
        assertNotNull(executor);
        assertEquals(1, executor.getCorePoolSize());
        assertEquals(1, executor.getMaxPoolSize());
        assertEquals(20, executor.getQueueCapacity());
        executor.shutdown();
    }

    @Test
    @DisplayName("getUrlExecutor 初始化正确")
    void shouldCreateGetUrlExecutor() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        ThreadPoolTaskExecutor executor = config.getUrlExecutor();
        assertNotNull(executor);
        assertEquals(3, executor.getCorePoolSize());
        assertEquals(3, executor.getMaxPoolSize());
        executor.shutdown();
    }

    @Test
    @DisplayName("getUrlExecutor 过载时快速失败抛异常，不静默丢弃")
    void shouldRejectFastOnSaturation() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        ThreadPoolTaskExecutor executor = config.getUrlExecutor();
        try {
            assertTrue(executor.getThreadPoolExecutor().getRejectedExecutionHandler()
                    instanceof ThreadPoolExecutor.AbortPolicy, "getUrlExecutor 必须快速失败，禁止静默丢弃");
            CountDownLatch release = new CountDownLatch(1);
            Runnable blocker = () -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            for (int i = 0; i < 13; i++) executor.execute(blocker); // 3 线程 + 10 队列占满
            assertThrows(org.springframework.core.task.TaskRejectedException.class, () -> {
                for (int i = 0; i < 5; i++) executor.execute(() -> {});
            });
            release.countDown();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("searchExecutor 过载时快速失败抛异常，不伪装成搜不到")
    void shouldRejectFastOnSearchSaturation() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        config.setSearchCore(1);
        config.setSearchMax(1);
        config.setSearchQueue(1);
        ThreadPoolTaskExecutor executor = config.searchExecutor();
        CountDownLatch release = new CountDownLatch(1);
        Runnable blocker = () -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            assertTrue(executor.getThreadPoolExecutor().getRejectedExecutionHandler()
                    instanceof ThreadPoolExecutor.AbortPolicy, "searchExecutor 必须快速失败，禁止静默丢弃");
            executor.execute(blocker);
            executor.execute(blocker);
            assertThrows(org.springframework.core.task.TaskRejectedException.class,
                    () -> executor.execute(() -> {}));
            release.countDown();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("asyncCacheExecutor 初始化正确")
    void shouldCreateAsyncCacheExecutor() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        ThreadPoolTaskExecutor executor = config.asyncCacheExecutor();
        assertNotNull(executor);
        assertEquals(2, executor.getCorePoolSize());
        assertEquals(4, executor.getMaxPoolSize());
        executor.shutdown();
    }

    @Test
    @DisplayName("全部 setter 生效：自定义参数透传到各线程池")
    void shouldApplyAllSetters() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        config.setSearchCore(2);
        config.setSearchMax(4);
        config.setSearchQueue(8);
        config.setSearchKeepAlive(30);
        config.setWarmCore(2);
        config.setWarmMax(3);
        config.setWarmQueue(5);
        config.setWarmKeepAlive(31);
        config.setGetUrlCore(1);
        config.setGetUrlMax(2);
        config.setGetUrlQueue(3);
        config.setGetUrlKeepAlive(32);
        config.setAsyncCacheCore(3);
        config.setAsyncCacheMax(5);
        config.setAsyncCacheQueue(6);
        config.setAsyncCacheKeepAlive(33);

        ThreadPoolTaskExecutor search = config.searchExecutor();
        assertEquals(2, search.getCorePoolSize());
        assertEquals(4, search.getMaxPoolSize());
        assertEquals(8, search.getQueueCapacity());
        assertEquals(30, search.getKeepAliveSeconds());
        search.shutdown();

        ThreadPoolTaskExecutor warm = config.warmExecutor();
        assertEquals(2, warm.getCorePoolSize());
        assertEquals(3, warm.getMaxPoolSize());
        assertEquals(5, warm.getQueueCapacity());
        warm.shutdown();

        ThreadPoolTaskExecutor getUrl = config.getUrlExecutor();
        assertEquals(1, getUrl.getCorePoolSize());
        assertEquals(2, getUrl.getMaxPoolSize());
        assertEquals(3, getUrl.getQueueCapacity());
        getUrl.shutdown();

        ThreadPoolTaskExecutor async = config.asyncCacheExecutor();
        assertEquals(3, async.getCorePoolSize());
        assertEquals(5, async.getMaxPoolSize());
        async.shutdown();
    }

    @Test
    @DisplayName("searchExecutor 拒绝处理器直调：记录后快速失败抛异常")
    void shouldFailFastOnSearchRejectDirectly() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        ThreadPoolTaskExecutor executor = config.searchExecutor();
        try {
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            java.util.concurrent.RejectedExecutionHandler handler = pool.getRejectedExecutionHandler();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> handler.rejectedExecution(() -> {}, pool));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("getUrlExecutor 拒绝处理器直调：记录后快速失败抛异常")
    void shouldFailFastOnGetUrlRejectDirectly() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        ThreadPoolTaskExecutor executor = config.getUrlExecutor();
        try {
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            java.util.concurrent.RejectedExecutionHandler handler = pool.getRejectedExecutionHandler();
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> handler.rejectedExecution(() -> {}, pool));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("warmExecutor 拒绝处理器直调：DiscardOldest 静默丢弃最旧任务不抛错")
    void shouldDiscardOldestOnWarmRejectDirectly() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        ThreadPoolTaskExecutor executor = config.warmExecutor();
        try {
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            java.util.concurrent.RejectedExecutionHandler handler = pool.getRejectedExecutionHandler();
            assertDoesNotThrow(() -> handler.rejectedExecution(() -> {}, pool));
        } finally {
            executor.shutdown();
        }
    }
}
