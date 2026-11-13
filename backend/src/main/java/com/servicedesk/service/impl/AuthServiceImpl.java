package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.dto.request.auth.LoginRequest;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Team;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
import com.servicedesk.security.JwtUtil;
import com.servicedesk.security.TokenBlackList;
import com.servicedesk.service.AuthService;
import com.servicedesk.vo.LoginVO;
import com.servicedesk.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现。
 *
 * <p>
 * 属于业务实现层。三条安全约定：
 * </p>
 * <ul>
 *   <li>用户不存在与密码错误返回同一提示，避免用户名枚举；</li>
 *   <li>停用账号在登录阶段就拦住，不让其拿到新 token；</li>
 *   <li>当前用户信息以数据库为准，防止 token 载荷中的角色/团队与最新数据不一致。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

  /** 启用状态的取值，对应 sys_user.status。 */
  private static final int STATUS_ENABLED = 1;

  /** 用户账号 Mapper。 */
  private final SysUserMapper sysUserMapper;

  /** 团队 Mapper，仅用于回填 teamName。 */
  private final TeamMapper teamMapper;

  /** JWT 工具。 */
  private final JwtUtil jwtUtil;

  /** 登出黑名单。 */
  private final TokenBlackList tokenBlackList;

  /** 密码编码器。 */
  private final PasswordEncoder passwordEncoder;

  /**
   * 用户名密码登录。
   *
   * @param request 登录请求
   * @return token、有效期与用户信息
   * @throws BusinessException 用户名不存在或密码错误抛 401；账号停用抛 403
   */
  @Override
  public LoginVO login(LoginRequest request) {
    SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
        .eq(SysUser::getUsername, request.getUsername()));

    if (user == null) {
      log.warn("登录失败：账号不存在, username={}", request.getUsername());
      throw new BusinessException(401, "用户名或密码错误");
    }
    if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
      log.warn("登录失败：密码错误, userId={}, username={}", user.getId(), user.getUsername());
      throw new BusinessException(401, "用户名或密码错误");
    }
    if (user.getStatus() == null || user.getStatus() != STATUS_ENABLED) {
      log.warn("登录被拒绝：账号已停用, userId={}, username={}", user.getId(), user.getUsername());
      throw new BusinessException(403, "账号已停用，请联系管理员");
    }

    LoginUser loginUser = new LoginUser(
        user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(), user.getTeamId());
    String token = jwtUtil.generate(loginUser);
    log.info("登录成功, userId={}, username={}, role={}", user.getId(), user.getUsername(), user.getRole());

    return LoginVO.builder()
        .token(token)
        .expiresIn(jwtUtil.getExpireSeconds())
        .user(toUserVO(user))
        .build();
  }

  /**
   * 登出，把 token 加入黑名单，TTL 为 token 剩余有效期。
   *
   * @param token 当前请求携带的 JWT
   */
  @Override
  public void logout(String token) {
    tokenBlackList.addBlackList(token);
    log.info("用户登出, userId={}", UserContext.getUserId());
  }

  /**
   * 查询当前登录用户。
   *
   * @return 最新用户信息
   * @throws BusinessException 未登录抛 401；账号已被删除抛 401
   */
  @Override
  public UserVO currentUser() {
    Long userId = UserContext.getUserId();
    SysUser user = sysUserMapper.selectById(userId);
    if (user == null) {
      log.warn("当前用户查询失败：账号已被删除, userId={}", userId);
      throw new BusinessException(401, "登录已失效，请重新登录");
    }
    return toUserVO(user);
  }

  /**
   * 实体转视图对象，并回填团队名。
   *
   * @param user 用户实体
   * @return 不含密码字段的用户视图
   */
  private UserVO toUserVO(SysUser user) {
    String teamName = null;
    if (user.getTeamId() != null) {
      Team team = teamMapper.selectById(user.getTeamId());
      teamName = team == null ? null : team.getName();
    }
    return UserVO.builder()
        .id(user.getId())
        .username(user.getUsername())
        .displayName(user.getDisplayName())
        .role(user.getRole())
        .teamId(user.getTeamId())
        .teamName(teamName)
        .phone(user.getPhone())
        .email(user.getEmail())
        .status(user.getStatus())
        .build();
  }
}
