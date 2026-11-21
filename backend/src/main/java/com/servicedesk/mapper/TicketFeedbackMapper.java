package com.servicedesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.servicedesk.entity.TicketFeedback;

/**
 * 工单回访 Mapper，继承 {@link BaseMapper} 获得单表 CRUD 能力。
 *
 * <p>
 * 属于持久化层。一单一评由唯一索引 {@code uk_ticket} 兜底，并发插入时捕获
 * {@code DuplicateKeyException} 转 409，不要依赖「先查后写」在并发下仍然成立。
 * </p>
 */
public interface TicketFeedbackMapper extends BaseMapper<TicketFeedback> {
}
