package com.omni.platform.modules.scheduler.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfig {
}
