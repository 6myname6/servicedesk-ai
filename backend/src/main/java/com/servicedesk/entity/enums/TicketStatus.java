package com.servicedesk.entity.enums;

/**
 * 工单状态（9 种）。
 *
 * <p>
 * 属于枚举字典层。枚举名必须与 {@code ticket.status} 的存储值、ER 图第 6 节字典、
 * 产品文档 7.1 状态机保持一致，禁止使用魔法数字。
 * </p>
 *
 * <p>
 * 建单只写 {@link #NEW}；其余状态由派单（F-01）与状态流转（F-02）维护。
 * {@link #CLOSED} 与 {@link #CANCELLED} 是终态，不可再迁出。
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
    /** 等待客户：需要客户补充信息，SLA 暂停计时。 */
    WAITING_CUSTOMER,
    /** 已解决：工程师给出解决方案，等待验收。 */
    RESOLVED,
    /** 已关闭：报修人验收或系统关闭，终态。 */
    CLOSED,
    /** 已重开：验收不通过或问题复现，重新进入处理。 */
    REOPENED,
    /** 已取消：报修人撤回或无效工单，终态。 */
    CANCELLED
}
