package com.servicedesk.entity.enums;

import com.servicedesk.common.exception.BusinessException;

import java.util.Locale;

/**
 * 紧急度（4 档），同时是 SLA 策略的匹配键。
 *
 * <p>
 * 属于枚举字典层。枚举名必须与 {@code ticket.urgency}、{@code sla_policy.priority}
 * 的存储值一致，建单时按枚举名匹配一条启用策略。
 * </p>
 */
public enum Urgency {

    /** P0：停机、安全、多人受影响，响应 15 分钟 / 解决 2 小时。 */
    P0,
    /** P1：主要功能不可用，响应 30 分钟 / 解决 4 小时。 */
    P1,
    /** P2：一般故障，响应 2 小时 / 解决 8 小时；缺省值。 */
    P2,
    /** P3：咨询、优化建议，响应 8 小时 / 解决 24 小时。 */
    P3;

    /**
     * 解析紧急度，缺省为 {@link #P2}。
     *
     * @param raw 请求参数，可为 null 或空白
     * @return 紧急度枚举，null / 空白返回 P2
     * @throws BusinessException 取值不在字典内时抛出 400
     */
    public static Urgency fromOrDefault(String raw) {
        return raw == null || raw.isBlank() ? P2 : from(raw);
    }

    /**
     * 严格解析紧急度，用于列表筛选。
     *
     * @param raw 请求参数
     * @return 紧急度枚举
     * @throws BusinessException 取值为空或不在字典内时抛出 400
     */
    public static Urgency from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, "紧急度不能为空");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (Urgency urgency : values()) {
            if (urgency.name().equals(normalized)) {
                return urgency;
            }
        }
        throw new BusinessException(400, "紧急度不合法: " + raw);
    }
}
