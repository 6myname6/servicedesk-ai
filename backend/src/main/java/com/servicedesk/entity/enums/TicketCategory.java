package com.servicedesk.entity.enums;

import com.servicedesk.common.exception.BusinessException;

import java.util.Locale;

/**
 * 故障分类（6 类）。
 *
 * <p>
 * 属于枚举字典层。枚举名必须与 {@code ticket.category} 的存储值、ER 图第 6 节字典、
 * 产品文档 7.5 分类体系保持一致；AI 输出与人工修改使用同一套取值。
 * </p>
 */
public enum TicketCategory {

    /** 硬件故障：设备本体、机械或电气部件损坏。 */
    HARDWARE,
    /** 软件故障：程序、配置、驱动、数据异常。 */
    SOFTWARE,
    /** 耗材问题：滤芯、刀头、油品等易耗件不足或失效。 */
    CONSUMABLE,
    /** 安装调试：新设备安装、参数调试、培训。 */
    INSTALL,
    /** 投诉建议：服务态度、响应时效等非技术诉求。 */
    COMPLAINT,
    /** 其他：无法归入以上分类。 */
    OTHER;

    /**
     * 解析分类，缺省为 {@link #OTHER}。
     *
     * <p>
     * 不传或空白按 OTHER 处理（接口文档 2.2）；传了非法值直接 400，
     * 不静默回退，避免把录入错误藏进「其他」。
     * </p>
     *
     * @param raw 请求参数，可为 null 或空白
     * @return 分类枚举，null / 空白返回 OTHER
     * @throws BusinessException 取值不在字典内时抛出 400
     */
    public static TicketCategory fromOrDefault(String raw) {
        return raw == null || raw.isBlank() ? OTHER : from(raw);
    }

    /**
     * 严格解析分类，用于列表筛选。
     *
     * @param raw 请求参数
     * @return 分类枚举
     * @throws BusinessException 取值为空或不在字典内时抛出 400
     */
    public static TicketCategory from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, "故障分类不能为空");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (TicketCategory category : values()) {
            if (category.name().equals(normalized)) {
                return category;
            }
        }
        throw new BusinessException(400, "故障分类不合法: " + raw);
    }
}
