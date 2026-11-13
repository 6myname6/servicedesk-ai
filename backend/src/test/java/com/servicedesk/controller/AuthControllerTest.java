package com.servicedesk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Team;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
import com.servicedesk.security.TokenBlackList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录、登出、当前用户接口的集成测试。
 *
 * <p>
 * 属于接口层测试，启动完整上下文并走真实拦截器与 MySQL；Redis 用本地容器，
 * 测试产生的黑名单键在 {@link #tearDown()} 中按 token 精确删除，避免污染开发环境。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

  /** 测试账号的明文密码，长度满足 8-32 位校验。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 黑名单键前缀，与 TokenBlackList 保持一致。 */
  private static final String BLACKLIST_PREFIX = "auth:blacklist:";

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** JSON 解析器，用于从登录响应中取 token。 */
  @Autowired
  private ObjectMapper objectMapper;

  /** 用户 Mapper，用于造数。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** 团队 Mapper，用于造数。 */
  @Autowired
  private TeamMapper teamMapper;

  /** 密码编码器，造数时生成 BCrypt 哈希。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** Redis 模板，仅用于清理测试产生的黑名单键。 */
  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  /** 本次测试签发的 token，结束后逐个清理黑名单键。 */
  private final List<String> issuedTokens = new ArrayList<>();

  /** 可用账号。 */
  private SysUser activeUser;

  /** 已停用账号。 */
  private SysUser disabledUser;

  /**
   * 每个用例前准备一个团队与两个账号（启用、停用），用户名带纳秒后缀避免唯一键冲突。
   */
  @BeforeEach
  void setUp() {
    Team team = new Team();
    team.setName("测试团队-" + System.nanoTime());
    teamMapper.insert(team);

    activeUser = buildUser("login_a_" + System.nanoTime(), team.getId(), 1);
    disabledUser = buildUser("login_d_" + System.nanoTime(), team.getId(), 0);
    sysUserMapper.insert(activeUser);
    sysUserMapper.insert(disabledUser);
  }

  /**
   * 清理测试产生的 Redis 黑名单键，DB 数据由 {@code @Transactional} 回滚。
   */
  @AfterEach
  void tearDown() {
    for (String token : issuedTokens) {
      stringRedisTemplate.delete(BLACKLIST_PREFIX + TokenBlackList.sha256Hex(token));
    }
    issuedTokens.clear();
  }

  /**
   * 正确用户名密码登录，返回 token 与用户信息。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("登录成功：返回 token、expiresIn 与用户信息")
  void shouldLoginSuccessfully() throws Exception {
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody(activeUser.getUsername(), RAW_PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.token").isNotEmpty())
        .andExpect(jsonPath("$.data.expiresIn").value(604800))
        .andExpect(jsonPath("$.data.user.username").value(activeUser.getUsername()))
        .andExpect(jsonPath("$.data.user.role").value("ENGINEER"))
        .andExpect(jsonPath("$.data.user.teamName").isNotEmpty());
  }

  /**
   * 密码错误返回 401，提示不区分「用户不存在」。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("密码错误：401 且提示用户名或密码错误")
  void shouldRejectWrongPassword() throws Exception {
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody(activeUser.getUsername(), "WrongPass1")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("用户名或密码错误"));
  }

  /**
   * 用户名不存在返回与密码错误一致的 401，避免用户名枚举。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("用户不存在：401 且提示与密码错误一致")
  void shouldRejectUnknownUser() throws Exception {
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody("no_such_user", RAW_PASSWORD)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("用户名或密码错误"));
  }

  /**
   * 停用账号即使密码正确也不能登录。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("账号停用：403 且提示联系管理员")
  void shouldRejectDisabledUser() throws Exception {
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody(disabledUser.getUsername(), RAW_PASSWORD)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.message").value("账号已停用，请联系管理员"));
  }

  /**
   * 不携带 token 访问当前用户接口应 401。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("当前用户：未携带 token 返回 401")
  void shouldRejectMeWithoutToken() throws Exception {
    mockMvc.perform(get("/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("未登录"));
  }

  /**
   * 携带登录拿到的 token 应返回当前用户信息。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("当前用户：带 token 返回本人信息")
  void shouldReturnCurrentUser() throws Exception {
    String token = loginAndGetToken(activeUser.getUsername(), RAW_PASSWORD);

    mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value(activeUser.getUsername()))
        .andExpect(jsonPath("$.data.displayName").value(activeUser.getDisplayName()));
  }

  /**
   * 登出后同一 token 立即失效，再访问当前用户接口应 401。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("登出：token 进入黑名单后立即失效")
  void shouldInvalidateTokenAfterLogout() throws Exception {
    String token = loginAndGetToken(activeUser.getUsername(), RAW_PASSWORD);

    mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("登录已失效，请重新登录"));

    assertThat(stringRedisTemplate.hasKey(BLACKLIST_PREFIX + TokenBlackList.sha256Hex(token))).isTrue();
  }

  /**
   * 登录并提取 token，同时登记以便测试结束后清理黑名单。
   *
   * @param username 登录名
   * @param password 明文密码
   * @return 签发的 JWT
   * @throws Exception MockMvc 调用异常
   */
  private String loginAndGetToken(String username, String password) throws Exception {
    String body = mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody(username, password)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);

    String token = objectMapper.readTree(body).path("data").path("token").asText();
    issuedTokens.add(token);
    return token;
  }

  /**
   * 拼接登录请求体。
   *
   * @param username 登录名
   * @param password 明文密码
   * @return JSON 字符串
   */
  private String loginBody(String username, String password) {
    return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
  }

  /**
   * 构造测试用户。
   *
   * @param username 登录名
   * @param teamId   团队 ID
   * @param status   1 启用 / 0 停用
   * @return 未持久化的用户实体
   */
  private SysUser buildUser(String username, Long teamId, int status) {
    SysUser user = new SysUser();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName("测试工程师");
    user.setRole("ENGINEER");
    user.setTeamId(teamId);
    user.setStatus(status);
    return user;
  }
}
