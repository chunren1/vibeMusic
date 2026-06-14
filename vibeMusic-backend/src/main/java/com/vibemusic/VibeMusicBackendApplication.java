package com.vibemusic;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.vibemusic.mapper")
@EnableScheduling
public class VibeMusicBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(VibeMusicBackendApplication.class, args);
    }

}
