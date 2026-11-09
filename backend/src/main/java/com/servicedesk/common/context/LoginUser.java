package com.servicedesk.common.context;

/**
 * 登录用户快照，由 JWT 解析得到，存放进 {@link UserContext} 供业务层读取。
 *
 * <p>
 * 不可变对象。只放鉴权与数据范围必需的最小字段，不放密码、手机号等敏感信息，
 * 避免随 token 在网络上长期传递。
 * </p>
 *
 * @param id          用户 ID
 * @param username    登录名
 * @param displayName 显示名
 * @param role        角色：REPORTER / ENGINEER / SUPERVISOR / ADMIN
 * @param teamId      所属团队 ID，管理员或未分配团队时为 null
 */
public record LoginUser(Long id, String username, String displayName, String role, Long teamId) {
}
