package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.servicedesk.common.api.PageResult;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.dto.request.admin.UserCreateRequest;
import com.servicedesk.dto.request.admin.UserQueryRequest;
import com.servicedesk.dto.request.admin.UserUpdateRequest;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Team;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
import com.servicedesk.security.UserStatusCache;
import com.servicedesk.service.UserAdminService;
import com.servicedesk.vo.UserVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户管理服务实现。
 *
 * <p>
 * 属于业务实现层。三条约定：
 * </p>
 *
 * <ul>
 * <li>密码只在写入前出现，落库即 BCrypt 编码，响应与日志都不出现明文或哈希；</li>
 * <li>登录名与邮箱的唯一性在写入前显式检查，返回 409 而不是让数据库唯一索引抛 500；</li>
 * <li>「不许把自己锁在门外」：不允许停用自己、不允许把自己降级为非管理员。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminServiceImpl implements UserAdminService {

  /** 启用状态取值，对应 {@code sys_user.status}。 */
  private static final int STATUS_ENABLED = 1;

  /** 停用状态取值，对应 {@code sys_user.status}。 */
  private static final int STATUS_DISABLED = 0;

  /** 用户账号 Mapper。 */
  private final SysUserMapper sysUserMapper;

  /** 团队 Mapper，用于校验团队存在性与回填团队名。 */
  private final TeamMapper teamMapper;

  /** 客户 Mapper，用于校验客户存在性与回填客户名；与团队互斥，最多用到一个。 */
  private final CustomerMapper customerMapper;

  /** 密码编码器，与登录校验共用同一套 BCrypt 配置。 */
  private final PasswordEncoder passwordEncoder;

  /** 账号停用标记，用于让「停用」在下一个请求就生效，而不必等 token 过期。 */
  private final UserStatusCache userStatusCache;

  /**
   * 分页查询用户。
   *
   * <p>
   * 团队名按当前页的团队 ID 批量查询后回填，避免逐条查询造成 N+1。
   * </p>
   *
   * @param query 查询条件
   * @return 分页结果
   */
  @Override
  public PageResult<UserVO> page(UserQueryRequest query) {
    LambdaQueryWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaQuery()
        .eq(isNotBlank(query.getRole()), SysUser::getRole, normalizeRoleFilter(query.getRole()))
        .eq(query.getStatus() != null, SysUser::getStatus, query.getStatus())
        .orderByDesc(SysUser::getId);

    String keyword = trimToNull(query.getKeyword());
    if (keyword != null) {
      // 关键字条件是「或」关系，必须整体括起来，否则会把上面的 role/status 条件一起或掉
      wrapper.and(w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getDisplayName, keyword));
    }

    IPage<SysUser> page = sysUserMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
    Map<Long, String> teamNames = loadTeamNames(page.getRecords());
    Map<Long, String> customerNames = loadCustomerNames(page.getRecords());

    return PageResult.from(page, user -> toVO(user, nameOf(teamNames, user.getTeamId()),
        nameOf(customerNames, user.getCustomerId())));
  }

  /**
   * 新增用户。
   *
   * @param request 新增入参
   * @return 新建用户信息
   */
  @Override
  public UserVO create(UserCreateRequest request) {
    String username = request.getUsername().trim();
    if (existsByUsername(username, null)) {
      log.warn("新增用户失败：用户名已存在, username={}, operatorId={}", username, UserContext.getUserId());
      throw new BusinessException(409, "用户名已存在");
    }

    String email = trimToNull(request.getEmail());
    if (email != null && existsByEmail(email, null)) {
      log.warn("新增用户失败：邮箱已存在, email={}, operatorId={}", email, UserContext.getUserId());
      throw new BusinessException(409, "邮箱已被注册");
    }

    Role role = parseRole(request.getRole());
    Long teamId = request.getTeamId();
    Long customerId = request.getCustomerId();
    requireExclusiveOrg(teamId, customerId);
    String teamName = requireTeam(teamId);
    String customerName = requireCustomer(customerId);

    SysUser user = new SysUser();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
    user.setDisplayName(request.getDisplayName().trim());
    user.setRole(role.name());
    user.setTeamId(teamId);
    user.setCustomerId(customerId);
    user.setPhone(trimToNull(request.getPhone()));
    user.setEmail(email);
    user.setStatus(STATUS_ENABLED);
    sysUserMapper.insert(user);

    log.info("新增用户成功, userId={}, username={}, role={}, teamId={}, customerId={}, operatorId={}",
        user.getId(), username, role, teamId, customerId, UserContext.getUserId());
    return toVO(user, teamName, customerName);
  }

  /**
   * 修改用户资料。
   *
   * <p>
   * 事务边界：主更新与「清空另一侧归属」的补充更新在同一事务内，
   * 否则会出现团队已清空、客户没写进去的半成品账号。
   * </p>
   *
   * @param id      用户 ID
   * @param request 修改入参
   * @return 修改后的用户信息
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public UserVO update(Long id, UserUpdateRequest request) {
    SysUser user = requireUser(id);

    String email = trimToNull(request.getEmail());
    if (email != null && existsByEmail(email, id)) {
      log.warn("修改用户失败：邮箱已存在, userId={}, email={}, operatorId={}", id, email, UserContext.getUserId());
      throw new BusinessException(409, "邮箱已被注册");
    }

    Role role = parseRole(request.getRole());
    Long operatorId = UserContext.getUserId();
    if (id.equals(operatorId) && role != Role.ADMIN) {
      log.warn("修改用户被拒绝：不能降低自己的角色, userId={}, targetRole={}", id, role);
      throw new BusinessException(400, "不能修改自己的角色");
    }

    Long teamId = request.getTeamId();
    Long customerId = request.getCustomerId();
    requireExclusiveOrg(teamId, customerId);
    String teamName = requireTeam(teamId);
    String customerName = requireCustomer(customerId);

    String beforeRole = user.getRole();
    user.setDisplayName(request.getDisplayName().trim());
    user.setRole(role.name());
    user.setTeamId(teamId);
    user.setCustomerId(customerId);
    user.setPhone(trimToNull(request.getPhone()));
    user.setEmail(email);
    sysUserMapper.updateById(user);
    // updateById 跳过 null：从「团队」切到「客户」（或反向）时必须显式清空另一列，
    // 否则两个归属会同时留在库里，违反互斥规则（编码规范 4.2）
    if (teamId == null || customerId == null) {
      sysUserMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
          .eq(SysUser::getId, id)
          .set(teamId == null, SysUser::getTeamId, null)
          .set(customerId == null, SysUser::getCustomerId, null));
    }

    log.info("修改用户成功, userId={}, role={} -> {}, teamId={}, customerId={}, operatorId={}",
        id, beforeRole, role, teamId, customerId, operatorId);
    return toVO(user, teamName, customerName);
  }

  /**
   * 启用或停用用户，并同步维护 Redis 停用标记。
   *
   * <p>
   * 先改数据库、再改标记：顺序反过来时若数据库更新失败，用户会被踢下线却仍处于启用状态。
   * 标记同步失败不回滚数据库，改为返回 503 让管理员重试——该操作幂等，重试即可补齐。
   * </p>
   *
   * @param id     用户 ID
   * @param status 目标状态：1 启用 / 0 停用
   * @throws BusinessException 用户不存在抛 404；停用当前登录账号抛 409；标记同步失败抛 503
   */
  @Override
  public void updateStatus(Long id, Integer status) {
    SysUser user = requireUser(id);
    Long currentId = UserContext.getUserId();

    if (id.equals(currentId) && status == STATUS_DISABLED) {
      log.warn("停用用户被拒绝：不能停用当前登录账号, userId={}", id);
      throw new BusinessException(409, "不能停用当前登录账号");
    }

    String beforeStatus = String.valueOf(user.getStatus());

    user.setStatus(status);
    sysUserMapper.updateById(user);
    try {
      // 停用账号，停用后，账户立即失效
      if (status == STATUS_DISABLED) {
        if (userStatusCache.isDisabled(id)) {
          log.warn("id{}已是停用账号，请勿重复操作", id);
          return;
        }
        userStatusCache.markDisabled(id);
        log.info("账号{}停用", id);
      } else {// 启用账号
        if (!userStatusCache.isDisabled(id)) {
          log.warn("id{}已是启用账号，请勿重复操作", id);
          return;
        }
        userStatusCache.clearDisabled(id);
        log.info("账号{}启用", id);
      }
    } catch (DataAccessException e) {
      log.error("用户状态标记同步失败，userId={}，status={}", id, status, e);
      throw new BusinessException(503, "账号状态已更新，但即时失效未生效，请稍后重试");
    }

    log.info("用户状态变更, userId={}, status={} -> {}, operatorId={}",
        id, beforeStatus, status, currentId);
  }

  /**
   * 按 ID 查询用户，不存在时抛 404。
   *
   * @param id 用户 ID
   * @return 用户实体
   * @throws BusinessException 用户不存在时抛出 404
   */
  private SysUser requireUser(Long id) {
    SysUser user = sysUserMapper.selectById(id);
    if (user == null) {
      log.warn("用户不存在, userId={}, operatorId={}", id, UserContext.getUserId());
      throw new BusinessException(404, "用户不存在");
    }
    return user;
  }

  /**
   * 解析角色取值，非法时返回 400。
   *
   * <p>
   * {@link Role#from(String)} 为了登录场景返回 401，管理接口里应视为参数错勿，
   * 因此在这里做一次转换，避免把「填错角色」提示成「请重新登录」。
   * </p>
   *
   * @param rawRole 原始角色字符串
   * @return 解析后的角色
   * @throws BusinessException 角色取值非法时抛出 400
   */
  private Role parseRole(String rawRole) {
    try {
      return Role.from(rawRole);
    } catch (BusinessException e) {
      log.warn("角色取值非法, rawRole={}, operatorId={}", rawRole, UserContext.getUserId());
      throw new BusinessException(400, "角色取值非法");
    }
  }

  /**
   * 校验团队存在并返回团队名。
   *
   * @param teamId 团队 ID，可为 null
   * @return 团队名；teamId 为 null 时返回 null
   * @throws BusinessException 团队不存在时抛出 400
   */
  private String requireTeam(Long teamId) {
    if (teamId == null) {
      return null;
    }
    Team team = teamMapper.selectById(teamId);
    if (team == null) {
      log.warn("团队不存在, teamId={}, operatorId={}", teamId, UserContext.getUserId());
      throw new BusinessException(400, "团队不存在");
    }
    return team.getName();
  }

  /**
   * 校验团队与客户归属互斥。
   *
   * <p>
   * 一个账号要么是内部员工（挂团队），要么是客户联系人（挂客户），
   * 同时有值会让「所属团队 / 所属客户」的展示与数据范围判断都失去唯一答案，
   * 因此在写库前就拦住，返回 400 而不是靠数据库约束。
   * </p>
   *
   * @param teamId     团队 ID，可为 null
   * @param customerId 客户 ID，可为 null
   * @throws BusinessException 两个都非空时抛出 400
   */
  private void requireExclusiveOrg(Long teamId, Long customerId) {
    if (teamId != null && customerId != null) {
      log.warn("用户归属校验失败：团队与客户只能二选一, teamId={}, customerId={}, operatorId={}",
          teamId, customerId, UserContext.getUserId());
      throw new BusinessException(400, "所属团队与所属客户只能二选一");
    }
  }

  /**
   * 校验客户存在并返回客户名。
   *
   * @param customerId 客户 ID，可为 null
   * @return 客户名；customerId 为 null 时返回 null
   * @throws BusinessException 客户不存在时抛出 400
   */
  private String requireCustomer(Long customerId) {
    if (customerId == null) {
      return null;
    }
    Customer customer = customerMapper.selectById(customerId);
    if (customer == null) {
      log.warn("客户不存在, customerId={}, operatorId={}", customerId, UserContext.getUserId());
      throw new BusinessException(400, "客户不存在");
    }
    return customer.getName();
  }

  /**
   * 判断用户名是否已被占用。
   *
   * @param username  登录名
   * @param excludeId 需要排除的用户 ID（修改时排除自己），新增时传 null
   * @return true 表示已存在
   */
  private boolean existsByUsername(String username, Long excludeId) {
    return sysUserMapper.exists(Wrappers.<SysUser>lambdaQuery()
        .eq(SysUser::getUsername, username)
        .ne(excludeId != null, SysUser::getId, excludeId));
  }

  /**
   * 判断邮箱是否已被占用。
   *
   * @param email     邮箱
   * @param excludeId 需要排除的用户 ID，新增时传 null
   * @return true 表示已存在
   */
  private boolean existsByEmail(String email, Long excludeId) {
    return sysUserMapper.exists(Wrappers.<SysUser>lambdaQuery()
        .eq(SysUser::getEmail, email)
        .ne(excludeId != null, SysUser::getId, excludeId));
  }

  /**
   * 批量加载团队名。
   *
   * @param users 当前页用户
   * @return 团队 ID 到团队名的映射，无团队的用户不包含在内
   */
  private Map<Long, String> loadTeamNames(List<SysUser> users) {
    Set<Long> teamIds = users.stream()
        .map(SysUser::getTeamId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    if (teamIds.isEmpty()) {
      return Map.of();
    }
    Collection<Team> teams = teamMapper.selectByIds(teamIds);
    return teams.stream().collect(Collectors.toMap(Team::getId, Team::getName));
  }

  /**
   * 批量加载客户名。
   *
   * @param users 当前页用户
   * @return 客户 ID 到客户名的映射，未关联客户的用户不包含在内
   */
  private Map<Long, String> loadCustomerNames(List<SysUser> users) {
    Set<Long> customerIds = users.stream()
        .map(SysUser::getCustomerId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    if (customerIds.isEmpty()) {
      return Map.of();
    }
    Collection<Customer> customers = customerMapper.selectByIds(customerIds);
    return customers.stream().collect(Collectors.toMap(Customer::getId, Customer::getName));
  }

  /**
   * 从「ID → 名称」映射取名称，ID 为 null 时直接返回 null。
   *
   * <p>
   * 不能直接 {@code map.get(null)}：空映射（{@code Map.of()}）对 null 键会抛 NPE，
   * 而团队与客户互斥意味着至少有一列永远为 null，这个分支是常态而不是异常。
   * </p>
   *
   * @param names ID 到名称的映射
   * @param id    关联 ID，可为 null
   * @return 名称；ID 为 null 或映射中没有该 ID 时返回 null
   */
  private String nameOf(Map<Long, String> names, Long id) {
    return id == null ? null : names.get(id);
  }

  /**
   * 实体转视图对象。
   *
   * @param user         用户实体
   * @param teamName     团队名，可为 null
   * @param customerName 客户名，可为 null
   * @return 不含密码字段的用户视图
   */
  private UserVO toVO(SysUser user, String teamName, String customerName) {
    return UserVO.builder()
        .id(user.getId())
        .username(user.getUsername())
        .displayName(user.getDisplayName())
        .role(user.getRole())
        .teamId(user.getTeamId())
        .teamName(teamName)
        .customerId(user.getCustomerId())
        .customerName(customerName)
        .phone(user.getPhone())
        .email(user.getEmail())
        .status(user.getStatus())
        .build();
  }

  /**
   * 归一化角色过滤条件，保证大小写与存储值一致。
   *
   * <p>
   * 过滤条件非法时不做报错，直接按原样匹配（查不到数据），
   * 因为「筛选条件写错」属于查询无效，不应该让整个列表接口 400。
   * </p>
   *
   * @param rawRole 原始角色过滤值
   * @return 供 SQL 匹配的角色字符串
   */
  private String normalizeRoleFilter(String rawRole) {
    return rawRole == null ? null : rawRole.trim().toUpperCase(Locale.ROOT);
  }

  /**
   * 判断字符串非空白。
   *
   * @param value 待判断字符串
   * @return true 表示存在非空白内容
   */
  private boolean isNotBlank(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * 去掉首尾空白，空串归一为 null。
   *
   * @param value 原始字符串
   * @return 去空白后的值，空白时返回 null
   */
  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
