package com.vibemusic.bootstrap;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 只跑 Flyway 的一次性入口（部署流水线里的「迁移步骤」）。
 *
 * <p>为什么需要它：迁移账号 {@code vibemusic_migrator} 有 DDL 权限。只要它的口令通过
 * {@code SPRING_FLYWAY_PASSWORD} 注入到<b>长期运行</b>的后端容器，应用一旦 RCE，
 * 攻击者读 {@code /proc/self/environ} 就能拿到 DDL 凭证 —— 那时「最小权限」只是纸面上的。
 *
 * <p>拆法：迁移由本入口在独立容器里跑完即退（{@code --rm}），后端容器只保留
 * {@code vibemusic}（DML）并设 {@code SPRING_FLYWAY_ENABLED=false}。
 * 于是「后端被攻破也改不了表结构」才真正成立。
 *
 * <p>刻意<b>不</b>用 {@code @SpringBootApplication}：那会 {@code @ComponentScan} 整个
 * {@code com.vibemusic}，把 Service/Controller/定时任务/连接池全部拉起来，进程不会退出，
 * 还会去连 Redis。这里只 import 两个自动配置（DataSource + Flyway），跑完就结束。
 *
 * <p>启动方式（fat jar 里换主类，不能用 {@code java -jar}）：
 * <pre>
 * java -Dloader.main=com.vibemusic.bootstrap.FlywayOnlyApplication \
 *      -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher
 * </pre>
 *
 * <p>退出码：迁移成功 0；迁移失败抛异常 → 1（部署脚本据此中止，不切流量）。
 */
@Configuration
@Import({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
public class FlywayOnlyApplication {

    public static void main(String[] args) {
        var context = new SpringApplicationBuilder(FlywayOnlyApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run(args);
        // 显式关闭上下文并返回 0：Flyway 在上下文刷新阶段就迁移完了，
        // 这里不 exit 的话，某些非守护线程会让进程挂着不退（部署脚本会一直等）。
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
