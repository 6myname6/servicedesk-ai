package com.servicedesk.vo;

import lombok.Builder;
import lombok.Getter;

/**
 * 客户视图对象，对应接口文档附录 A.5。
 *
 * <p>
 * 属于接口出参层。禁止直接返回实体：{@code created_at}/{@code updated_at}
 * 属于内部审计字段，前端列表不需要，也避免后续加内部字段时随响应外泄。
 * </p>
 */
@Getter
@Builder
public class CustomerVO {

  /** 客户 ID。 */
  private final Long id;

  /** 客户名。 */
  private final String name;

  /** 联系人，可为 null。 */
  private final String contact;

  /** 联系电话，可为 null。 */
  private final String phone;

  /** 地址，可为 null。 */
  private final String address;

  /** 客户等级：KEY / NORMAL。 */
  private final String level;

  /** 备注，可为 null。 */
  private final String remark;
}
