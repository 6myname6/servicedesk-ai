package com.servicedesk.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.servicedesk.entity.TicketAttachment;
import com.servicedesk.entity.TicketEvent;
import com.servicedesk.entity.enums.TicketEventType;
import com.servicedesk.mapper.TicketEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 附件事件写入器：把「附件与工单关联」与「删除已关联附件」翻译成 {@code ATTACH} 事件。
 *
 * <p>
 * 属于业务支撑层。唯一职责是保证 ATTACH 的 metadata 结构统一：
 * {@code action}（upload / delete）、{@code attachmentId}、{@code fileName}，正文为可读摘要。
 * 不校验权限、不判断工单状态、不写附件表——这些由调用方在进入本类前完成。
 * </p>
 *
 * <p>
 * 关键约束：
 * </p>
 * <ul>
 * <li>只有附件已经或曾经关联工单时才调用：上传但未关联工单的附件不产生时间线；</li>
 * <li>调用方必须处于事务内，事件写入失败要连同业务写入一起回滚；</li>
 * <li>每次调用插入一行，批量挂附件时按附件顺序逐条写，时间线可据此还原挂载顺序。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TicketAttachEventWriter {

    /** 附件挂到工单时的动作标识，写入 {@code metadata.action}，与 RC-003 一致。 */
    private static final String ACTION_UPLOAD = "upload";

    /** 删除已关联附件时的动作标识，写入 {@code metadata.action}。 */
    private static final String ACTION_DELETE = "delete";

    /** 事件 Mapper，本类只插入，不查询也不修改。 */
    private final TicketEventMapper ticketEventMapper;

    /** JSON 工具，构造 metadata；不解析业务正文。 */
    private final ObjectMapper objectMapper;

    /**
     * 批量写入附件挂载事件。
     *
     * @param attachments 已成功回填 {@code ticket_id} 的附件，按期望的时间线顺序排列
     * @param operatorId  触发挂载的用户 ID
     * @param now         发生时间，建单时与工单创建时间同一个基准
     */
    public void writeUploads(List<TicketAttachment> attachments, Long operatorId, LocalDateTime now) {
        for (TicketAttachment attachment : attachments) {
            write(attachment, ACTION_UPLOAD, operatorId, now);
        }
    }

    /**
     * 写入附件删除事件，仅用于已关联工单的附件。
     *
     * @param attachment 即将删除的附件，必须已带 {@code ticket_id}
     * @param operatorId 执行删除的用户 ID，可能是上传人本人，也可能是管理员
     * @param now        发生时间
     */
    public void writeDelete(TicketAttachment attachment, Long operatorId, LocalDateTime now) {
        write(attachment, ACTION_DELETE, operatorId, now);
    }

    /**
     * 构造并插入单条 ATTACH 事件。
     *
     * @param attachment 附件实体
     * @param action     动作：upload / delete
     * @param operatorId 操作人 ID
     * @param now        发生时间
     */
    private void write(TicketAttachment attachment, String action, Long operatorId, LocalDateTime now) {
        TicketEvent event = new TicketEvent();
        event.setTicketId(attachment.getTicketId());
        event.setType(TicketEventType.ATTACH.name());
        event.setOperatorId(operatorId);
        // 正文给人读，metadata 给前端做结构化展示；文件名不是敏感信息
        event.setContent(ACTION_UPLOAD.equals(action) ? "上传附件：" + attachment.getFileName()
                : "删除附件：" + attachment.getFileName());
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("action", action);
        metadata.put("attachmentId", attachment.getId());
        metadata.put("fileName", attachment.getFileName());
        event.setMetadata(metadata.toString());
        event.setCreatedAt(now);
        ticketEventMapper.insert(event);
    }
}
