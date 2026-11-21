package com.servicedesk.entity.enums;

/**
 * 工单事件类型（时间线），对应 ER 图第 6 节与产品文档 12.1 的字典。
 *
 * <p>
 * 属于枚举字典层。枚举名必须与 {@code ticket_event.type} 的存储值一致，禁止魔法字符串。
 * 事件只追加不修改，一条工单从建单到关闭会写入 3-8 条。
 * </p>
 *
 * <p>
 * 本期只落地 {@link #CREATE} 与 {@link #CANCEL}，其余取值先按契约定义，
 * 等派单（F-01）、状态机（F-02）、处理记录（F-03/F-04）接入时直接使用，不再改字典。
 * </p>
 */
public enum TicketEventType {

    /** 建单：报修人提交工单。 */
    CREATE,
    /** 派单：主管指定处理工程师，写 assignee/team。 */
    ASSIGN,
    /** 接单：工程师确认接单，写首次响应时间。 */
    ACCEPT,
    /** 开始处理：工程师进入现场或开始排查。 */
    START,
    /** 等待客户：进入 WAITING_CUSTOMER，SLA 暂停。 */
    WAIT_CUSTOMER,
    /** 恢复处理：客户回复后回到 IN_PROGRESS，补回暂停时长。 */
    RESUME,
    /** 处理记录 / 内部备注：content 为正文，internal 标记在 metadata。 */
    COMMENT,
    /** 解决：进入 RESOLVED，写 resolved_at。 */
    RESOLVE,
    /** 关闭：验收通过，进入终态 CLOSED。 */
    CLOSE,
    /** 重开：验收不通过或问题复现。 */
    REOPEN,
    /** 取消：进入终态 CANCELLED，content 为取消原因。 */
    CANCEL,
    /** 超时升级：SLA 响应或解决超时，metadata 记录升级类型。 */
    ESCALATE,
    /** AI 建议：分类定级与摘要采纳记录。 */
    AI_SUGGEST,
    /** 附件：上传或删除附件。 */
    ATTACH
}
