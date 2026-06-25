package com.vibemusic.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                this.strictInsertFill(metaObject, "addedAt", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
                this.strictUpdateFill(metaObject, "playedAt", LocalDateTime.class, LocalDateTime.now());
                this.strictUpdateFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                this.strictUpdateFill(metaObject, "addedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }

    /**
     * 显式创建 SqlSessionFactory（Spring Boot 4.x 自动配置兼容性问题）
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);

        // 使用 MyBatis-Plus 的 Configuration
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);

        return factoryBean.getObject();
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
