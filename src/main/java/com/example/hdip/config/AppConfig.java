package com.example.hdip.config;

import com.example.hdip.observability.PipelineMetrics;
import com.example.hdip.ratelimit.IngestGuardFilter;
import com.example.hdip.ratelimit.IngestProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(IngestProperties.class)
public class AppConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Registered at lowest precedence so it runs after the Spring Security filter chain. */
    @Bean
    FilterRegistrationBean<IngestGuardFilter> ingestGuardFilter(IngestProperties props, Clock clock, PipelineMetrics metrics) {
        FilterRegistrationBean<IngestGuardFilter> reg = new FilterRegistrationBean<>(new IngestGuardFilter(props, clock, metrics));
        reg.addUrlPatterns("/api/v1/*");
        reg.setOrder(Integer.MAX_VALUE - 10);
        return reg;
    }
}
