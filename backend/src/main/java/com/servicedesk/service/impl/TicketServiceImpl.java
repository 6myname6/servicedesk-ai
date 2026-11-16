package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.dto.request.ticket.TicketCreateRequest;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketAttachment;
import com.servicedesk.entity.enums.TicketCategory;
import com.servicedesk.entity.enums.TicketStatus;
import com.servicedesk.entity.enums.Urgency;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.TicketAttachmentMapper;
import com.servicedesk.mapper.TicketMapper;
import com.servicedesk.service.TicketService;
import com.servicedesk.service.support.TicketNoGenerator;
import com.servicedesk.vo.AttachmentVO;
import com.servicedesk.vo.SlaStatusVO;
import com.servicedesk.vo.TicketDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 工单服务实现（当前仅建单 T-01）。
 *
 * <p>
 * 属于业务实现层。建单主链路：
 * </p>
 * <ol>
 * <li>校验客户、设备归属、分类/紧急度取值与附件可用性——全部在写库前完成；</li>
 * <li>按紧急度匹配启用中的 SLA 策略，用同一基准时刻算出两个 deadline；</li>
 * <li>生成工单号并插入工单（`NEW`、暂停 0 分钟、AI 字段留空）；</li>
 * <li>把已上传附件的 {@code ticket_id} 回填为本工单。</li>
 * </ol>
 *
 * <p>
 * 关键约束：工单插入与附件回填在同一个事务内，附件回填失败要连工单一起回滚，
 * 不能留下「工单没有图」的半成品；工单号来自 Redis，回滚不会退号，允许跳号。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    /** 单次建单最多关联的附件数，与接口文档 2.2 一致。 */
    private static final int MAX_ATTACHMENTS = 5;

    /** 工单号唯一冲突时的最大尝试次数（含首次）。 */
    private static final int MAX_INSERT_ATTEMPTS = 2;

    /** 工单 Mapper。 */
    private final TicketMapper ticketMapper;

    /** 工单号生成器，Redis 日序列 + 库内兜底。 */
    private final TicketNoGenerator ticketNoGenerator;

    /** 客户 Mapper，用于校验客户存在性并回填客户快照。 */
    private final CustomerMapper customerMapper;

    /** 设备 Mapper，用于校验设备存在性与归属。 */
    private final DeviceMapper deviceMapper;

    /** SLA 策略 Mapper，按紧急度匹配启用策略。 */
    private final SlaPolicyMapper slaPolicyMapper;

    /** 附件 Mapper，用于校验并回填 {@code ticket_id}。 */
    private final TicketAttachmentMapper attachmentMapper;

    /** 业务时钟，建单时刻与剩余分钟都取它，禁止无参 {@code now()}。 */
    private final Clock clock;

    /**
     * 创建工单。
     *
     * @param request 建单入参
     * @return 新工单详情
     * @throws BusinessException 客户/设备/分类/紧急度/附件不合法抛 400；
     *                           SLA 策略缺失或工单号连续冲突抛 503
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO create(TicketCreateRequest request) {
        LoginUser operator = UserContext.getRequired();
        Customer customer = requireCustomer(request.getCustomerId());
        Device device = requireDeviceOfCustomer(request.getDeviceId(), customer.getId());
        TicketCategory category = TicketCategory.fromOrDefault(request.getCategory());
        Urgency urgency = Urgency.fromOrDefault(request.getUrgency());
        SlaPolicy policy = requirePolicy(urgency);
        List<TicketAttachment> attachments = resolveAttachments(request.getImageUrls(), operator.id());

        // 基准时刻只取一次并截到秒：createdAt 与两个 deadline 必须同源，且与 DATETIME(0) 的落库精度一致，
        // 否则「deadline - createdAt」会少算 1 分钟
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime responseDeadline = now.plusMinutes(policy.getResponseMinutes());
        LocalDateTime resolveDeadline = now.plusMinutes(policy.getResolveMinutes());

        Ticket ticket = buildTicket(request, customer, device, category, urgency, policy,
            operator.id(), now, responseDeadline, resolveDeadline);
        insertWithUniqueTicketNo(ticket);
        linkAttachments(attachments, ticket.getId());

        // 重新查一次拿数据库生成的 version / updated_at，避免响应里出现 null
        Ticket saved = ticketMapper.selectById(ticket.getId());
        log.info("建单成功, ticketId={}, ticketNo={}, category={}, urgency={}, reporterId={}, attachments={}",
            saved.getId(), saved.getTicketNo(), category, urgency, operator.id(), attachments.size());
        return toVO(saved, customer, device, operator, policy, attachments,
            now, responseDeadline, resolveDeadline);
    }

    /**
     * 组装待插入的工单实体。
     *
     * @param request          建单入参
     * @param customer         客户
     * @param device           设备，可为 null
     * @param category         分类
     * @param urgency          紧急度
     * @param policy           命中的 SLA 策略
     * @param reporterId       报修人 ID
     * @param now              建单时刻
     * @param responseDeadline 响应截止
     * @param resolveDeadline  解决截止
     * @return 工单实体，工单号留待插入前生成
     */
    private Ticket buildTicket(TicketCreateRequest request, Customer customer, Device device,
        TicketCategory category, Urgency urgency, SlaPolicy policy, Long reporterId,
        LocalDateTime now, LocalDateTime responseDeadline, LocalDateTime resolveDeadline) {
        Ticket ticket = new Ticket();
        ticket.setTitle(request.getTitle().trim());
        ticket.setDescription(request.getDescription().trim());
        ticket.setCategory(category.name());
        ticket.setUrgency(urgency.name());
        ticket.setStatus(TicketStatus.NEW.name());
        ticket.setCustomerId(customer.getId());
        ticket.setDeviceId(device == null ? null : device.getId());
        ticket.setReporterId(reporterId);
        ticket.setSlaPolicyId(policy.getId());
        ticket.setResponseDeadline(responseDeadline);
        ticket.setResolveDeadline(resolveDeadline);
        ticket.setPausedTotalMinutes(0);
        ticket.setAiCorrected(0);
        ticket.setCreatedAt(now);
        return ticket;
    }

    /**
     * 生成工单号并插入；唯一索引冲突时换号重试一次。
     *
     * <p>
     * 冲突只可能来自降级路径的并发撞号（Redis 正常时序列自增不会重复）。
     * 重试仍失败说明降级路径持续撞号，返回 503 让调用方重试。
     * </p>
     *
     * @param ticket 待插入工单
     * @throws BusinessException 连续两次冲突时抛出 503
     */
    private void insertWithUniqueTicketNo(Ticket ticket) {
        for (int attempt = 1; attempt <= MAX_INSERT_ATTEMPTS; attempt++) {
            ticket.setTicketNo(ticketNoGenerator.next());
            try {
                ticketMapper.insert(ticket);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt == MAX_INSERT_ATTEMPTS) {
                    log.error("工单号连续冲突，建单失败, ticketNo={}", ticket.getTicketNo(), e);
                    throw new BusinessException(503, "工单号生成冲突，请稍后重试");
                }
                log.warn("工单号唯一冲突，重新取号, ticketNo={}", ticket.getTicketNo());
            }
        }
    }

    /**
     * 把附件的 {@code ticket_id} 回填为本工单。
     *
     * @param attachments 已通过校验的附件
     * @param ticketId    新工单 ID
     */
    private void linkAttachments(List<TicketAttachment> attachments, Long ticketId) {
        if (attachments.isEmpty()) {
            return;
        }
        List<Long> attachmentIds = attachments.stream().map(TicketAttachment::getId).toList();
        attachmentMapper.update(null, Wrappers.<TicketAttachment>lambdaUpdate()
            .in(TicketAttachment::getId, attachmentIds)
            .set(TicketAttachment::getTicketId, ticketId));
        // 同步内存对象，响应里的 attachments 不必再查一次库
        attachments.forEach(attachment -> attachment.setTicketId(ticketId));
        log.info("建单关联附件成功, ticketId={}, attachmentIds={}", ticketId, attachmentIds);
    }

    /**
     * 校验客户存在。
     *
     * @param customerId 客户 ID
     * @return 客户实体
     * @throws BusinessException 客户不存在时抛出 400
     */
    private Customer requireCustomer(Long customerId) {
        Customer customer = customerMapper.selectById(customerId);
        if (customer == null) {
            log.warn("建单被拒绝：客户不存在, customerId={}, operatorId={}",
                customerId, UserContext.getUserId());
            throw new BusinessException(400, "客户不存在");
        }
        return customer;
    }

    /**
     * 校验设备存在且属于该客户。
     *
     * @param deviceId   设备 ID，可为 null
     * @param customerId 客户 ID
     * @return 设备实体，deviceId 为 null 时返回 null
     * @throws BusinessException 设备不存在或不属于该客户时抛出 400
     */
    private Device requireDeviceOfCustomer(Long deviceId, Long customerId) {
        if (deviceId == null) {
            return null;
        }
        Device device = deviceMapper.selectById(deviceId);
        if (device == null || !Objects.equals(device.getCustomerId(), customerId)) {
            log.warn("建单被拒绝：设备不存在或不属于该客户, deviceId={}, customerId={}, operatorId={}",
                deviceId, customerId, UserContext.getUserId());
            throw new BusinessException(400, "设备不存在或不属于该客户");
        }
        return device;
    }

    /**
     * 按紧急度匹配启用中的 SLA 策略。
     *
     * @param urgency 紧急度
     * @return SLA 策略
     * @throws BusinessException 没有启用策略时抛出 503
     */
    private SlaPolicy requirePolicy(Urgency urgency) {
        SlaPolicy policy = slaPolicyMapper.selectOne(Wrappers.<SlaPolicy>lambdaQuery()
            .eq(SlaPolicy::getPriority, urgency.name())
            .eq(SlaPolicy::getEnabled, 1));
        if (policy == null) {
            log.error("建单失败：紧急度无启用的 SLA 策略, urgency={}, operatorId={}",
                urgency, UserContext.getUserId());
            throw new BusinessException(503, "SLA 策略未配置，请联系管理员");
        }
        return policy;
    }

    /**
     * 校验并加载待关联的附件。
     *
     * <p>
     * 只接受「当前账号上传且尚未关联工单」的附件：否则任何登录用户都能把别人的图
     * 挂到自己的工单上，或者把一张图同时挂到多张工单上。
     * </p>
     *
     * @param imageUrls  请求里的附件 URL，可为 null
     * @param reporterId 当前用户 ID
     * @return 命中的附件，按入参顺序去重后的数量与结果一致；无附件时返回空列表
     * @throws BusinessException URL 不可用或超过 5 个时抛出 400
     */
    private List<TicketAttachment> resolveAttachments(List<String> imageUrls, Long reporterId) {
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
            log.warn("建单被拒绝：附件超过上限, size={}, operatorId={}", urls.size(), reporterId);
            throw new BusinessException(400, "最多关联 5 个附件");
        }

        List<TicketAttachment> attachments = attachmentMapper.selectList(
            Wrappers.<TicketAttachment>lambdaQuery()
                .in(TicketAttachment::getFileUrl, urls)
                .eq(TicketAttachment::getUploaderId, reporterId)
                .isNull(TicketAttachment::getTicketId));
        if (attachments.size() != urls.size()) {
            log.warn("建单被拒绝：附件不存在、非本人上传或已被关联, requested={}, matched={}, operatorId={}",
                urls.size(), attachments.size(), reporterId);
            throw new BusinessException(400, "附件不存在或不可用");
        }
        return attachments;
    }

    /**
     * 实体转工单详情视图。
     *
     * @param ticket           已落库工单
     * @param customer         客户
     * @param device           设备，可为 null
     * @param operator         当前用户
     * @param policy           命中的 SLA 策略
     * @param attachments      已关联附件
     * @param now              建单时刻，用于计算剩余分钟
     * @param responseDeadline 响应截止
     * @param resolveDeadline  解决截止
     * @return 工单详情视图
     */
    private TicketDetailVO toVO(Ticket ticket, Customer customer, Device device, LoginUser operator,
        SlaPolicy policy, List<TicketAttachment> attachments, LocalDateTime now,
        LocalDateTime responseDeadline, LocalDateTime resolveDeadline) {
        return TicketDetailVO.builder()
            .id(ticket.getId())
            .ticketNo(ticket.getTicketNo())
            .title(ticket.getTitle())
            .description(ticket.getDescription())
            .category(ticket.getCategory())
            .urgency(ticket.getUrgency())
            .status(ticket.getStatus())
            .customer(new TicketDetailVO.CustomerBrief(customer.getId(), customer.getName(), customer.getPhone()))
            .device(device == null ? null : new TicketDetailVO.DeviceBrief(
                device.getId(), device.getModel(), device.getSerialNo(), device.getWarrantyEnd()))
            .reporter(new TicketDetailVO.UserBrief(operator.id(), operator.displayName()))
            .assignee(null)
            .team(null)
            .sla(buildSla(policy, now, responseDeadline, resolveDeadline))
            .ai(null)
            .attachments(attachments.stream().map(this::toAttachmentVO).toList())
            .createdAt(ticket.getCreatedAt())
            .updatedAt(ticket.getUpdatedAt())
            .version(ticket.getVersion() == null ? 0 : ticket.getVersion())
            .build();
    }

    /**
     * 组装 SLA 状态：未派单工单没有首次响应，也不处于暂停。
     *
     * @param policy           命中的策略
     * @param now              当前时刻
     * @param responseDeadline 响应截止
     * @param resolveDeadline  解决截止
     * @return SLA 状态视图
     */
    private SlaStatusVO buildSla(SlaPolicy policy,
        LocalDateTime now, LocalDateTime responseDeadline, LocalDateTime resolveDeadline) {
        long responseRemaining = Duration.between(now, responseDeadline).toMinutes();
        long resolveRemaining = Duration.between(now, resolveDeadline).toMinutes();
        return SlaStatusVO.builder()
            .policyId(policy.getId())
            .policyName(policy.getName())
            .responseDeadline(responseDeadline)
            .resolveDeadline(resolveDeadline)
            .firstResponseAt(null)
            .paused(false)
            .pausedTotalMinutes(0)
            .responseRemainingMinutes(responseRemaining)
            .resolveRemainingMinutes(resolveRemaining)
            .responseBreached(now.isAfter(responseDeadline))
            .resolveBreached(now.isAfter(resolveDeadline))
            .build();
    }

    /**
     * 附件实体转视图。
     *
     * @param attachment 附件实体
     * @return 附件视图
     */
    private AttachmentVO toAttachmentVO(TicketAttachment attachment) {
        return AttachmentVO.builder()
            .id(attachment.getId())
            .fileName(attachment.getFileName())
            .fileUrl(attachment.getFileUrl())
            .fileSize(attachment.getFileSize())
            .mimeType(attachment.getMimeType())
            .createdAt(attachment.getCreatedAt())
            .build();
    }
}
