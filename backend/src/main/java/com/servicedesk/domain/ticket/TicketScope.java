package com.servicedesk.domain.ticket;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.entity.enums.Role;

/**
 * 工单数据范围：决定「当前用户能看到哪些工单」。
 *
 * <p>
 * 属于领域层。不依赖 Spring、不访问数据库、不做任何 IO：主管的团队 ID 集合由调用方（Service）
 * 查好后传入，本类只负责把角色翻译成可见范围，并判定某一行工单是否落在范围内。
 * </p>
 *
 * <p>
 * 构造入口只有 {@link #of(Role, Long, Collection)} 一个，业务代码不要直接 new 四个实现。
 * 列表过滤与详情判定共用 {@link #allows(Long, Long, Long)}，保证「列表里看不到的，详情也打不开」。
 * </p>
 *
 * <p>
 * 关键约束：
 * </p>
 * <ul>
 * <li>{@link Supervisor} 的团队集合为空，表示「什么都看不到」，绝不能被解释成「不限条件」；</li>
 * <li>{@link #allows(Long, Long, Long)} 的入参必须来自数据库查出的工单行，不能来自请求参数；</li>
 * <li>四个实现互斥：管理员不持有任何身份字段，因此不存在「既全放开又限定某人」的非法组合。</li>
 * </ul>
 */
public sealed interface TicketScope {

    /**
     * 判断一行工单是否落在本范围内。
     *
     * <p>
     * 与列表查询共用同一套规则：列表用它生成 where 条件，详情用它做内存判定，
     * 两者口径不一致就会产生「列表看不到、改个 URL 就能打开」的越权。
     * </p>
     *
     * @param reporterId 工单的报修人 ID，取自数据库，允许为 null
     * @param assigneeId 工单的当前处理人 ID，未派单时为 null
     * @param teamId     工单的归属团队 ID，未派单时为 null
     * @return true 表示当前用户可见；参数为 null 时一律返回 false，不抛异常
     */
    boolean allows(Long reporterId, Long assigneeId, Long teamId);

    /**
     * 把数据范围翻译成可下推到数据库的查询条件描述。
     *
     * <p>
     * 列表接口必须把范围下推到 SQL，否则分页的 {@code total} 会把不可见行算进去。
     * 领域层只返回「按什么字段过滤」这一事实，不依赖 MyBatis，翻译成 SQL 由 Service 完成。
     * </p>
     *
     * @return 查询条件描述，不会返回 null
     */
    Visibility visibility();

    /**
     * 数据范围的查询条件描述，字段互斥：最多只有一项生效。
     *
     * @param unrestricted true 表示管理员不加行级条件
     * @param reporterId   按报修人过滤的条件，其余条件生效时为 null
     * @param assigneeId   按处理人过滤的条件，其余条件生效时为 null
     * @param teamIds      按归属团队过滤的集合；非管理员且前两项为 null 时生效，
     *                     空集合表示「什么都不看到」，不能退化成不过滤
     */
    record Visibility(boolean unrestricted, Long reporterId, Long assigneeId, Set<Long> teamIds) {
    }

    /** 管理员：不加任何条件，可见全部工单。 */
    record All() implements TicketScope {

        /**
         * 管理员不做任何行级过滤。
         *
         * @param reporterId 工单报修人 ID，忽略
         * @param assigneeId 工单处理人 ID，忽略
         * @param teamId     工单归属团队 ID，忽略
         * @return 恒为 true
         */
        @Override
        public boolean allows(Long reporterId, Long assigneeId, Long teamId) {
            // 直接无条件查看
            return true;
        }

        /**
         * 管理员不产生任何 where 条件。
         *
         * @return unrestricted=true 的可见性描述
         */
        @Override
        public Visibility visibility() {
            return new Visibility(true, null, null, Set.of());
        }
    }

    /**
     * 报修人范围：只能看到自己提交的工单，即使自己是该单的处理人也不算命中。
     *
     * @param userId 当前登录用户 ID，构造时已保证非空
     */
    record Reporter(Long userId) implements TicketScope {

        /**
         * 按报修人判定。
         *
         * @param reporterId 工单报修人 ID
         * @param assigneeId 工单处理人 ID，忽略
         * @param teamId     工单归属团队 ID，忽略
         * @return 工单报修人等于当前用户时为 true
         */
        @Override
        public boolean allows(Long reporterId, Long assigneeId, Long teamId) {
            return Objects.equals(userId, reporterId);
        }

        /**
         * 只按报修人下推条件。
         *
         * @return reporterId 为当前用户的可见性描述
         */
        @Override
        public Visibility visibility() {
            return new Visibility(false, userId, null, Set.of());
        }
    }

    /**
     * 工程师范围：只能看到指派给自己的工单。
     *
     * <p>
     * 刻意不按团队过滤：工程师的待办列表必须干净，否则会出现「顺手修了同事的单」，
     * 责任边界随之模糊。
     * </p>
     *
     * @param userId 当前登录用户 ID，构造时已保证非空
     */
    record Engineer(Long userId) implements TicketScope {

        /**
         * 按处理人判定。
         *
         * @param reporterId 工单报修人 ID，忽略
         * @param assigneeId 工单当前处理人 ID
         * @param teamId     工单归属团队 ID，忽略
         * @return 工单处理人等于当前用户时为 true
         */
        @Override
        public boolean allows(Long reporterId, Long assigneeId, Long teamId) {
            return Objects.equals(userId, assigneeId);
        }

        /**
         * 只按处理人下推条件，刻意不加团队条件。
         *
         * @return assigneeId 为当前用户的可见性描述
         */
        @Override
        public Visibility visibility() {
            return new Visibility(false, null, userId, Set.of());
        }
    }

    /**
     * 主管范围：可见本团队工单，团队范围来自 {@code team.supervisor_id = 当前用户}。
     *
     * <p>
     * {@code teamIds} 为空集合是合法状态（该主管未管理任何团队），语义是「什么都看不到」，
     * 不能跳过条件、也不能退化成全量可见。
     * </p>
     *
     * @param teamIds 我管理的团队 ID 集合，不可变、已去重；不允许为 null（调用方无团队时传空集合）
     */
    record Supervisor(Set<Long> teamIds) implements TicketScope {

        /**
         * 按归属团队判定。
         *
         * @param reporterId 工单报修人 ID，忽略
         * @param assigneeId 工单处理人 ID，忽略
         * @param teamId     工单归属团队 ID，未派单时为 null，此时判定为不可见
         * @return 工单归属团队在我管理的团队集合内时为 true
         */
        @Override
        public boolean allows(Long reporterId, Long assigneeId, Long teamId) {
            return teamId != null && teamIds.contains(teamId);
        }

        /**
         * 按归属团队下推条件；团队集合为空时由持久化层翻译成「恒假」。
         *
         * @return teamIds 为管理团队集合的可见性描述
         */
        @Override
        public Visibility visibility() {
            return new Visibility(false, null, null, teamIds);
        }
    }

    /**
     * 按角色构造工单范围，是所有业务调用的唯一入口。
     *
     * <p>
     * 校验刻意写在各个分支内部而不是方法开头：管理员不需要任何身份信息，
     * 若在开头统一判空，管理员会因为「没传 userId」而登录失败。
     * </p>
     *
     * @param role    当前用户角色，不能为 null
     * @param userId  当前用户 ID，REPORTER / ENGINEER 时必填
     * @param teamIds 我管理的团队 ID 集合，SUPERVISOR 时必填且允许为空集合
     * @return 对应的工单范围，不会为 null
     * @throws BusinessException     REPORTER / ENGINEER 缺少 userId 时抛出，状态码 401
     * @throws IllegalStateException SUPERVISOR 的 teamIds 为 null 时抛出，属于调用方漏传
     */
    static TicketScope of(Role role, Long userId, Collection<Long> teamIds) {
        return switch (role) {
            case ADMIN -> new All();
            case REPORTER -> new Reporter(requiredUserId(userId));
            case ENGINEER -> new Engineer(requiredUserId(userId));
            case SUPERVISOR -> new Supervisor(copyTeamIds(teamIds));
        };
    }

    /**
     * 校验当前用户 ID 存在。
     *
     * <p>
     * 缺失说明登录态不完整，属于用户可自愈的问题（重新登录即可），因此抛业务异常 401；
     * 若放任 null 进入范围对象，最终会拼出 {@code reporter_id = null}，数据库静默返回 0 行。
     * </p>
     *
     * @param userId 当前用户 ID
     * @return 原样返回的 userId
     * @throws BusinessException userId 为 null 时抛出，状态码 401
     */
    private static Long requiredUserId(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "登录异常，请重新登录！");
        }
        return userId;
    }

    /**
     * 复制并固化团队集合。
     *
     * <p>
     * 必须复制：直接持有调用方的集合引用，等于允许对方事后 add 一个团队 ID 悄悄扩大可见范围。
     * 复制同时完成去重与不可变化，且返回集合的迭代顺序不保证，不要依赖它。
     * </p>
     *
     * @param teamIds 我管理的团队 ID 集合，不允许为 null；无团队时传空集合
     * @return 去重后的不可变集合
     * @throws IllegalStateException teamIds 为 null 时抛出，属于调用方漏传（程序缺陷，非用户可自愈问题）
     */
    private static Set<Long> copyTeamIds(Collection<Long> teamIds) {
        if (teamIds == null) {
            throw new IllegalStateException("主管工单范围缺少团队集合，调用方必须传空集合而不是 null");
        }
        return Set.copyOf(teamIds);
    }
}
