package com.servicedesk.service;

import com.servicedesk.common.api.PageResult;
import com.servicedesk.dto.request.admin.UserCreateRequest;
import com.servicedesk.dto.request.admin.UserQueryRequest;
import com.servicedesk.dto.request.admin.UserUpdateRequest;
import com.servicedesk.vo.UserVO;

/**
 * 用户管理服务，对应接口文档第 9 章 M-01。
 *
 * <p>
 * 属于业务层，只处理用户资料与账号状态；登录、鉴权不在本接口范围内。
 * 角色校验由接口层的 {@code @RequiredRole} 完成（本接口只对管理员开放），
 * 因此本接口内部不再判断角色——避免同一规则在两处实现。
 * </p>
 */
public interface UserAdminService {

  /**
   * 分页查询用户，支持关键字、角色、状态三种过滤。
   *
   * <p>
   * 事务边界：只读，不加事务。keyword 同时匹配登录名与显示名，为空则不过滤。
   * </p>
   *
   * @param query 查询条件，分页参数已带默认值
   * @return 分页结果，records 中不含密码字段
   */
  PageResult<UserVO> page(UserQueryRequest query);

  /**
   * 新增用户，用户名与邮箱均需全局唯一。
   *
   * <p>
   * 事务边界：单表插入，由数据库事务兜底；密码在此处 BCrypt 编码后入库。
   * 幂等性：不幂等，重复调用会在第二次返回 409。
   * </p>
   *
   * @param request 新增入参
   * @return 新建用户的信息，不含密码
   * @throws com.servicedesk.common.exception.BusinessException 用户名重复抛 409；邮箱重复抛 409；
   *                                                           角色非法或团队不存在抛 400
   */
  UserVO create(UserCreateRequest request);

  /**
   * 修改用户资料（姓名、角色、团队、联系方式）。
   *
   * <p>
   * 事务边界：单表更新。登录名与密码不在此修改。
   * 自我保护：不允许把当前登录账号的角色改成非管理员，避免管理员把自己锁在门外。
   * </p>
   *
   * @param id      用户 ID
   * @param request 修改入参
   * @return 修改后的用户信息
   * @throws com.servicedesk.common.exception.BusinessException 用户不存在抛 404；邮箱重复抛 409；
   *                                                           角色非法、团队不存在或自我降级抛 400
   */
  UserVO update(Long id, UserUpdateRequest request);

  /**
   * 启用或停用用户。
   *
   * <p>
   * 事务边界：单表更新 + 一次 Redis 标记写入，两者<b>不共享事务</b>；Redis 失败不会回滚数据库。
   * 自我保护：不允许停用当前登录账号，否则管理员会把自己踢出系统且无法自助恢复。
   * </p>
   *
   * <p>
   * 即时失效：停用时写入 Redis 标记（TTL 取 token 最长存活时间），鉴权拦截器在每个请求上校验该标记，
   * 因此已签发的 token 在下一个请求就会被拒绝；启用时删除标记，同一 token 立即恢复可用。
   * Redis 写入失败返回 503 并保留数据库变更，操作幂等，管理员重试即可补齐。
   * </p>
   *
   * @param id     用户 ID
   * @param status 目标状态：1 启用 / 0 停用
   * @throws com.servicedesk.common.exception.BusinessException 用户不存在抛 404；停用当前登录账号抛 409；
   *                                                           Redis 不可用导致标记同步失败抛 503
   */
  void updateStatus(Long id, Integer status);
}
