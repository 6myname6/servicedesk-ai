package com.servicedesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.servicedesk.entity.TicketAttachment;

/**
 * 工单附件 Mapper，继承 {@link BaseMapper} 获得单表 CRUD 能力。
 *
 * <p>
 * 属于持久化层。接口由启动类上的 {@code @MapperScan("com.servicedesk.mapper")} 生成代理；
 * F-05 只用到 {@code insert} / {@code selectById} / {@code deleteById}。
 * </p>
 */
public interface TicketAttachmentMapper extends BaseMapper<TicketAttachment> {
}
