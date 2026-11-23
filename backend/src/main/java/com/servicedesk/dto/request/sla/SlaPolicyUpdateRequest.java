package com.servicedesk.dto.request.sla;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改 SLA 策略的入参，对应接口文档 5.3。
 *
 * <p>
 * 属于接口入参层。四个字段整体覆盖，都是必填：修改接口不做部分更新，
 * 前端编辑弹窗本就持有完整表单，缺字段说明调用方理解错了契约。
 * </p>
 *
 * <p>
 * 刻意不接收 {@code priority}：优先级既是策略的匹配键，也是唯一索引列，
 * 修改它等于换一条策略，应删旧建新；接口文档 5.3 的入参表也不含该字段。
 * </p>
 */
@Data
public class SlaPolicyUpdateRequest {

  /** 策略名，必填，≤64。 */
  @NotBlank(message = "策略名不能为空")
  @Size(max = 64, message = "策略名不能超过 64 位")
  private String name;

  /** 响应时限（分钟），必填且为正数。 */
  @NotNull(message = "响应时限不能为空")
  @Positive(message = "响应时限必须为正数")
  private Integer responseMinutes;

  /** 解决时限（分钟），必填且为正数，业务上不得小于响应时限。 */
  @NotNull(message = "解决时限不能为空")
  @Positive(message = "解决时限必须为正数")
  private Integer resolveMinutes;

  /** 是否启用，必填。 */
  @NotNull(message = "是否启用不能为空")
  private Boolean enabled;
}
