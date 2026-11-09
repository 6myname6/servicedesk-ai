package com.servicedesk.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

@Configuration
public class MybatisPlusConfig {
	// 单页最大条数，防止前端传过大数字拖垮数据库
	private static final long MAX_LIMIT = 100L;

	@Bean
	public MybatisPlusInterceptor mybatisPlusInterceptor() {
		MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
		// 分页插件：多个内部拦截器时，分页放在最后
		interceptor.addInnerInterceptor(paginationInnerInterceptor());
		return interceptor;
	}

	private InnerInterceptor paginationInnerInterceptor() {
		PaginationInnerInterceptor p = new PaginationInnerInterceptor(DbType.MYSQL);
		// 设置单页上限，页码越界行为
		p.setMaxLimit(MAX_LIMIT);
		p.setOverflow(false);
		return p;
	}
}
