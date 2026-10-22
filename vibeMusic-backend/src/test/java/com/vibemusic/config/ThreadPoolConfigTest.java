package com.vibemusic.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
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
        assertEquals(8, executor.getCorePoolSize());
        assertEquals(12, executor.getMaxPoolSize());
        assertEquals(32, executor.getQueueCapacity());
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
        assertEquals(2, executor.getQueueCapacity());
        executor.shutdown();
    }

    @Test
    @DisplayName("getUrlExecutor 初始化正确")
    void shouldCreateGetUrlExecutor() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        ThreadPoolTaskExecutor executor = config.getUrlExecutor();
        try {
            assertEquals(8, executor.getCorePoolSize());
            assertEquals(8, executor.getMaxPoolSize());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("getUrlExecutor 过载时快速失败抛异常，不静默丢弃")
    void shouldRejectFastOnSaturation() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        // 显式钉住小尺寸，使饱和测试不依赖生产默认值（默认值变化不应让本测试失去意义）
        config.setGetUrlCore(3);
        config.setGetUrlMax(3);
        config.setGetUrlQueue(10);
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

    // ==================== 资源预算不变量（2026-10-22 线程/连接池重排） ====================

    @Test
    @DisplayName("资源预算：core ≤ max、队列有界、三池总任务上限 ≤ 96（768MB 容器）")
    void shouldKeepPoolsWithinContainerBudget() {
        ThreadPoolConfig config = new ThreadPoolConfig();
        ThreadPoolTaskExecutor search = config.searchExecutor();
        ThreadPoolTaskExecutor warm = config.warmExecutor();
        ThreadPoolTaskExecutor getUrl = config.getUrlExecutor();
        try {
            // core 不得超过 max（ThreadPoolExecutor 会直接抛 IllegalArgumentException，这里提前断言给出可读信息）
            assertTrue(search.getCorePoolSize() <= search.getMaxPoolSize(),
                    "search 池 core 必须 ≤ max");
            assertTrue(getUrl.getCorePoolSize() <= getUrl.getMaxPoolSize(),
                    "getUrl 池 core 必须 ≤ max");
            assertEquals(getUrl.getCorePoolSize(), getUrl.getMaxPoolSize(),
                    "getUrl 池定为固定尺寸，避免扩容抖动");

            // 队列必须有界（无界队列 = 内存泄漏 + 放弃拒绝语义）
            for (ThreadPoolTaskExecutor e : new ThreadPoolTaskExecutor[]{search, warm, getUrl}) {
                assertTrue(e.getQueueCapacity() > 0 && e.getQueueCapacity() <= 64,
                        "队列必须有界且不超过 64: " + e.getThreadNamePrefix() + "=" + e.getQueueCapacity());
            }

            // 容器 limit 768MB；给「线程栈 + 栈上对象」的预算按 96MB 计（每任务 1MB 栈）。
            // 若有人把池改回 200 线程 / 无界队列，这条会先于 OOMKilled 报出来。
            int totalTasks = search.getMaxPoolSize() + search.getQueueCapacity()
                    + warm.getMaxPoolSize() + warm.getQueueCapacity()
                    + getUrl.getMaxPoolSize() + getUrl.getQueueCapacity();
            assertTrue(totalTasks <= 96, "三池总任务上限 " + totalTasks + " 超过 768MB 容器预算（96）");
        } finally {
            search.shutdown();
            warm.shutdown();
            getUrl.shutdown();
        }
    }

    @Test
    @DisplayName("@ConfigurationProperties 绑定生效：threadpool.* 覆盖值能透传到各池")
    void shouldBindThreadpoolOverrides() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("threadpool.search-core", "2");
        env.setProperty("threadpool.search-max", "3");
        env.setProperty("threadpool.search-queue", "4");
        env.setProperty("threadpool.warm-core", "1");
        env.setProperty("threadpool.warm-max", "1");
        env.setProperty("threadpool.warm-queue", "1");
        env.setProperty("threadpool.get-url-core", "5");
        env.setProperty("threadpool.get-url-max", "6");
        env.setProperty("threadpool.get-url-queue", "7");

        ThreadPoolConfig config = Binder.get(env).bindOrCreate("threadpool", ThreadPoolConfig.class);
        ThreadPoolTaskExecutor search = config.searchExecutor();
        ThreadPoolTaskExecutor warm = config.warmExecutor();
        ThreadPoolTaskExecutor getUrl = config.getUrlExecutor();
        try {
            assertEquals(2, search.getCorePoolSize());
            assertEquals(3, search.getMaxPoolSize());
            assertEquals(4, search.getQueueCapacity());
            assertEquals(1, warm.getQueueCapacity());
            assertEquals(5, getUrl.getCorePoolSize(), "kebab-case get-url-core 必须绑定到 getUrlCore");
            assertEquals(6, getUrl.getMaxPoolSize());
            assertEquals(7, getUrl.getQueueCapacity());
        } finally {
            search.shutdown();
            warm.shutdown();
            getUrl.shutdown();
        }
    }

    @Test
    @DisplayName("application.yml 与 Java 兜底一致：threadpool / tomcat / hikari 取值不漂移")
    void shouldKeepYamlInSyncWithJavaDefaults() throws Exception {
        PropertySource<?> yml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml")).get(0);

        ThreadPoolConfig config = new ThreadPoolConfig();
        ThreadPoolTaskExecutor search = config.searchExecutor();
        ThreadPoolTaskExecutor warm = config.warmExecutor();
        ThreadPoolTaskExecutor getUrl = config.getUrlExecutor();
        try {
            // yml 是可覆盖的单一事实源，Java 只是兜底；两者不一致说明有人只改了一边。
            assertEquals(search.getCorePoolSize(), intFromYaml(yml, "threadpool.search-core"));
            assertEquals(search.getMaxPoolSize(), intFromYaml(yml, "threadpool.search-max"));
            assertEquals(search.getQueueCapacity(), intFromYaml(yml, "threadpool.search-queue"));
            assertEquals(warm.getCorePoolSize(), intFromYaml(yml, "threadpool.warm-core"));
            assertEquals(warm.getMaxPoolSize(), intFromYaml(yml, "threadpool.warm-max"));
            assertEquals(warm.getQueueCapacity(), intFromYaml(yml, "threadpool.warm-queue"));
            assertEquals(getUrl.getCorePoolSize(), intFromYaml(yml, "threadpool.get-url-core"));
            assertEquals(getUrl.getMaxPoolSize(), intFromYaml(yml, "threadpool.get-url-max"));
            assertEquals(getUrl.getQueueCapacity(), intFromYaml(yml, "threadpool.get-url-queue"));
        } finally {
            search.shutdown();
            warm.shutdown();
            getUrl.shutdown();
        }

        // Tomcat 收敛与 Hikari 收敛：防止有人回退成 Spring Boot 默认 200 线程 / 20 连接
        assertEquals(40, intFromYaml(yml, "server.tomcat.threads.max"));
        assertEquals(50, intFromYaml(yml, "server.tomcat.accept-count"));
        assertEquals(200, intFromYaml(yml, "server.tomcat.max-connections"));
        assertEquals(8, intFromYaml(yml, "spring.datasource.hikari.maximum-pool-size"));
    }

    /** 取 yml 标量；支持带默认值的占位符 ${ENV:40}。 */
    private static int intFromYaml(PropertySource<?> source, String key) {
        Object raw = source.getProperty(key);
        assertNotNull(raw, "application.yml 缺少配置项: " + key);
        String value = String.valueOf(raw).trim();
        if (value.startsWith("${") && value.endsWith("}")) {
            value = value.substring(2, value.length() - 1);
            int sep = value.lastIndexOf(':');
            if (sep >= 0) {
                value = value.substring(sep + 1);
            }
        }
        return Integer.parseInt(value.trim());
    }
}
