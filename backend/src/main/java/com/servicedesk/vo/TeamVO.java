package com.servicedesk.vo;

import lombok.Builder;
import lombok.Getter;

/**
 * 团队视图对象，对应接口文档附录 A.4。
 *
 * <p>
 * 属于接口出参层。{@code supervisorName} 与 {@code memberCount} 不是 {@code team}
 * 表字段，由 Service 批量回填，禁止前端拿 {@code supervisorId} 再查一次。
 * </p>
 */
@Getter
@Builder
public class TeamVO {

  /** 团队 ID。 */
  private final Long id;

  /** 团队名。 */
  private final String name;

  /** 主管用户 ID，未指定主管时为 null。 */
  private final Long supervisorId;

  /** 主管姓名，未指定主管或主管账号已删除时为 null。 */
  private final String supervisorName;

  /** 成员数，统计 {@code sys_user.team_id} 指向本团队的账号数量（含停用账号）。 */
  private final long memberCount;

  /** 团队描述，供编辑弹窗回显；接口文档 A.4 之外的补充字段。 */
  private final String description;
}
