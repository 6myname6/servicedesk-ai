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
import com.servicedesk.security.LoginRateLimiter;
import com.servicedesk.security.TokenBlackList;
import com.servicedesk.service.AuthService;
import com.servicedesk.vo.LoginVO;
import com.servicedesk.vo.UserVO;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现。
 *
 * <p>
 * 属于业务实现层。三条安全约定：
 * </p>
 * <ul>
 * <li>用户不存在与密码错误返回同一提示，避免用户名枚举；</li>
 * <li>停用账号在登录阶段就拦住，不让其拿到新 token；</li>
 * <li>当前用户信息以数据库为准，防止 token 载荷中的角色/团队与最新数据不一致。</li>
 * </ul>
 */
@Slf4j
@Service
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
    /** 登录频控器，按账号与客户端 IP 两个维度限制失败次数。 */
    private final LoginRateLimiter loginRateLimiter;

    /**
     * 构造认证服务。
     *
     * @param sysUserMapper     用户账号 Mapper
     * @param teamMapper        团队 Mapper
     * @param jwtUtil           JWT 工具
     * @param tokenBlackList    登出黑名单
     * @param passwordEncoder   密码编码器
     * @param loginRateLimiter  登录频控器
     */
    public AuthServiceImpl(SysUserMapper sysUserMapper,
                           TeamMapper teamMapper,
                           JwtUtil jwtUtil,
                           TokenBlackList tokenBlackList,
                           PasswordEncoder passwordEncoder,
                           LoginRateLimiter loginRateLimiter) {
        this.sysUserMapper = sysUserMapper;
        this.teamMapper = teamMapper;
        this.jwtUtil = jwtUtil;
        this.tokenBlackList = tokenBlackList;
        this.passwordEncoder = passwordEncoder;
        this.loginRateLimiter = loginRateLimiter;
    }

    /**
     * 用户名密码登录。
     *
     * <p>
     * 执行顺序：账号锁定检查 → IP 锁定检查 → 查库 → 校验密码 → 校验状态 → 签发 token。
     * 两次锁定检查都放在查库之前，被限流的请求不消耗数据库连接。
     * </p>
     *
     * @param request  登录请求
     * @param clientIp 客户端 IP，用于 IP 维度失败限流
     * @return token、有效期与用户信息
     * @throws BusinessException 用户名不存在或密码错误抛 401；账号停用抛 403；
     *                           账号或 IP 失败次数过多抛 429；频控依赖不可用抛 503
     */
    @Override
    public LoginVO login(LoginRequest request, String clientIp) {
        String username = request.getUsername();
        try {
            loginRateLimiter.checkUserLocked(username);
            loginRateLimiter.checkIpLocked(clientIp);
        } catch (BusinessException e) {
            throw e;
        } catch (DataAccessException e) {
            log.error("登录频控不可用, username={}, ip={}", username, clientIp, e);
            throw new BusinessException(503, "服务暂时不可用，请稍后重试");
        }

        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username));

        if (user == null) {
            log.warn("登录失败：用户名或密码错误, username={}, ip={}", username, clientIp);
            recordFailureQuietly(username, clientIp);
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("登录失败：密码错误, userId={}, username={}, ip={}", user.getId(), username, clientIp);
            recordFailureQuietly(username, clientIp);
            throw new BusinessException(401, "用户名或密码错误");
        }

        // 检查账号状态
        if (user.getStatus() == null || user.getStatus() != STATUS_ENABLED) {
            log.warn("登录被拒绝：账号已停用, userId={}, username={}", user.getId(), user.getUsername());
            throw new BusinessException(403, "账号已停用，请联系管理员");
        }
        LoginUser loginUser = new LoginUser(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(), user.getTeamId());

        loginRateLimiter.clearOnSuccess(user.getUsername());

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
     * 记录一次登录失败，Redis 不可用时只告警不阻断。
     *
     * <p>
     * 记账不是登录的必要条件：若让 Redis 异常向上冒泡，用户明明只是输错密码却会收到
     * 500「服务端错误」，既误导用户也让排查时的日志与事实不符。
     * 因此安全判定 {@code checkUserLocked} 采取 fail-closed（503 拒绝），
     * 记账则采取 fail-open（放行 + 告警）。
     * </p>
     *
     * @param username 登录名，允许是不存在的账号
     * @param clientIp 客户端 IP
     */
    private void recordFailureQuietly(String username, String clientIp) {
        try {
            loginRateLimiter.recordFailure(username, clientIp);
        } catch (DataAccessException e) {
            log.warn("记录登录失败次数失败，降级放行, username={}, ip={}", username, clientIp, e);
        }
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
