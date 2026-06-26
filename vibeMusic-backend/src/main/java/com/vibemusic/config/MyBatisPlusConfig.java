package com.vibemusic.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.Resource;

import javax.sql.DataSource;
import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置: 显式创建 SqlSessionFactory（兼容 Spring Boot 4.x）
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 自动填充处理器（替代 JPA @PrePersist）
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "playedAt", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
                this.strictUpdateFill(metaObject, "playedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }

    /**
     * 显式创建 SqlSessionFactory（Spring Boot 4.x 自动配置兼容性问题）
     * 注意：必须显式设置 mapperLocations，否则自定义 XML mapper 无法绑定
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource, MetaObjectHandler metaObjectHandler) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);

        // 必须显式设置 mapper XML 路径，否则报 BindingException
        Resource[] mapperLocations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/**/*.xml");
        factoryBean.setMapperLocations(mapperLocations);

        // 使用 MyBatis-Plus 的 Configuration
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        // 注入 MetaObjectHandler 到 GlobalConfig（否则自动填充不生效）
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setMetaObjectHandler(metaObjectHandler);
        factoryBean.setGlobalConfig(globalConfig);

        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
