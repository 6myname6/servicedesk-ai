package com.servicedesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.servicedesk.entity.TicketEvent;

/**
 * 工单事件 Mapper，继承 {@link BaseMapper} 获得单表 CRUD 能力。
 *
 * <p>
 * 属于持久化层。时间线只追加不修改，业务代码只应调用 {@code insert} 与查询方法，
 * 不要对本表执行 update / delete。
 * </p>
 */
public interface TicketEventMapper extends BaseMapper<TicketEvent> {
}
