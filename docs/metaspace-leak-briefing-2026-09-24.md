# Metaspace 泄漏分析简报（请外部 AI 协助诊断）

> 目标：定位 Spring Boot 4 应用元空间持续增长（无类加载增长）的根因。
> 本文档自包含，所有事实均来自生产实测与代码核查。

## 1. 运行环境

| 项 | 值 |
|---|---|
| 应用 | vibeMusic 后端（音乐播放/搜索/AI 助手），单体 Spring Boot |
| Spring Boot | **4.0.6**（spring-boot-starter-parent 4.0.6，非常新的主版本） |
| Java | 17（运行镜像 eclipse-temurin:17-jre-alpine，容器内无 JDK 工具） |
| GC | G1 |
| 容器 | Docker，`mem_limit: 768M`，`restart: unless-stopped`，单实例 |
| 宿主机 | 阿里云 ECS 2 vCPU / 1.6G RAM（内存紧张），磁盘 40G |
| 流量 | **极低**：全站每小时几十个请求（image-proxy ~20/h、play ~9/h、lyric ~5/h） |
| 对外特性 | Web MVC + Security + MyBatis-Plus 3.5.9（MySQL）+ Redis（StringRedisTemplate）+ AI 助手 |

### JVM 参数（完整，容器内生效）

```
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/uploads/dumps/
-XX:MaxRAMPercentage=70.0 -XX:MinRAMPercentage=30.0
-XX:MaxDirectMemorySize=64m
-XX:MetaspaceSize=48m -XX:MaxMetaspaceSize=160m
-XX:+UseG1GC
```

（此前 `-XX:MaxRAMPercentage=60.0 -XX:MaxMetaspaceSize=96m` 时发生两次 OOM；96M→160M 后仍在增长）

### 依赖要点（完整 starter 列表）

- spring-boot-starter-webmvc（Spring Boot 4 拆分后的新命名）+ **spring-boot-starter-webflux**（两者并存，webflux 仅为 AI 助手的 WebClient 提供 reactor-netty 1.3.5）
- spring-boot-starter-security / validation / jdbc / data-redis(+commons-pool2) / actuator / cache
- mybatis-plus-spring-boot3-starter 3.5.9（MySQL）
- httpclient5（RestTemplate/RestClient 共享连接池）
- springdoc-openapi-starter-webmvc-ui 2.8.5、minio、caffeine、jjwt 0.12.x
- **spring-boot-devtools 在 pom 中，但已确认未打入部署 jar**（spring-boot-maven-plugin 默认排除，已用 zipfile 核验）
- micrometer-registry-prometheus

## 2. 问题现象

**Metaspace 以 ~1.4 MB/h 单调增长，同时已加载类数量基本持平。**

| 指标 | 数值（UTC 时间） |
|---|---|
| Metaspace used | 新容器启动即 **87 MB**；重启后 ~13 分钟跳至 **95 MB**（+7~8 MB，与首次调用 AI 助手的时间吻合）；随后持续缓慢爬升 95.4 → 96.4 → 97.1（+1.4 MB/h，未见平台期） |
| 已加载类数量 | 启动 17613 → 1 小时后 **19474** → 之后**持平**（+20/小时以内） |
| Compressed Class Space | 12 MB，平稳 |
| CodeHeap 'profiled nmethods' | 16 → 22 MB（JIT 正常，有界） |
| CodeHeap 'non-profiled' / 'non-nmethods' | 6 / 1 MB，平稳 |
| Direct buffers | 4 MB / 64M 上限 |
| 堆 | 新生代+老年代合计 ~71 MB / 538M 上限，几乎无 GC 压力 |
| HikariCP | 0 active / max 5 |
| 请求量 | 数十/小时（可忽略不计的量级） |

### 事故时间线（两次 OOM，跨两个不同构建的 jar 复现）

1. **09-23 17:41 UTC（旧 jar，001ee2ea）**：`OutOfMemoryError: Java heap space`×48（堆当时 ~307M 太小 + 流量突发）→ 扩堆至 538M 后未再发生堆 OOM。
2. **09-23 18:15 UTC 起**：Metaspace 从 89 → 95.4 后**钉死在 96M 上限**（旧配置 MaxMetaspaceSize=96m）→ `OutOfMemoryError: Metaspace`，应用持续 degraded 10 小时（进程存活、healthcheck 失败，restart 策略无效）。
3. **09-24 04:40 UTC（新 jar，e37356c1，上限已调 160M）**：运行 10 小时后再次 `OutOfMemoryError: Metaspace`（爬升至 96M 上限的过程一致：启动 87 → 首次 AI 调用 +7 → 缓慢爬升）。
4. **09-24 06:04 UTC（当前容器）**：上限 160M，当前 97M（61%），仍在缓慢爬升。

**关键矛盾：两个不同的 jar（旧/新，旧包比新包多 195 行死代码）都在运行 ~10 小时后 Metaspace OOM，且类数量在增长期间持平。**

## 3. 已排除项

- ❌ 应用代码显式动态类生成：grep 全源码无 `Proxy.newProxyInstance` / `MethodHandles` / `defineClass` / `ScriptEngine` / `ByteBuddy` / CGLIB 直接使用
- ❌ `ObjectMapper` 重复创建：单例注入（JacksonConfig），AI 服务也是构造注入
- ❌ devtools 进入生产：jar 内已核验不存在
- ❌ 高流量放大：请求量极低（几十/小时），且泄漏速率与流量无强相关（深夜无流量也在涨）
- ❌ DB 池/线程池压力：几乎空闲
- ❌ 直接内存：4M/64M
- ❌ 新旧 jar 差异：跨 jar 复现，且新包代码量更小
- ❌ Compressed Class Space：12M 平稳（增长不在这一池）

## 4. 关键疑点（请重点分析）

1. **类数量持平 + Metaspace 线性增长，最可能的机制是什么？**
   候选：a) 隐藏类/anonymous class（不计入 ClassLoadingMXBean？）——但 JFR profile 模板正在记录 jdk.ClassLoad，待验证；b) Metaspace 碎片化（chunk 回收后 used 不降）；c) 非 class 的元数据增长（如 Method* 结构、注解）——什么会导致已有类的元数据膨胀？
2. **Spring Boot 4.0.6 已知问题？** Boot 4 是全新主版本（webmvc/webflux 拆分 starter），是否有已报告的 Metaspace 增长 issue（含 springdoc 2.8.5、reactor-netty 1.3.5 组合）？
3. **AI 助手路径嫌疑最大**：每次重启后首次调用 AI 助手，Metaspace 即跳升 ~7-8 MB（对 ~19.5k 类的基数是异常大的增量）。该路径使用 WebClient(reactor-netty) 调 DeepSeek API（SSE 流式）+ 手写工具调用循环（Jackson 解析）。是否 reactor-netty/SSE 解码在每次连接建立时生成大量隐藏类/匿名类且不被回收？注意：泄漏在**无 AI 调用的深夜也在继续**（速率略降）。
4. **96M→160M 只是延缓**：若按 1.4 MB/h 线性，~45 小时后再次 OOM。需要根治。

## 5. 可用证据文件

1. **堆转储**（Metaspace OOM 时刻，72MB hprof）：`java_pid1.hprof` —— 注意：类数量在 OOM 时是正常的 ~19.5k，堆内对象分布正常；泄漏在 native 元数据。MAT 分析建议：Class Loader Explorer + Duplicate Classes 是否有价值？
2. **类加载统一日志**（持续累积）：`-Xlog:class+load=info,class+unload=info`，格式样例：
   `[2026-09-24T07:54:20.593+0000][0.043s] java.lang.Object source: shared objects file`
   （load 与 unload 行格式相同、无标记，靠文件分离或时间序列推断；统计"同名类出现多次"可检测 churn）
3. **Prometheus 序列**（15s 采样，可导出任意窗口）：jvm_memory_used_bytes（分池）、jvm_classes_loaded_classes、http_server_requests_seconds_count（按 uri）、hikaricp_*、tomcat_* 等
4. **JFR**：曾以默认模板记录（未含 ClassLoad 事件，价值低）；profile 模板重启后正在录制中（jdk.ClassLoad/Unload 全量）

## 6. 请外部 AI 回答

1. "类数量持平 + Metaspace 线性增长"在 Spring Boot 4 / Java 17 / G1 下，最常见的 3 个根因（按概率排序）？
2. reactor-netty 1.3.5 + WebFlux WebClient 的 SSE 流式调用，是否存在已知的 per-connection 元数据/隐藏类增长问题？如何验证？
3. springdoc-openapi 2.8.5 在 Boot 4 下的已知 Metaspace 问题？
4. 对 72MB 的 Metaspace-OOM hprof，用 MAT 做什么查询最可能定位真凶？（注意：泄漏的是 native 元数据，不在堆内）
5. 我们还应补采什么数据？（当前可自由重启应用、可加任意 JVM 参数、可挂 JDK sidecar 容器共享 PID namespace）

## 7. 联系上下文

- 应用代码可提供任意源码片段（Spring Boot 4 结构：controller/service/config 分层，AI 流式为 SseEmitter + WebClient 手写 SSE 解析）
- 可在容器内执行任意命令（已挂 JDK sidecar 方案可行：`--pid=container:` + 共享 /tmp）
- 泄漏速率可用 Prometheus 任意窗口复核
