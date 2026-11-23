package com.servicedesk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.SysUser;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.security.JwtUtil;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 个人资料与密码管理接口的集成测试（A-05）。
 *
 * <p>
 * 属于接口层测试，启动完整上下文并走真实拦截器与 MySQL。重点守住三件事：
 * {@code /auth/me} 与登录响应能返回「所属团队或所属客户」；改密必须校验原密码，
 * 成功后旧密码立即失效；未登录不能改密。
 * </p>
 *
 * <p>
 * 与登录相关的用例会触碰 Redis 里的失败计数，{@link #tearDown()} 按 IP 与账号两个维度清理，
 * 避免跨用例、跨轮次累加后被限流打成 429。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProfileControllerTest {

  /** 测试用明文密码，长度满足 8-32 位校验。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 改密后的新密码，含字母与数字。 */
  private static final String NEW_PASSWORD = "NewPassw0rd";

  /** 登录失败计数的账号维度键前缀，与 LoginRateLimiter 保持一致。 */
  private static final String LOGIN_FAIL_USER_PREFIX = "auth:loginFail:user:";

  /** 登录失败计数的 IP 维度键前缀，与 LoginRateLimiter 保持一致。 */
  private static final String LOGIN_FAIL_IP_PREFIX = "auth:loginFail:ip:";

  /** MockMvc 模拟请求的默认客户端地址。 */
  private static final String MOCK_CLIENT_IP = "127.0.0.1";

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** JSON 序列化与解析工具。 */
  @Autowired
  private ObjectMapper objectMapper;

  /** 用户 Mapper，用于造数与断言落库结果。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** 客户 Mapper，用于造一个客户并挂到账号上。 */
  @Autowired
  private CustomerMapper customerMapper;

  /** 用于直接签发指定角色的 token，绕开登录流程。 */
  @Autowired
  private JwtUtil jwtUtil;

  /** 密码编码器，用于校验落库密码确实是 BCrypt。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** Redis 模板，仅用于清理登录失败计数。 */
  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  /** 客户联系人账号，挂客户、不挂团队。 */
  private SysUser customerUser;

  /** 内部员工账号，挂团队、不挂客户。 */
  private SysUser teamUser;

  /** 客户档案。 */
  private Customer customer;

  /**
   * 每个用例前准备一个客户与两个账号，用户名带纳秒后缀避免唯一键冲突。
   */
  @BeforeEach
  void setUp() {
    customer = new Customer();
    customer.setName("个人中心测试客户-" + System.nanoTime());
    customer.setPhone("0792-0000000");
    customer.setLevel("NORMAL");
    customerMapper.insert(customer);

    customerUser = insertUser("ut_profile_c_" + System.nanoTime(), null, customer.getId());
    teamUser = insertUser("ut_profile_t_" + System.nanoTime(), 1L, null);
  }

  /**
   * 清理登录失败计数，避免跨用例与跨轮次累积触发限流。
   */
  @AfterEach
  void tearDown() {
    stringRedisTemplate.delete(LOGIN_FAIL_IP_PREFIX + MOCK_CLIENT_IP);
    stringRedisTemplate.delete(LOGIN_FAIL_USER_PREFIX + customerUser.getUsername());
    stringRedisTemplate.delete(LOGIN_FAIL_USER_PREFIX + teamUser.getUsername());
  }

  /**
   * 客户联系人账号的当前用户信息带客户 ID 与客户名，团队为空。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("当前用户：客户联系人返回 customerId 与 customerName")
  void shouldReturnCustomerOrgInMe() throws Exception {
    String token = loginAndGetToken(customerUser.getUsername(), RAW_PASSWORD);

    mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.customerId").value(customer.getId().intValue()))
        .andExpect(jsonPath("$.data.customerName").value(customer.getName()))
        .andExpect(jsonPath("$.data.teamId").value(nullValue()))
        .andExpect(jsonPath("$.data.teamName").value(nullValue()));
  }

  /**
   * 登录响应同样返回归属信息，前端登录后无需再拉一次 {@code /auth/me} 就能渲染侧栏。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("登录：响应中的用户信息包含 customerId 与 customerName")
  void shouldReturnCustomerOrgInLogin() throws Exception {
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(loginBody(customerUser.getUsername(), RAW_PASSWORD))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.user.customerId").value(customer.getId().intValue()))
        .andExpect(jsonPath("$.data.user.customerName").value(customer.getName()));

    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(loginBody(teamUser.getUsername(), RAW_PASSWORD))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.user.customerId").value(nullValue()))
        .andExpect(jsonPath("$.data.user.teamId").value(1));
  }

  /**
   * 改密成功：新密码以 BCrypt 落库，旧密码登录 401，新密码登录 200。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("改密：成功后旧密码失效、新密码可登录")
  void shouldChangePassword() throws Exception {
    String token = loginAndGetToken(customerUser.getUsername(), RAW_PASSWORD);

    mockMvc.perform(put("/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(passwordBody(RAW_PASSWORD, NEW_PASSWORD)))
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    SysUser saved = sysUserMapper.selectById(customerUser.getId());
    assertThat(saved.getPasswordHash()).startsWith("$2");
    assertThat(passwordEncoder.matches(NEW_PASSWORD, saved.getPasswordHash())).isTrue();
    assertThat(passwordEncoder.matches(RAW_PASSWORD, saved.getPasswordHash())).isFalse();

    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(loginBody(customerUser.getUsername(), RAW_PASSWORD))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("用户名或密码错误"));

    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(loginBody(customerUser.getUsername(), NEW_PASSWORD))))
        .andExpect(status().isOk());
  }

  /**
   * 原密码不正确返回 400，密码哈希保持原样。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("改密：原密码错误返回 400 且不改库")
  void shouldRejectWrongOldPassword() throws Exception {
    String before = sysUserMapper.selectById(customerUser.getId()).getPasswordHash();

    mockMvc.perform(put("/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(passwordBody("WrongPass1", NEW_PASSWORD)))
            .header("Authorization", signToken(customerUser.getId())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("原密码不正确"));

    assertThat(sysUserMapper.selectById(customerUser.getId()).getPasswordHash()).isEqualTo(before);
  }

  /**
   * 新旧密码相同返回 400，避免「改了等于没改」。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("改密：新旧密码相同返回 400")
  void shouldRejectSamePassword() throws Exception {
    mockMvc.perform(put("/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(passwordBody(RAW_PASSWORD, RAW_PASSWORD)))
            .header("Authorization", signToken(customerUser.getId())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("新密码不能与原密码相同"));
  }

  /**
   * 新密码不满足强度规则（纯数字、过短）返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("改密：新密码强度不足返回 400")
  void shouldRejectWeakNewPassword() throws Exception {
    String token = signToken(customerUser.getId());

    mockMvc.perform(put("/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(passwordBody(RAW_PASSWORD, "12345678")))
            .header("Authorization", token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("newPassword: 新密码需同时包含字母与数字"));

    mockMvc.perform(put("/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(passwordBody(RAW_PASSWORD, "Ab1")))
            .header("Authorization", token))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 未登录不能改密，先被鉴权拦截器拦下。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("改密：未登录返回 401")
  void shouldRejectChangePasswordWithoutToken() throws Exception {
    mockMvc.perform(put("/auth/password")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(passwordBody(RAW_PASSWORD, NEW_PASSWORD))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  /**
   * 登录并提取 token。
   *
   * @param username 登录名
   * @param password 明文密码
   * @return 签发的 JWT
   * @throws Exception MockMvc 调用异常
   */
  private String loginAndGetToken(String username, String password) throws Exception {
    String body = mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(loginBody(username, password))))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
    return objectMapper.readTree(body).path("data").path("token").asText();
  }

  /**
   * 直接签发 token，绕开登录流程与本用例无关的限流计数。
   *
   * @param userId 用户 ID
   * @return 形如 {@code Bearer xxx} 的请求头取值
   */
  private String signToken(Long userId) {
    LoginUser user = new LoginUser(userId, "ut_profile", "个人中心测试", "REPORTER", null);
    return "Bearer " + jwtUtil.generate(user);
  }

  /**
   * 构造登录请求体。
   *
   * @param username 登录名
   * @param password 明文密码
   * @return 请求体映射
   */
  private Map<String, Object> loginBody(String username, String password) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", username);
    body.put("password", password);
    return body;
  }

  /**
   * 构造改密请求体。
   *
   * @param oldPassword 原密码
   * @param newPassword 新密码
   * @return 请求体映射
   */
  private Map<String, Object> passwordBody(String oldPassword, String newPassword) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("oldPassword", oldPassword);
    body.put("newPassword", newPassword);
    return body;
  }

  /**
   * 直接插入一个账号，绕过接口，用于造数。
   *
   * @param username   登录名
   * @param teamId     团队 ID，可为 null
   * @param customerId 客户 ID，可为 null
   * @return 插入后的账号实体
   */
  private SysUser insertUser(String username, Long teamId, Long customerId) {
    SysUser user = new SysUser();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName("个人中心测试账号");
    user.setRole("REPORTER");
    user.setTeamId(teamId);
    user.setCustomerId(customerId);
    user.setStatus(1);
    sysUserMapper.insert(user);
    return user;
  }
}
