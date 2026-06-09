package com.vibemusic.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 头像/背景图上传目录映射，加上浏览器缓存（7天）
        String uploadPath = new File("uploads/avatars").getAbsolutePath();
        registry.addResourceHandler("/uploads/avatars/**")
                .addResourceLocations("file:" + uploadPath + File.separator)
                .setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
    }
}
