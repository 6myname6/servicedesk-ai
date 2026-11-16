package com.servicedesk.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 时间基础配置：统一业务时区，并提供可注入的 {@link Clock}。
 *
 * <p>
 * 属于公共配置层。业务代码取「今天」一律走 {@code LocalDate.now(clock)}，
 * 不直接调用 {@code LocalDate.now()}：一是单测要模拟跨天，二是避免集群内
 * JVM 时区不一致导致同一天出现两种日期段。
 * </p>
 *
 * <p>
 * 时区必须与 {@code application.yml} 的 {@code spring.jackson.time-zone}
 * 以及 JDBC 的 {@code serverTimezone} 保持一致，否则「建单时间」与「工单号日期」
 * 会差一天。
 * </p>
 */
@Configuration
public class TimeConfig {

    /** 业务时区，全项目唯一口径。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 系统时钟，供业务代码注入使用。
     *
     * <p>
     * 测试中可替换为 {@code Clock.fixed(...)} 来模拟跨天、时钟回拨等边界。
     * </p>
     *
     * @return 固定业务时区的系统时钟
     */
    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
