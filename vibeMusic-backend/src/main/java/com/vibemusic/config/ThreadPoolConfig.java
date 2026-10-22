package com.vibemusic.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置：统一管理全局线程池，交由 Spring 托管生命周期，
 * 避免 static ExecutorService 在热重载/测试/重启时泄漏。
 *
 * <p>尺寸预算（生产容器 2 vCPU / 768MB limit，主机 1.6 GiB + swap 已用 527MB）：
 * 池大小按「在途任务数 × 每线程 1MB 栈 + 上游超时预算」推导，不按 CPU 核数推导。
 * 全部数值可由 {@code application.yml} 的 {@code threadpool.*}（或同名环境变量
 * {@code THREADPOOL_*}）覆盖；下面字段的初始值仅是缺配置时的兜底，须与 yml 保持一致。
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "threadpool")
public class ThreadPoolConfig {

    // 搜索线程池：单次搜索 fan-out 5 个音源（网易/QQ/咪咕/酷狗/B站）。
    // 稳态 8 线程 ≈ 1.6 并发搜索请求，max 12 吸收突发，queue 32 兜住分钟级洪峰。
    // 合计 44 个任务 ≈ 44MB 栈（旧值 20/40/100 ≈ 140 个任务，在 768MB 容器里不可接受）。
    private int searchCore = 8;
    private int searchMax = 12;
    private int searchQueue = 32;
    private int searchKeepAlive = 60;

    // 预热线程池：单线程 = 串行限速（@PostConstruct 只提交 1 个任务，14 个关键词串行 + sleep 1.5s）。
    // 不可与搜索池合并：合并会让预热长期占用搜索线程，用户搜索被预热队列拖慢。
    // queue 2 足够（提交方只有启动预热），满了丢最旧，不阻塞启动线程。
    private int warmCore = 1;
    private int warmMax = 1;
    private int warmQueue = 2;
    private int warmKeepAlive = 60;

    // 获取播放链接线程池：单次播放要并行探测 3 个音质档（exhigh/higher/standard），
    // /stream 最坏重试 5 次（StreamController.MAX_ATTEMPTS），故 8 线程 = 2 个并发流 × 3 档 + 2 个重试位。
    // 固定 8（core=max）避免扩容抖动；queue 16 兜一轮重试积压。
    private int getUrlCore = 8;
    private int getUrlMax = 8;
    private int getUrlQueue = 16;
    private int getUrlKeepAlive = 60;

    @Bean(name = "searchExecutor")
    public ThreadPoolTaskExecutor searchExecutor() {
        // 过载快速失败：AbortPolicy 让 submit 同步抛 RejectedExecutionException，
        // 调用方转 BusinessException("稍后重试")，不静默丢弃伪装成"搜不到"。
        RejectedExecutionHandler handler = new ThreadPoolExecutor.AbortPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                log.warn("[SEARCH-POOL] 搜索线程池过载，拒绝任务（队列: {}/{}, 活跃线程: {}/{})",
                        e.getQueue().size(), searchQueue, e.getActiveCount(), e.getMaximumPoolSize());
                super.rejectedExecution(r, e);
            }
        };
        return build("search-worker-", searchCore, searchMax, searchQueue, searchKeepAlive, handler,
                "searchExecutor");
    }

    @Bean(name = "warmExecutor")
    public ThreadPoolTaskExecutor warmExecutor() {
        // 预热是后台非关键任务：队列满时丢最旧的待预热词（DiscardOldest）而不抛异常，
        // 因为预热失败只影响首次命中缓存，绝不能反过来让启动/搜索链路失败。
        RejectedExecutionHandler handler = new ThreadPoolExecutor.DiscardOldestPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                log.warn("[WARM-POOL] 预热任务被丢弃（队列已满），跳过预加载");
                super.rejectedExecution(r, e);
            }
        };
        return build("prewarm-worker-", warmCore, warmMax, warmQueue, warmKeepAlive, handler,
                "warmExecutor");
    }

    @Bean(name = "getUrlExecutor")
    public ThreadPoolTaskExecutor getUrlExecutor() {
        // 过载快速失败：调用方（CompletableFuture）收异常即降级到下一档音质，不静默丢弃伪装成"无链接"。
        RejectedExecutionHandler handler = new ThreadPoolExecutor.AbortPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                log.warn("[GETURL-POOL] 取链线程池过载，拒绝任务（队列: {}/{}, 活跃线程: {}/{})",
                        e.getQueue().size(), getUrlQueue, e.getActiveCount(), e.getMaximumPoolSize());
                super.rejectedExecution(r, e);
            }
        };
        return build("get-url-", getUrlCore, getUrlMax, getUrlQueue, getUrlKeepAlive, handler,
                "getUrlExecutor");
    }

    /**
     * 三个池的连接/线程公共装配，避免重复 setter。拒绝策略由调用方按语义传入：
     * 用户可感知的链路（搜索/取链）一律 AbortPolicy，后台预热一律 DiscardOldestPolicy。
     */
    private ThreadPoolTaskExecutor build(String threadNamePrefix, int core, int max, int queue,
                                         int keepAlive, RejectedExecutionHandler handler, String beanName) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setKeepAliveSeconds(keepAlive);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(handler);
        executor.initialize();
        log.info("[THREAD-POOL] {} 初始化完成: core={}, max={}, queue={}, reject={}",
                beanName, core, max, queue, rejectLabel(handler));
        return executor;
    }

    /** 拒绝策略可读名（匿名子类 getSimpleName() 为空，按父类型判断）。 */
    private static String rejectLabel(RejectedExecutionHandler handler) {
        if (handler instanceof ThreadPoolExecutor.AbortPolicy) {
            return "AbortPolicy";
        }
        if (handler instanceof ThreadPoolExecutor.DiscardOldestPolicy) {
            return "DiscardOldestPolicy";
        }
        return handler.getClass().getName();
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
}
