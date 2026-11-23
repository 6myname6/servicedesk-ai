package com.servicedesk.vo;

import lombok.Builder;
import lombok.Getter;

/**
 * 用户视图对象，对应接口文档附录 A.3。
 *
 * <p>
 * 属于接口出参层。禁止把 {@code passwordHash} 放进本类，
 * 也禁止直接返回实体，避免密码哈希随响应外泄。
 * </p>
 */
@Getter
@Builder
public class UserVO {

  /** 用户 ID。 */
  private final Long id;

  /** 登录名。 */
  private final String username;

  /** 显示名。 */
  private final String displayName;

  /** 角色：REPORTER / ENGINEER / SUPERVISOR / ADMIN。 */
  private final String role;

  /** 团队 ID，管理员或未分配团队时为 null。 */
  private final Long teamId;

  /** 团队名，未分配团队时为 null。 */
  private final String teamName;

  /** 所属客户 ID，客户联系人账号才有值；与团队 ID 互斥。 */
  private final Long customerId;

  /** 所属客户名，未关联客户时为 null。 */
  private final String customerName;

  /** 手机号。 */
  private final String phone;

  /** 邮箱。 */
  private final String email;

  /** 状态：1 启用 / 0 停用。 */
  private final Integer status;
}
