package com.servicedesk.service.support;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.domain.ticket.TicketScope;
import com.servicedesk.entity.Team;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.TeamMapper;

import lombok.RequiredArgsConstructor;

/**
 * 工单数据范围解析器：把当前登录用户翻译成 {@link TicketScope}。
 *
 * <p>
 * 属于业务支撑层，是数据范围链路中唯一的 IO 点。规则本身在 {@code domain.ticket.TicketScope}
 * 里，本类只负责补齐它拿不到的那部分信息——主管管理的团队 ID 集合；领域层因此保持无 Spring、无数据库。
 * </p>
 *
 * <p>
 * 关键约束：
 * </p>
 * <ul>
 * <li>只有 {@code SUPERVISOR} 才查库，报修人/工程师/管理员的请求不产生额外查询；</li>
 * <li>主管的可见范围来自 {@code team.supervisor_id = 当前用户}，不能用 token 载荷里自己的
 * {@code teamId} 代替——自己所属的团队与管理的团队是两件事；</li>
 * <li>同一 Service 方法内只调用一次，把结果作为参数往下传，避免每查一次列表就多一次团队查询。</li>
 * </ul>
 */
@RequiredArgsConstructor
@Component
public class TicketScopeResolver {
    private final TeamMapper teamMapper;

    /**
     * 解析当前用户的工单可见范围。
     *
     * @param user 当前登录用户，不能为 null
     * @return 对应的工单范围，不会为 null
     * @throws com.servicedesk.common.exception.BusinessException 角色取值非法时抛出，状态码 401
     */
    public TicketScope resolve(LoginUser user) {
        Role role = Role.from(user.role());
        return switch (role) {
            case REPORTER -> TicketScope.of(role, user.id(), null);
            case ENGINEER -> TicketScope.of(role, user.id(), null);
            case ADMIN -> TicketScope.of(role, null, null);
            case SUPERVISOR -> TicketScope.of(role, user.id(), supervisorTeamIds(user.id()));
        };

    }

    /**
     * 查询当前用户作为主管所管理的团队 ID 集合。
     *
     * <p>
     * 来源是 {@code team.supervisor_id}，一个主管可以管理多个团队，因此返回集合；
     * 一个团队都没有时返回空集合，由 {@code TicketScope} 解释为「什么都看不到」。
     * </p>
     *
     * @param userId 当前用户 ID
     * @return 团队 ID 集合，查不到时为空集合，不会为 null
     */
    private Collection<Long> supervisorTeamIds(Long userId) {
        List<Team> teams = teamMapper.selectList(
                new LambdaQueryWrapper<Team>()
                        .eq(Team::getSupervisorId, userId));
        if (teams == null) {
            return List.of();
        }
        return teams.stream().map(Team::getId).toList();
    };
}
