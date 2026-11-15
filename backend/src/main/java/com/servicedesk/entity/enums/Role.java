package com.servicedesk.entity.enums;

import java.util.Locale;

import com.servicedesk.common.exception.BusinessException;

import lombok.extern.slf4j.Slf4j;

/**
 * 系统角色（4 种）。
 *
 * <p>
 * 属于枚举字典层……
 * <p>
 * 关键约束：枚举名必须与 sys_user.role 存储值、JWT 载荷保持一致。
 */
@Slf4j
public enum Role {
    /** 报修人：只能看到自己提交的工单 */
    REPORTER,
    /** 工程师：只能看到指派给自己的工单 */
    ENGINEER,
    /** 主管：可见本团队工单 */
    SUPERVISOR,
    /** 管理员：拥有所有权限 */
    ADMIN;

    /**
     * 将字符串转换为 Role 枚举值。
     * 
     * @param raw 字符串
     * @return Role 枚举值
     * @throws BusinessException 如果字符串不合法，则抛出业务异常
     */
    public static Role from(String raw) {
        // 1.空值保护
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException(401, "登录异常，请重新登录！");
        }
        // 2. normalize(trim+Locale.Root)
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        // 3.枚举匹配
        try {
            return Role.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            log.error("非法角色值，raw={}", raw, e);
            throw new BusinessException(401, "登录异常，请重新登录！");
        }
    }
}
