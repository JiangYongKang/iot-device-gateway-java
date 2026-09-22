package com.github.highcumontoa.iotdevicegatewayjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 网关基础配置：系统时钟（测试可替换为固定时钟）与容量属性绑定。 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
