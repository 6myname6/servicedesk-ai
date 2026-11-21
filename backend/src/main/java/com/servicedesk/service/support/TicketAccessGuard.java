package com.servicedesk.service.support;

import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.domain.ticket.TicketScope;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.entity.enums.TicketStatus;
import com.servicedesk.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工单访问守卫：回答「这张单能不能看、能不能改」，不负责装配也不负责写库。
 *
 * <p>
 * 属于业务支撑层。三个方法的判定顺序是契约的一部分，调用方必须按
 * {@link #requireVisible} → {@link #assertEditable} → {@link #assertVersionMatches} 排列：
 * 先 404 再 409，否则会通过 409 确认「工单存在」。
 * </p>
 *
 * <p>
 * 依赖方向单向：本类只依赖 {@code TicketMapper} 与领域层的 {@code TicketScope}，
 * 不依赖 Service，也不感知具体是详情还是编辑场景。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketAccessGuard {

    /** 工单不存在或越权时的统一提示，两个分支必须一字不差，避免资源枚举。 */
    public static final String MSG_NOT_FOUND = "工单不存在";

    /** 乐观锁冲突提示，编辑与并发兜底共用，避免两处文案漂移。 */
    public static final String MSG_VERSION_CONFLICT = "工单已被他人修改，请刷新后重试";

    /** 工单 Mapper，用于按 ID 取行。 */
    private final TicketMapper ticketMapper;

    /**
     * 查询工单并校验数据范围，越权与不存在统一 404。
     *
     * <p>
     * 两个分支必须返回同一个提示：区分「不存在」与「无权查看」等于对外提供一个资源枚举接口。
     * 判定入参取自数据库行，不取请求参数，避免伪造 reporterId / teamId 绕过范围。
     * </p>
     *
     * @param id       工单 ID
     * @param scope    当前用户的数据范围
     * @param operator 当前登录用户，用于日志
     * @return 工单实体
     * @throws BusinessException 工单不存在或不在数据范围内时抛出 404
     */
    public Ticket requireVisible(Long id, TicketScope scope, LoginUser operator) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            log.warn("工单不存在, ticketId={}, operatorId={}", id, operator.id());
            throw new BusinessException(404, MSG_NOT_FOUND);
        }
        if (!scope.allows(ticket.getReporterId(), ticket.getAssigneeId(), ticket.getTeamId())) {
            log.warn("工单越权访问被拒绝, ticketId={}, operatorId={}, role={}",
                    id, operator.id(), operator.role());
            throw new BusinessException(404, MSG_NOT_FOUND);
        }
        return ticket;
    }

    /**
     * 校验当前用户与工单状态是否允许编辑。
     *
     * <p>
     * 走到这里说明数据范围已通过：报修人的范围只放行本人，因此这里不再判「是否本人」——
     * 再判一次会把本该 404 的请求变成 409，反而确认了工单存在。
     * </p>
     *
     * @param ticket   工单实体
     * @param operator 当前登录用户
     * @throws BusinessException 终态或报修人对非 NEW 工单发起编辑时抛出 409
     */
    public void assertEditable(Ticket ticket, LoginUser operator) {
        TicketStatus status = TicketStatus.from(ticket.getStatus());
        if (status == TicketStatus.CLOSED || status == TicketStatus.CANCELLED) {
            log.warn("编辑被拒绝：工单已结束, ticketId={}, status={}, operatorId={}",
                    ticket.getId(), status, operator.id());
            throw new BusinessException(409, "工单已结束，不能编辑");
        }
        if (Role.from(operator.role()) == Role.REPORTER && status != TicketStatus.NEW) {
            log.warn("编辑被拒绝：报修人仅未派单前可改, ticketId={}, status={}, operatorId={}",
                    ticket.getId(), status, operator.id());
            throw new BusinessException(409, "工单已派单，不能编辑");
        }
    }

    /**
     * 校验请求里的版本号与库里当前版本一致。
     *
     * <p>
     * 必须显式比较：MyBatis-Plus 乐观锁用的是「查出来的实体」上的 {@code version}，
     * 请求里的版本号不会自动参与 WHERE 条件。少了这一步，客户端拿旧页面提交也会成功覆盖，
     * 乐观锁形同虚设；{@code updateById} 返回 0 只兜住「两个请求同时提交」的竞态。
     * </p>
     *
     * @param ticket         库里查出的工单
     * @param requestVersion 请求携带的版本号
     * @param operator       当前登录用户，用于日志
     * @throws BusinessException 版本不一致时抛出 409
     */
    public void assertVersionMatches(Ticket ticket, Integer requestVersion, LoginUser operator) {
        int currentVersion = ticket.getVersion() == null ? 0 : ticket.getVersion();
        if (requestVersion == null || currentVersion != requestVersion) {
            log.warn("编辑被拒绝：版本冲突, ticketId={}, operatorId={}, requestVersion={}, currentVersion={}",
                    ticket.getId(), operator.id(), requestVersion, currentVersion);
            throw new BusinessException(409, MSG_VERSION_CONFLICT);
        }
    }
}
