package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.domain.ticket.TicketScope;
import com.servicedesk.dto.request.ticket.TicketCommentRequest;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketEvent;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.entity.enums.TicketEventType;
import com.servicedesk.entity.enums.TicketStatus;
import com.servicedesk.mapper.TicketEventMapper;
import com.servicedesk.mapper.TicketMapper;
import com.servicedesk.service.TicketCommentService;
import com.servicedesk.service.support.TicketAccessGuard;
import com.servicedesk.service.support.TicketAssembler;
import com.servicedesk.service.support.TicketScopeResolver;
import com.servicedesk.vo.TicketEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工单处理记录与时间线实现（F-03、F-04），对应接口文档 3.3 / 3.4。
 *
 * <p>
 * 属于业务实现层。只做两件事：追加 {@code COMMENT} 事件、按角色读出时间线；
 * 不改工单状态、不写 SLA 时间戳（首响按 7.14 的例外补写一次）。
 * </p>
 *
 * <p>
 * 关键约束：
 * </p>
 * <ul>
 * <li>判定顺序固定为「数据范围 404 → 终态 409 → 内部备注越权 400 → 正文 400」；</li>
 * <li>内部备注的可见性过滤下推到 SQL，先过滤再取 500 条上限，避免内部备注挤占报修人可见的事件；</li>
 * <li>首响只在工程师写记录且原值为空时补写，条件更新带 {@code first_response_at IS NULL}，
 * 与接单并发时以先落库者为准；</li>
 * <li>正文与备注原文不写日志，只记长度与角色，避免客户现场细节进入日志系统。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketCommentServiceImpl implements TicketCommentService {

    /** 时间线硬上限：不分页后必须有兜底，超出时只返回最近 500 条（RC-004）。 */
    private static final int TIMELINE_LIMIT = 500;

    /** 处理记录正文上限，与接口文档 3.3.2 及开发文档 7.14 一致。 */
    private static final int MAX_CONTENT_LENGTH = 2000;

    /** 终态写处理记录的统一提示。 */
    private static final String MSG_FINISHED = "工单已结束，不能追加处理记录";

    /** 报修人写内部备注的统一提示。 */
    private static final String MSG_REPORTER_INTERNAL = "报修人不能写内部备注";

    /** 正文去空白后为空的提示，与 Bean Validation 的文案保持一致。 */
    private static final String MSG_BLANK = "处理记录内容不能为空";

    /** 正文超长提示。 */
    private static final String MSG_TOO_LONG = "处理记录不能超过 2000 字";

    /**
     * 报修人可见性条件：只放行 {@code metadata.internal} 不为 true 的事件。
     *
     * <p>
     * 没有内部标记（系统事件、历史数据）按 public 处理；表达式为常量，不接受任何外部拼接。
     * </p>
     */
    private static final String REPORTER_VISIBLE_FILTER =
            "COALESCE(JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.internal')), 'false') = 'false'";

    /** 工单 Mapper，用于补写首次响应时间的条件更新。 */
    private final TicketMapper ticketMapper;

    /** 工单事件 Mapper，处理记录只插入、时间线只查询。 */
    private final TicketEventMapper ticketEventMapper;

    /** 数据范围解析器，把登录身份翻译成工单可见范围。 */
    private final TicketScopeResolver ticketScopeResolver;

    /** 访问守卫，保证越权与不存在统一 404。 */
    private final TicketAccessGuard ticketAccessGuard;

    /** 事件装配器，批量回填操作人姓名与角色并解析 metadata。 */
    private final TicketAssembler ticketAssembler;

    /** 业务时钟，事件时间与首响时间取同一基准，禁止无参 {@code now()}。 */
    private final Clock clock;

    /** JSON 序列化工具，只用于写 {@code metadata.internal}，不解析业务正文。 */
    private final ObjectMapper objectMapper;

    /**
     * 追加一条处理记录或内部备注。
     *
     * @param id      工单 ID
     * @param request 处理记录入参，正文必填
     * @return 新写入的事件视图
     * @throws BusinessException 不存在或越权抛 404；工单已终态抛 409；
     *                           报修人写内部备注或正文不合法抛 400
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketEventVO addComment(Long id, TicketCommentRequest request) {
        LoginUser operator = UserContext.getRequired();
        TicketScope scope = ticketScopeResolver.resolve(operator);
        Ticket ticket = ticketAccessGuard.requireVisible(id, scope, operator);

        TicketStatus status = TicketStatus.from(ticket.getStatus());
        if (isFinished(status)) {
            log.warn("追加处理记录被拒绝：工单已结束, ticketId={}, status={}, operatorId={}",
                    id, status, operator.id());
            throw new BusinessException(409, MSG_FINISHED);
        }

        Role role = Role.from(operator.role());
        boolean internal = Boolean.TRUE.equals(request.getInternal());
        if (internal && role == Role.REPORTER) {
            log.warn("追加处理记录被拒绝：报修人不能写内部备注, ticketId={}, operatorId={}", id, operator.id());
            throw new BusinessException(400, MSG_REPORTER_INTERNAL);
        }

        String content = request.getContent() == null ? null : request.getContent().trim();
        if (content == null || content.isEmpty()) {
            log.warn("追加处理记录被拒绝：正文为空, ticketId={}, operatorId={}", id, operator.id());
            throw new BusinessException(400, MSG_BLANK);
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            log.warn("追加处理记录被拒绝：正文超长, ticketId={}, operatorId={}, contentLength={}",
                    id, operator.id(), content.length());
            throw new BusinessException(400, MSG_TOO_LONG);
        }

        // 事件时间、首响时间与落库精度（DATETIME(0)）保持一致，统一截到秒
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        TicketEvent event = new TicketEvent();
        event.setTicketId(ticket.getId());
        event.setType(TicketEventType.COMMENT.name());
        event.setOperatorId(operator.id());
        event.setContent(content);
        // internal 固定写在 metadata：时间线过滤与出参解析都以这一个字段为准
        event.setMetadata(objectMapper.createObjectNode().put("internal", internal).toString());
        event.setCreatedAt(now);
        ticketEventMapper.insert(event);

        // 首响取最早：只在工程师写记录且原值为空时补写，条件更新避免与接单并发时覆盖更早的值
        if (role == Role.ENGINEER && ticket.getFirstResponseAt() == null) {
            int updated = ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
                    .eq(Ticket::getId, ticket.getId())
                    .isNull(Ticket::getFirstResponseAt)
                    .set(Ticket::getFirstResponseAt, now));
            if (updated > 0) {
                log.info("处理记录补写首次响应时间, ticketId={}, firstResponseAt={}", id, now);
            }
        }

        log.info("追加工单处理记录成功, ticketId={}, eventId={}, internal={}, role={}, contentLength={}",
                id, event.getId(), internal, role, content.length());
        return ticketAssembler.assembleEvents(List.of(event)).get(0);
    }

    /**
     * 查询工单时间线，一次返回全部可见事件。
     *
     * @param id 工单 ID
     * @return 按时间正序的事件列表，最多 500 条
     * @throws BusinessException 工单不存在或不在数据范围内时抛出 404
     */
    @Override
    public List<TicketEventVO> listEvents(Long id) {
        LoginUser operator = UserContext.getRequired();
        TicketScope scope = ticketScopeResolver.resolve(operator);
        Ticket ticket = ticketAccessGuard.requireVisible(id, scope, operator);
        Role role = Role.from(operator.role());

        LambdaQueryWrapper<TicketEvent> query = Wrappers.<TicketEvent>lambdaQuery()
                .eq(TicketEvent::getTicketId, ticket.getId());
        if (role == Role.REPORTER) {
            query.apply(REPORTER_VISIBLE_FILTER);
        }
        // 先按倒序截最近 500 条，再翻转成时间正序；正序 + LIMIT 会丢掉最新进展
        query.orderByDesc(TicketEvent::getCreatedAt)
                .orderByDesc(TicketEvent::getId)
                .last("LIMIT " + TIMELINE_LIMIT);
        List<TicketEvent> events = new ArrayList<>(ticketEventMapper.selectList(query));
        Collections.reverse(events);

        log.debug("查询工单时间线, ticketId={}, role={}, count={}", id, role, events.size());
        return ticketAssembler.assembleEvents(events);
    }

    /**
     * 判断工单是否已到终态。
     *
     * <p>
     * 三个终态 {@code CLOSED} / {@code REJECTED} / {@code CANCELLED} 都不可再写；
     * 判定集中在本方法，状态字典变化时调用方不需要改。
     * </p>
     *
     * @param status 当前状态
     * @return true 表示终态，不可再追加处理记录
     */
    private boolean isFinished(TicketStatus status) {
        return status == TicketStatus.CLOSED
                || status == TicketStatus.REJECTED
                || status == TicketStatus.CANCELLED;
    }
}
