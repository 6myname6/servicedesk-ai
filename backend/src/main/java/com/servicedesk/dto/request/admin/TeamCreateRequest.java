package com.servicedesk.dto.request.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增团队的入参，对应接口文档 9.6。
 *
 * <p>
 * 属于接口入参层，只做格式校验；团队名是否重复、主管是否存在由 Service 判断，
 * 分别返回 409 / 400，避免把唯一索引冲突暴露成 500。
 * </p>
 */
@Data
public class TeamCreateRequest {

  /** 团队名，不超过 64 位，全局唯一。 */
  @NotBlank(message = "团队名不能为空")
  @Size(max = 64, message = "团队名不能超过 64 位")
  private String name;

  /** 主管用户 ID，可为空；不为空时必须存在。 */
  private Long supervisorId;

  /** 团队描述，可为空。 */
  @Size(max = 255, message = "描述不能超过 255 位")
  private String description;
}
