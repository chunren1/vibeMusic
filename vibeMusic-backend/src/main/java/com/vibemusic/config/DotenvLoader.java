package com.vibemusic.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 自动加载项目根目录的 .env 文件到 Spring Environment
 * <p>
 * 查找顺序：项目根目录 → 当前目录
 * 这样本地开发时 IntelliJ 无需 EnvFile 插件也能从 .env 读取密钥
 */
public class DotenvLoader implements EnvironmentPostProcessor {

    private static final String[] SEARCH_PATHS = { "../.env", ".env", "../../.env" };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Resource resource = null;
        for (String path : SEARCH_PATHS) {
            Resource candidate = new FileSystemResource(path);
            if (candidate.exists() && candidate.isReadable()) {
                resource = candidate;
                break;
            }
        }
        if (resource == null) return;

        Map<String, Object> props = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int idx = line.indexOf('=');
                if (idx <= 0) continue;
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                if (!value.isEmpty()) {
                    props.put(key, value);
                }
            }
        } catch (Exception ignored) {
            return;
        }

        if (!props.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("dotenv", props));
        }
    }
}
