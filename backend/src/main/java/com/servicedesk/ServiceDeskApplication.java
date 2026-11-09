package com.servicedesk;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智维工单 ServiceDesk AI 启动类。
 *
 * <p>
 * 包名：com.servicedesk；API 基址：/api；接口契约见 docs/接口文档.md。
 *
 * <p>
 * 注解说明：
 * <ul>
 * <li>{@code @SpringBootApplication}：组件扫描 + 自动配置，扫描范围为本包及子包</li>
 * <li>{@code @MapperScan}：扫描 Mapper 接口，生成 MyBatis 代理 Bean</li>
 * <li>{@code @ConfigurationPropertiesScan}：注册 JwtProperties / StorageProperties
 * / AiProperties</li>
 * <li>{@code @EnableScheduling}：启用定时任务（SLA 超时扫描、暂停自动恢复、令牌配额）</li>
 * <li>{@code @EnableAsync}：启用异步任务（知识库解析与索引）</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan("com.servicedesk.mapper")
public class ServiceDeskApplication {

    /**
     * 应用启动入口。
     *
     * @param args 命令行启动参数（Spring Boot 标准入口参数）
     */
    public static void main(String[] args) {
        SpringApplication.run(ServiceDeskApplication.class, args);
    }
}
