package com.vibemusic.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 防回归：{@link FlywayOnlyApplication} 必须只加载 DataSource + Flyway。
 *
 * <p>真正的风险不是"跑不起来"，而是有人图省事把它改成 {@code @SpringBootApplication}
 * —— 那样 {@code @ComponentScan} 会把整个应用拉起来：迁移容器不退、还去连 Redis/MinIO，
 * 部署脚本就会一直等一个永远不会结束的进程。本测试用「业务 bean 不存在」把这条锁住。
 */
class FlywayOnlyApplicationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FlywayOnlyApplication.class)
            .withPropertyValues(
                    // 这里只验"加载面"，不验迁移：迁移用真实 MySQL 跑（见 docs §8.4 的验证记录）
                    "spring.flyway.enabled=false",
                    "spring.datasource.url=jdbc:h2:mem:flywayonly;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=");

    @Test
    void 只加载数据源与Flyway_不扫描业务组件() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(DataSource.class);

            // 业务组件一个都不该出现（出现了 = 有人加了组件扫描）
            assertThat(ctx).doesNotHaveBean("cookieCryptoService");
            assertThat(ctx).doesNotHaveBean("playHistoryService");
            // 也不该有 Redis / Web 相关的基础设施
            assertThat(ctx).doesNotHaveBean("redisConnectionFactory");
            assertThat(ctx).doesNotHaveBean("tomcatServletWebServerFactory");

            // 兜底：上下文 bean 数量保持在个位数量级（组件扫描会让它暴涨到数百）
            assertThat(ctx.getBeanDefinitionCount())
                    .as("FlywayOnlyApplication 的 bean 定义数应远小于完整应用")
                    .isLessThan(30);
        });
    }
}
