package com.servicedesk.dto.request.sla;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 SLA 策略的入参，对应接口文档 5.2。
 *
 * <p>
 * 属于接口入参层，只做格式校验；优先级取值是否在字典内、优先级是否已被占用、
 * 解决时限是否小于响应时限，均由 Service 判断并返回 400 / 409。
 * </p>
 *
 * <p>
 * {@code enabled} 为包装类型：不传时由 Service 按接口文档的默认值 true 处理，
 * 不能声明成 {@code boolean}，否则缺省会被静默当成 false。
 * </p>
 */
@Data
public class SlaPolicyCreateRequest {

  /** 策略名，必填，≤64。 */
  @NotBlank(message = "策略名不能为空")
  @Size(max = 64, message = "策略名不能超过 64 位")
  private String name;

  /** 适用紧急度，必填，取值 P0 / P1 / P2 / P3。 */
  @NotBlank(message = "优先级不能为空")
  @Size(max = 8, message = "优先级不能超过 8 位")
  private String priority;

  /** 响应时限（分钟），必填且为正数。 */
  @NotNull(message = "响应时限不能为空")
  @Positive(message = "响应时限必须为正数")
  private Integer responseMinutes;

  /** 解决时限（分钟），必填且为正数，业务上不得小于响应时限。 */
  @NotNull(message = "解决时限不能为空")
  @Positive(message = "解决时限必须为正数")
  private Integer resolveMinutes;

  /** 是否启用，可空，缺省 true。 */
  private Boolean enabled;
}
