package com.servicedesk.entity.enums;

import com.servicedesk.common.exception.BusinessException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 工单状态（8 种），对应开发文档 12.1 与 ER 图第 6 节。
 *
 * <p>
 * 属于枚举字典层。枚举名必须与 {@code ticket.status} 的存储值一致，禁止使用魔法数字。
 * 建单只写 {@link #NEW}；其余状态由派单（F-01）与状态流转（F-02）维护。
 * </p>
 *
 * <p>
 * 终态 3 个：{@link #CLOSED}（已完成）、{@link #REJECTED}（不通过）、{@link #CANCELLED}（已取消），
 * 不可再迁出。RC-001 已删除「等客户」与「重开」两个状态，客户补充信息改用私信沟通。
 * </p>
 */
public enum TicketStatus {

    /** 新建：报修人已提交，尚未派单。 */
    NEW,
    /** 已派单：主管指定了处理工程师。 */
    ASSIGNED,
    /** 已接单：工程师确认接单。 */
    ACCEPTED,
    /** 处理中：工程师已开始处理。 */
    IN_PROGRESS,
    /** 已解决：工程师给出解决方案，等待验收。 */
    RESOLVED,
    /** 已完成：报修人验收通过，终态。 */
    CLOSED,
    /** 不通过：报修人验收不通过，终态；不写 {@code closed_at}，问题复现需新建工单。 */
    REJECTED,
    /** 已取消：报修人撤回或无效工单，终态。 */
    CANCELLED;

    /**
     * 终态名称列表，供 SQL 条件（{@code NOT IN}）使用。
     *
     * <p>
     * 由 {@link #isTerminal()} 派生，避免「内存判定」与「SQL 名单」两处各自维护：
     * 新增或调整终态时只改 {@link #isTerminal()} 一处。仅用于拼查询条件，
     * 入参校验必须走 {@link #from(String)}，不能拿本列表当字典。
     * </p>
     */
    public static final List<String> TERMINAL_NAMES = Arrays.stream(values())
            .filter(TicketStatus::isTerminal)
            .map(Enum::name)
            .toList();

    /**
     * 严格解析状态，用于列表筛选与状态迁转入参。
     *
     * <p>
     * 与建单不同，筛选没有「缺省值」语义：传了就必须是合法状态，
     * 否则返回 400，避免把拼错的状态解释成「全部」。
     * </p>
     *
     * @param raw 请求参数
     * @return 状态枚举
     * @throws BusinessException 取值不在字典内时抛出 400
     */
    public static TicketStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, "工单状态不能为空");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (TicketStatus status : values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        throw new BusinessException(400, "工单状态不合法: " + raw);
    }

    /**
     * 判断是否为终态（{@code CLOSED} / {@code REJECTED} / {@code CANCELLED}）。
     *
     * <p>
     * 终态的业务语义是「SLA 停止计时」：详情、列表与超时升级扫描三处共用这一判断。
     * 各自散写三个状态名，新增状态时必然漏改一处，出现「已关闭工单仍在告警」。
     * </p>
     *
     * <p>
     * 注意与「是否已完成验收」区分：评价只允许 {@code CLOSED}，不能用本方法替代
     * {@code status == CLOSED}，否则 {@code REJECTED} / {@code CANCELLED} 会被误放行。
     * </p>
     *
     * @return 终态返回 true，流转中的状态返回 false
     */
    public boolean isTerminal() {
        return this == CLOSED || this == REJECTED || this == CANCELLED;
    }
}
