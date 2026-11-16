package com.servicedesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.servicedesk.entity.Ticket;

/**
 * 工单 Mapper，继承 {@link BaseMapper} 获得单表 CRUD 能力。
 *
 * <p>
 * 属于持久化层。接口由启动类上的 {@code @MapperScan("com.servicedesk.mapper")} 生成代理。
 * 建单只用到 {@code insert}；列表/详情条件查询在 T-02、T-03 再按需追加方法。
 * </p>
 */
public interface TicketMapper extends BaseMapper<Ticket> {
}
