package com.servicedesk.entity.enums;

/**
 * 工单事件类型（时间线），对应 ER 图第 6 节与开发文档 12.1 的字典。
 *
 * <p>
 * 属于枚举字典层。枚举名必须与 {@code ticket_event.type} 的存储值一致，禁止魔法字符串。
 * 事件只追加不修改，一条工单从建单到关闭会写入 3-8 条。
 * </p>
 *
 * <p>
 * 除 {@link #ESCALATE} 与 {@link #AI_SUGGEST} 外均已落地；RC-001 已删除
 * {@code WAIT_CUSTOMER} / {@code RESUME} / {@code REOPEN}，客户沟通改走私信，不再写时间线。
 * </p>
 */
public enum TicketEventType {

    /** 建单：报修人提交工单。 */
    CREATE,
    /** 编辑：修改标题、描述、分类、紧急度或设备，metadata.fields 记录被改字段名。 */
    UPDATE,
    /** 派单：主管指定处理工程师，写 assignee/team。 */
    ASSIGN,
    /** 接单：工程师确认接单，写首次响应时间。 */
    ACCEPT,
    /** 开始处理：工程师进入现场或开始排查。 */
    START,
    /** 处理记录 / 内部备注：content 为正文，internal 标记在 metadata。 */
    COMMENT,
    /** 解决：进入 RESOLVED，写 resolved_at。 */
    RESOLVE,
    /** 验收通过：进入终态 CLOSED，写 closed_at。 */
    CLOSE,
    /** 验收不通过：进入终态 REJECTED，不写 closed_at。 */
    REJECT,
    /** 取消：进入终态 CANCELLED，content 为取消原因。 */
    CANCEL,
    /** 评价：验收通过后报修人提交回访，metadata.score 记录分数，正文不双写。 */
    FEEDBACK,
    /** 超时升级：SLA 响应或解决超时，metadata 记录升级类型。 */
    ESCALATE,
    /** AI 建议：分类定级与摘要采纳记录。 */
    AI_SUGGEST,
    /** 附件：上传或删除附件，metadata 记录 action / attachmentId / fileName。 */
    ATTACH
}
