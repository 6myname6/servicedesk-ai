package com.servicedesk.dto.request.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改团队的入参，对应接口文档 9.7。
 *
 * <p>
 * 属于接口入参层。字段与新增一致，但语义是「整体覆盖」：未传的可选字段
 * （主管、描述）会被清空，而不是保持原值，避免前端漏传导致界面与库不一致。
 * </p>
 */
@Data
public class TeamUpdateRequest {

  /** 团队名，不超过 64 位；除自身外不得与其他团队重名。 */
  @NotBlank(message = "团队名不能为空")
  @Size(max = 64, message = "团队名不能超过 64 位")
  private String name;

  /** 主管用户 ID，可为空表示不指定主管；不为空时必须存在。 */
  private Long supervisorId;

  /** 团队描述，可为空表示清空。 */
  @Size(max = 255, message = "描述不能超过 255 位")
  private String description;
}
