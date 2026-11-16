package com.servicedesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.servicedesk.entity.SlaPolicy;

/**
 * SLA 策略 Mapper，继承 {@link BaseMapper} 获得单表 CRUD 能力。
 *
 * <p>
 * 属于持久化层。接口由启动类上的 {@code @MapperScan("com.servicedesk.mapper")} 生成代理。
 * 建单按 {@code priority} + {@code enabled} 匹配策略，条件查询用 {@code LambdaQueryWrapper} 拼装，无需自定义 SQL。
 * </p>
 */
public interface SlaPolicyMapper extends BaseMapper<SlaPolicy> {
}
