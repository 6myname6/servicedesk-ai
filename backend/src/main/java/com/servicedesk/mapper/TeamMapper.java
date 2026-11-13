package com.servicedesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.servicedesk.entity.Team;

/**
 * 团队 Mapper，继承 {@link BaseMapper} 获得单表 CRUD 能力。
 *
 * <p>
 * 属于持久化层。接口由启动类上的 {@code @MapperScan("com.servicedesk.mapper")} 生成代理。
 * </p>
 */
public interface TeamMapper extends BaseMapper<Team> {
}
