package com.servicedesk.domain.ticket;

import com.servicedesk.entity.enums.Role;
import com.servicedesk.entity.enums.TicketEventType;
import com.servicedesk.entity.enums.TicketStatus;

import java.util.Optional;
import java.util.Set;

/**
 * 工单状态迁移表：把「当前状态 + 目标状态」翻译成一次合法动作。
 *
 * <p>
 * 属于领域层。只回答「这次迁移叫什么动作、写哪种事件、哪些角色能做」，
 * 不查库、不写库、不依赖 Spring；时间戳副作用由 {@code TicketTransitionServiceImpl} 执行。
 * 表内容来自开发文档 7.13，两者冲突时先按文档流程提议，不在代码里自行裁决。
 * </p>
 *
 * <p>
 * 覆盖范围刻意只包含状态迁移接口（PATCH /tickets/{id}/status）可执行的五个动作：
 * 接单、开始处理、解决、验收通过、验收不通过。派单（7.12）与取消（7.11.1）各有独立接口，
 * 不在本表；{@link TicketStatus#ASSIGNED} 与 {@link TicketStatus#CANCELLED} 只能作为
 * 独立接口的结果出现。表里没有的组合一律视为非法迁移（409）。
 * </p>
 *
 * <p>
 * 角色只表达「这个动作允许哪类角色发起」，「必须是本单处理人 / 本团队成员」这类行级条件
 * 由数据范围（{@link TicketScope}）保证，本表不重复判定。
 * </p>
 */
public enum TicketTransition {

    /** 接单：已派单 → 已接单，仅被派工程师；首次响应时间由此动作补写。 */
    ACCEPT(Set.of(TicketStatus.ASSIGNED), TicketStatus.ACCEPTED,
            TicketEventType.ACCEPT, Set.of(Role.ENGINEER)),

    /** 开始处理：已接单 → 处理中，仅被派工程师；不产生时间戳。 */
    START(Set.of(TicketStatus.ACCEPTED), TicketStatus.IN_PROGRESS,
            TicketEventType.START, Set.of(Role.ENGINEER)),

    /** 解决：处理中 → 已解决，仅被派工程师；写 {@code resolved_at}，解决时钟停止。 */
    RESOLVE(Set.of(TicketStatus.IN_PROGRESS), TicketStatus.RESOLVED,
            TicketEventType.RESOLVE, Set.of(Role.ENGINEER)),

    /** 验收通过：已解决 → 已完成，终态；报修人本人 / 主管 / 管理员，写 {@code closed_at}。 */
    CLOSE(Set.of(TicketStatus.RESOLVED), TicketStatus.CLOSED,
            TicketEventType.CLOSE, Set.of(Role.REPORTER, Role.SUPERVISOR, Role.ADMIN)),

    /**
     * 验收不通过：已解决 → 不通过，终态。
     *
     * <p>
     * 不写 {@code closed_at}：该字段语义是「验收通过时刻」，不通过时间以 REJECT 事件为准，
     * 避免污染关闭时长统计。问题复现时新建工单，不提供返工流转。
     * </p>
     */
    REJECT(Set.of(TicketStatus.RESOLVED), TicketStatus.REJECTED,
            TicketEventType.REJECT, Set.of(Role.REPORTER, Role.SUPERVISOR, Role.ADMIN));

    /** 允许的起始状态；当前五个动作都只有一个起点，保留集合是为了以后扩展多起点。 */
    private final Set<TicketStatus> fromStatuses;

    /** 迁移完成后的状态。 */
    private final TicketStatus targetStatus;

    /** 需要写入 {@code ticket_event.type} 的事件类型。 */
    private final TicketEventType eventType;

    /** 允许发起该动作的角色集合；行级范围不在其中判定。 */
    private final Set<Role> allowedRoles;

    /**
     * 构造一条迁移定义。
     *
     * @param fromStatuses 允许的起始状态，至少一个
     * @param targetStatus 目标状态
     * @param eventType    迁移时写入时间线的事件类型
     * @param allowedRoles 允许发起该动作的角色
     */
    TicketTransition(Set<TicketStatus> fromStatuses, TicketStatus targetStatus,
            TicketEventType eventType, Set<Role> allowedRoles) {
        this.fromStatuses = fromStatuses;
        this.targetStatus = targetStatus;
        this.eventType = eventType;
        this.allowedRoles = allowedRoles;
    }

    /**
     * 允许的起始状态集合，用于文档与测试核对，调用方不要修改返回值。
     *
     * @return 不可变的起始状态集合
     */
    public Set<TicketStatus> fromStatuses() {
        return fromStatuses;
    }

    /**
     * 迁移后的目标状态。
     *
     * @return 目标状态
     */
    public TicketStatus targetStatus() {
        return targetStatus;
    }

    /**
     * 迁移对应的时间线事件类型。
     *
     * @return 事件类型
     */
    public TicketEventType eventType() {
        return eventType;
    }

    /**
     * 允许发起该动作的角色集合。
     *
     * @return 不可变的角色集合
     */
    public Set<Role> allowedRoles() {
        return allowedRoles;
    }

    /**
     * 按「当前状态 + 目标状态」解析合法迁移。
     *
     * <p>
     * 只做内存查表，不校验版本与角色：版本冲突（409）由守卫先判，
     * 角色不符（403）由调用方拿到动作后判，两者顺序见
     * {@code TicketTransitionServiceImpl#transitionStatus}。
     * </p>
     *
     * @param from   工单当前状态，取自数据库行
     * @param target 请求里的目标状态
     * @return 命中的迁移；任一参数为 null 或组合不合法时返回空
     */
    public static Optional<TicketTransition> resolve(TicketStatus from, TicketStatus target) {
        if (from == null || target == null) {
            return Optional.empty();
        }
        for (TicketTransition transition : values()) {
            if (transition.targetStatus == target && transition.fromStatuses.contains(from)) {
                return Optional.of(transition);
            }
        }
        return Optional.empty();
    }
}
