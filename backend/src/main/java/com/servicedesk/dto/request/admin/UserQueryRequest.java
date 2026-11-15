package com.servicedesk.dto.request.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 用户管理列表的查询条件，对应接口文档 9.1。
 *
 * <p>
 * 属于接口入参层，通过 queryString 绑定。三个过滤条件互相独立，为空即不过滤；
 * 分页参数带默认值，避免前端不传时取到 0 条数据。
 * </p>
 */
@Data
public class UserQueryRequest {

  /** 关键字，模糊匹配登录名或显示名，可为空。 */
  private String keyword;

  /** 角色过滤，取值见 {@code com.servicedesk.entity.enums.Role}，为空表示不过滤。 */
  private String role;

  /** 状态过滤：1 启用 / 0 停用，为空表示不过滤。 */
  private Integer status;

  /** 页码，从 1 开始，默认 1。 */
  @Min(value = 1, message = "页码不能小于 1")
  private Integer page = 1;

  /**
   * 每页条数，默认 10。
   *
   * <p>
   * 上限与分页插件的 {@code maxLimit} 保持一致，超出时直接返回 400，
   * 而不是由插件静默截断，避免前端以为「请求了 500 条却只拿到 100 条」。
   * </p>
   */
  @Min(value = 1, message = "每页条数不能小于 1")
  @Max(value = 100, message = "每页条数不能超过 100")
  private Integer size = 10;
}
