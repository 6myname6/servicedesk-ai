package com.servicedesk.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据源连通性冒烟测试。
 *
 * <p>职责：验证 Spring 上下文能按 {@code application.yml} 的配置真正连上 MySQL，
 * 并确认目标库的字符集与排序规则符合项目约定（utf8mb4 / utf8mb4_0900_ai_ci）。
 * 属于本地环境自检，不覆盖任何业务逻辑。</p>
 */
@SpringBootTest
class DataSourceConnectivityTest {

    /** 用于执行裸 SQL 的模板，由 Spring 按数据源自动装配。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 验证数据库连接可用。
     *
     * <p>执行 {@code SELECT 1}，能拿到结果即说明连接池、账号密码、库名均正确。</p>
     */
    @Test
    @DisplayName("能连上 MySQL 并执行 SELECT 1")
    void shouldConnectToMysql() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);

        assertThat(result).isEqualTo(1);
    }

    /**
     * 验证目标库字符集与排序规则。
     *
     * <p>项目统一要求 utf8mb4 + utf8mb4_0900_ai_ci，字符集不一致会导致中文与 emoji 存取异常。</p>
     */
    @Test
    @DisplayName("servicedesk 库字符集为 utf8mb4 且排序规则为 utf8mb4_0900_ai_ci")
    void shouldUseExpectedCharset() {
        String collation = jdbcTemplate.queryForObject(
                "SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE()",
                String.class);

        assertThat(collation).isEqualTo("utf8mb4_0900_ai_ci");
    }
}
