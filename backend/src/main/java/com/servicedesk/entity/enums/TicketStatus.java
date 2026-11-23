package com.servicedesk.entity.enums;

import com.servicedesk.common.exception.BusinessException;

import java.util.Locale;

/**
 * 工单状态（8 种），对应开发文档 12.1 与 ER 图第 6 节。
 *
 * <p>
 * 属于枚举字典层。枚举名必须与 {@code ticket.status} 的存储值一致，禁止使用魔法数字。
 * 建单只写 {@link #NEW}；其余状态由派单（F-01）与状态流转（F-02）维护。
 * </p>
 *
 * <p>
 * 终态 3 个：{@link #CLOSED}（已完成）、{@link #REJECTED}（不通过）、{@link #CANCELLED}（已取消），
 * 不可再迁出。RC-001 已删除「等客户」与「重开」两个状态，客户补充信息改用私信沟通。
 * </p>
 */
public enum TicketStatus {

    /** 新建：报修人已提交，尚未派单。 */
    NEW,
    /** 已派单：主管指定了处理工程师。 */
    ASSIGNED,
    /** 已接单：工程师确认接单。 */
    ACCEPTED,
    /** 处理中：工程师已开始处理。 */
    IN_PROGRESS,
    /** 已解决：工程师给出解决方案，等待验收。 */
    RESOLVED,
    /** 已完成：报修人验收通过，终态。 */
    CLOSED,
    /** 不通过：报修人验收不通过，终态；不写 {@code closed_at}，问题复现需新建工单。 */
    REJECTED,
    /** 已取消：报修人撤回或无效工单，终态。 */
    CANCELLED;

    /**
     * 严格解析状态，用于列表筛选与状态迁转入参。
     *
     * <p>
     * 与建单不同，筛选没有「缺省值」语义：传了就必须是合法状态，
     * 否则返回 400，避免把拼错的状态解释成「全部」。
     * </p>
     *
     * @param raw 请求参数
     * @return 状态枚举
     * @throws BusinessException 取值不在字典内时抛出 400
     */
    public static TicketStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, "工单状态不能为空");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (TicketStatus status : values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        throw new BusinessException(400, "工单状态不合法: " + raw);
    }
}
