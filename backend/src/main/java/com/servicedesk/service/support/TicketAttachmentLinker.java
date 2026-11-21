package com.servicedesk.service.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.entity.TicketAttachment;
import com.servicedesk.mapper.TicketAttachmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 工单附件关联器：把「已上传但未挂单」的附件校验并回填到工单。
 *
 * <p>
 * 属于业务支撑层。只处理 {@code ticket_attachment} 单表，不感知工单状态与权限；
 * 调用方（建单）必须处于同一事务内，回填失败要连工单一起回滚，不能留下「工单没有图」的半成品。
 * </p>
 *
 * <p>
 * 关键约束：两个方法都按「上传人 + 尚未关联」过滤。不加这两个条件，任何登录用户都能把别人的图
 * 挂到自己单上，或者一张图同时挂在多张单上。回填时再带一次条件是为了堵住
 * 「校验通过后、回填之前被另一个请求抢走」的 TOCTOU 窗口。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketAttachmentLinker {

    /** 单次建单最多关联的附件数，与接口文档 2.2 一致。 */
    private static final int MAX_ATTACHMENTS = 5;

    /** 附件不可用时的统一提示，涵盖不存在、非本人上传与已被关联三种情况。 */
    private static final String MSG_UNAVAILABLE = "附件不存在或不可用";

    /** 附件 Mapper，用于查询待关联附件与回填 {@code ticket_id}。 */
    private final TicketAttachmentMapper attachmentMapper;

    /**
     * 校验并加载待关联的附件。
     *
     * <p>
     * 只接受「当前账号上传且尚未关联工单」的附件。返回数量与去重后的 URL 数量不一致时说明
     * 其中至少一个不可用，统一按 400 拒绝，不逐个指出是哪一个，避免探测他人上传记录。
     * </p>
     *
     * @param imageUrls  请求里的附件 URL，可为 null
     * @param uploaderId 当前用户 ID，附件必须由他上传
     * @return 命中的附件；无附件时返回空列表
     * @throws BusinessException 超过上限或 URL 不可用时抛出 400
     */
    public List<TicketAttachment> resolveAttachments(List<String> imageUrls, Long uploaderId) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return List.of();
        }
        List<String> urls = imageUrls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(url -> !url.isEmpty())
                .distinct()
                .toList();
        if (urls.isEmpty()) {
            return List.of();
        }
        if (urls.size() > MAX_ATTACHMENTS) {
            log.warn("建单被拒绝：附件超过上限, size={}, operatorId={}", urls.size(), uploaderId);
            throw new BusinessException(400, "最多关联 " + MAX_ATTACHMENTS + " 个附件");
        }

        List<TicketAttachment> attachments = attachmentMapper.selectList(
                Wrappers.<TicketAttachment>lambdaQuery()
                        .in(TicketAttachment::getFileUrl, urls)
                        .eq(TicketAttachment::getUploaderId, uploaderId)
                        .isNull(TicketAttachment::getTicketId));
        if (attachments.size() != urls.size()) {
            log.warn("建单被拒绝：附件不存在、非本人上传或已被关联, requested={}, matched={}, operatorId={}",
                    urls.size(), attachments.size(), uploaderId);
            throw new BusinessException(400, MSG_UNAVAILABLE);
        }
        return attachments;
    }

    /**
     * 把附件的 {@code ticket_id} 回填为本工单。
     *
     * <p>
     * 回填时重复「上传人 + 未关联」条件，防止并发下另一个请求在校验之后抢先绑定同一附件；
     * 影响行数与入参数量不等即视为被抢占，抛 409 让整个建单事务回滚，不返回「部分图挂上了」的结果。
     * </p>
     *
     * @param attachments 已通过 {@link #resolveAttachments} 校验的附件
     * @param ticketId    新工单 ID
     * @param uploaderId  当前用户 ID，与校验时保持一致
     * @throws BusinessException 并发下附件已被其他工单占用时抛出 409
     */
    public void linkAttachments(List<TicketAttachment> attachments, Long ticketId, Long uploaderId) {
        if (attachments.isEmpty()) {
            return;
        }
        List<Long> attachmentIds = attachments.stream().map(TicketAttachment::getId).toList();
        int updated = attachmentMapper.update(null, Wrappers.<TicketAttachment>lambdaUpdate()
                .in(TicketAttachment::getId, attachmentIds)
                .eq(TicketAttachment::getUploaderId, uploaderId)
                .isNull(TicketAttachment::getTicketId)
                .set(TicketAttachment::getTicketId, ticketId));
        if (updated != attachmentIds.size()) {
            log.warn("建单关联附件失败：附件已被并发占用, ticketId={}, expected={}, updated={}",
                    ticketId, attachmentIds.size(), updated);
            throw new BusinessException(409, "附件已被使用，请重新上传");
        }
        // 同步内存对象，响应里的 attachments 不必再查一次库
        attachments.forEach(attachment -> attachment.setTicketId(ticketId));
        log.info("建单关联附件成功, ticketId={}, attachmentIds={}", ticketId, attachmentIds);
    }
}
