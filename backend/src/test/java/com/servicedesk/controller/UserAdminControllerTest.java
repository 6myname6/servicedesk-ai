package com.servicedesk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Team;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户管理接口的集成测试（M-01）。
 *
 * <p>
 * 属于接口层测试，启动完整上下文并走真实拦截器与 MySQL；数据由用例自建，
 * 依靠 {@code @Transactional} 回滚，不污染开发库。
 * </p>
 *
 * <p>
 * 重点覆盖三类风险：越权（非管理员 403、未登录 401）、唯一性（用户名/邮箱 409）、
 * 以及「把自己锁在门外」的自我保护（停用自己、降低自己角色）。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserAdminControllerTest {

  /** 测试用明文密码，长度满足 8-32 位校验。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 管理员账号的用户名，用例内自建，避免依赖种子数据。 */
  private static final String ADMIN_USERNAME_PREFIX = "ut_admin_";

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** JSON 解析与构造工具。 */
  @Autowired
  private ObjectMapper objectMapper;

  /** 用于直接签发指定角色的 token，绕开登录流程。 */
  @Autowired
  private JwtUtil jwtUtil;

  /** 用户 Mapper，用于造数与断言落库结果。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** 团队 Mapper，用于造团队数据。 */
  @Autowired
  private TeamMapper teamMapper;

  /** 客户 Mapper，用于造客户并验证客户联系人账号。 */
  @Autowired
  private CustomerMapper customerMapper;

  /** 密码编码器，用于校验落库密码确实是 BCrypt。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** Redis 模板，仅用于清理停用标记（Redis 不参与数据库事务，必须手工清理）。 */
  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  /** 本次用例新建的管理员账号，作为请求发起者。 */
  private SysUser adminUser;

  /** 本次用例新建的团队。 */
  private Team team;

  /** 本次用例新建的客户，用于验证客户联系人账号。 */
  private Customer customer;

  /** 本轮用例建过的用户 ID，结束后逐个清理其停用标记。 */
  private final List<Long> createdUserIds = new ArrayList<>();

  /**
   * 每个用例前准备一个管理员账号与一个团队，作为后续请求的发起者与归属团队。
   */
  @BeforeEach
  void setUp() {
    adminUser = insertUser(ADMIN_USERNAME_PREFIX + System.nanoTime(), "管理员", Role.ADMIN.name(), null);
    team = new Team();
    team.setName("用户管理测试团队-" + System.nanoTime());
    team.setSupervisorId(adminUser.getId());
    teamMapper.insert(team);

    customer = new Customer();
    customer.setName("用户管理测试客户-" + System.nanoTime());
    customer.setPhone("0792-0000000");
    customer.setLevel("NORMAL");
    customerMapper.insert(customer);
  }

  /**
   * 清理用例在 Redis 中留下的停用标记。
   *
   * <p>
   * 标记不随 {@code @Transactional} 回滚，不清理会在 7 天窗口内污染同一 ID 的后续用例。
   * </p>
   */
  @AfterEach
  void clearDisabledFlags() {
    createdUserIds.forEach(id -> stringRedisTemplate.delete("auth:user:disabled:" + id));
    createdUserIds.clear();
  }

  /**
   * 列表接口：管理员可按关键字与角色过滤，并返回分页结构。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("列表：按角色过滤并返回分页结构")
  void shouldPageUsers() throws Exception {
    String prefix = "ut_eng_" + System.nanoTime();
    insertUser(prefix + "_1", "工程师甲", Role.ENGINEER.name(), team.getId());
    insertUser(prefix + "_2", "工程师乙", Role.ENGINEER.name(), team.getId());

    mockMvc.perform(get("/admin/users")
            .param("keyword", prefix)
            .param("role", Role.ENGINEER.name())
            .param("page", "1")
            .param("size", "10")
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.total").value(2))
        .andExpect(jsonPath("$.data.records[0].username").value(org.hamcrest.Matchers.startsWith(prefix)))
        .andExpect(jsonPath("$.data.records[0].teamName").value(team.getName()))
        .andExpect(jsonPath("$.data.records[0].passwordHash").doesNotExist());
  }

  /**
   * 非管理员访问用户管理接口必须 403。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("权限：工程师访问用户管理接口返回 403")
  void shouldRejectNonAdmin() throws Exception {
    mockMvc.perform(get("/admin/users")
            .header("Authorization", bearerOf(adminUser.getId(), Role.ENGINEER)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  /**
   * 未登录访问先被鉴权拦截器拦下，返回 401 而不是 403。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("权限：未登录访问返回 401")
  void shouldRejectWithoutToken() throws Exception {
    mockMvc.perform(get("/admin/users"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  /**
   * 新增用户成功：密码以 BCrypt 落库，响应不含密码字段。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：成功并以 BCrypt 存储密码")
  void shouldCreateUser() throws Exception {
    String username = "ut_new_" + System.nanoTime();

    mockMvc.perform(post("/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(username, Role.ENGINEER.name()))
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value(username))
        .andExpect(jsonPath("$.data.status").value(1))
        .andExpect(jsonPath("$.data.teamName").value(team.getName()))
        .andExpect(jsonPath("$.data.password").doesNotExist())
        .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

    SysUser created = findByUsername(username);
    assertThat(created).isNotNull();
    assertThat(created.getPasswordHash()).startsWith("$2");
    assertThat(passwordEncoder.matches(RAW_PASSWORD, created.getPasswordHash())).isTrue();
  }

  /**
   * 用户名重复返回 409，而不是让唯一索引抛 500。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：用户名重复返回 409")
  void shouldRejectDuplicateUsername() throws Exception {
    String username = "ut_dup_" + System.nanoTime();
    insertUser(username, "重复用户", Role.REPORTER.name(), null);

    mockMvc.perform(post("/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(username, Role.REPORTER.name()))
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("用户名已存在"));
  }

  /**
   * 新增时角色取值非法返回 400（而不是登录接口的 401）。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：角色非法返回 400")
  void shouldRejectInvalidRole() throws Exception {
    mockMvc.perform(post("/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("ut_badrole_" + System.nanoTime(), "ROOT"))
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 入参格式不合法（用户名过短）返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：入参校验失败返回 400")
  void shouldRejectInvalidBody() throws Exception {
    mockMvc.perform(post("/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("ab", Role.REPORTER.name()))
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 修改不存在的用户返回 404。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：用户不存在返回 404")
  void shouldReturn404WhenUserMissing() throws Exception {
    mockMvc.perform(put("/admin/users/{id}", 999_999_999L)
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody("某人", Role.REPORTER.name(), null))
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  /**
   * 修改用户资料成功，团队名回填到响应中。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：成功更新团队并回填团队名")
  void shouldUpdateUser() throws Exception {
    SysUser target = insertUser("ut_upd_" + System.nanoTime(), "待改名", Role.REPORTER.name(), null);

    mockMvc.perform(put("/admin/users/{id}", target.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody("改后名字", Role.ENGINEER.name(), team.getId()))
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.displayName").value("改后名字"))
        .andExpect(jsonPath("$.data.role").value("ENGINEER"))
        .andExpect(jsonPath("$.data.teamName").value(team.getName()));

    SysUser updated = sysUserMapper.selectById(target.getId());
    assertThat(updated.getRole()).isEqualTo("ENGINEER");
    assertThat(updated.getTeamId()).isEqualTo(team.getId());
  }

  /**
   * 新增客户联系人账号：只挂客户、不挂团队，响应回填客户名。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("归属：新增客户联系人只挂客户并回填客户名")
  void shouldCreateReporterWithCustomer() throws Exception {
    String username = "ut_cust_" + System.nanoTime();

    mockMvc.perform(post("/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(username, Role.REPORTER.name(), null, customer.getId()))
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.customerId").value(customer.getId().intValue()))
        .andExpect(jsonPath("$.data.customerName").value(customer.getName()))
        .andExpect(jsonPath("$.data.teamId").value(nullValue()));

    SysUser created = findByUsername(username);
    assertThat(created.getCustomerId()).isEqualTo(customer.getId());
    assertThat(created.getTeamId()).isNull();
  }

  /**
   * 团队与客户同时传返回 400：一个账号只能有一种归属。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("归属：团队与客户同时传返回 400")
  void shouldRejectBothTeamAndCustomer() throws Exception {
    mockMvc.perform(post("/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("ut_both_" + System.nanoTime(), Role.REPORTER.name(),
                team.getId(), customer.getId()))
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("所属团队与所属客户只能二选一"));
  }

  /**
   * 客户 ID 指向不存在的客户返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("归属：客户不存在返回 400")
  void shouldRejectUnknownCustomer() throws Exception {
    mockMvc.perform(post("/admin/users")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("ut_nocust_" + System.nanoTime(), Role.REPORTER.name(),
                null, 999_999_999L))
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("客户不存在"));
  }

  /**
   * 从团队切换为客户：团队列必须被显式清空，否则会出现两种归属同时存在。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("归属：团队切换为客户时显式清空团队")
  void shouldSwitchTeamToCustomer() throws Exception {
    SysUser target = insertUser("ut_switch_" + System.nanoTime(), "待切换",
        Role.REPORTER.name(), team.getId());

    mockMvc.perform(put("/admin/users/{id}", target.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody("切换后", Role.REPORTER.name(), null, customer.getId()))
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.customerName").value(customer.getName()))
        .andExpect(jsonPath("$.data.teamName").value(nullValue()));

    SysUser updated = sysUserMapper.selectById(target.getId());
    assertThat(updated.getCustomerId()).isEqualTo(customer.getId());
    assertThat(updated.getTeamId()).isNull();
  }

  /**
   * 停用用户成功，状态落库为 0。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("启停：停用成功且状态落库")
  void shouldDisableUser() throws Exception {
    SysUser target = insertUser("ut_dis_" + System.nanoTime(), "待停用", Role.REPORTER.name(), null);

    mockMvc.perform(patch("/admin/users/{id}/status", target.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":0}")
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    assertThat(sysUserMapper.selectById(target.getId()).getStatus()).isZero();
  }

  /**
   * 管理员停用自己必须被拒绝，否则会把自己踢出系统且无法自助恢复。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("启停：停用当前登录账号返回 409")
  void shouldRejectDisablingSelf() throws Exception {
    mockMvc.perform(patch("/admin/users/{id}/status", adminUser.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":0}")
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("不能停用当前登录账号"));

    assertThat(sysUserMapper.selectById(adminUser.getId()).getStatus()).isEqualTo(1);
  }

  /**
   * 停用必须对已签发的 token 立即生效，重新启用后同一 token 又能正常访问。
   *
   * <p>
   * 这是「停用后下一次请求 401」的端到端验证：JWT 本身未过期，
   * 能否访问完全取决于服务端的停用标记，因此同时证明了标记的写入与清除都生效。
   * </p>
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("停用即时生效：旧 token 立即 401，重新启用后恢复访问")
  void shouldInvalidateTokenImmediatelyWhenDisabled() throws Exception {
    SysUser target = insertUser("ut_disable_" + System.nanoTime(), "被停用者", Role.REPORTER.name(), null);
    String targetToken = bearerOf(target.getId(), Role.REPORTER);

    // 停用前：同一 token 可正常访问
    mockMvc.perform(get("/auth/me").header("Authorization", targetToken))
        .andExpect(status().isOk());

    mockMvc.perform(patch("/admin/users/{id}/status", target.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":0}")
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isOk());

    // 停用后：token 未过期，但必须被拦下
    mockMvc.perform(get("/auth/me").header("Authorization", targetToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    mockMvc.perform(patch("/admin/users/{id}/status", target.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":1}")
            .header("Authorization", bearerOf(adminUser.getId(), Role.ADMIN)))
        .andExpect(status().isOk());

    // 重新启用后：同一 token 恢复可用
    mockMvc.perform(get("/auth/me").header("Authorization", targetToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value(target.getUsername()));
  }

  /**
   * 构造新增用户的请求体，默认挂本用例的团队。
   *
   * @param username 登录名
   * @param role     角色
   * @return JSON 字符串
   * @throws Exception JSON 序列化异常
   */
  private String createBody(String username, String role) throws Exception {
    return createBody(username, role, team.getId(), null);
  }

  /**
   * 构造新增用户的请求体。
   *
   * @param username   登录名
   * @param role       角色
   * @param teamId     团队 ID，可为 null
   * @param customerId 客户 ID，可为 null
   * @return JSON 字符串
   * @throws Exception JSON 序列化异常
   */
  private String createBody(String username, String role, Long teamId, Long customerId)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", username);
    body.put("password", RAW_PASSWORD);
    body.put("displayName", "测试用户");
    body.put("role", role);
    body.put("teamId", teamId);
    body.put("customerId", customerId);
    body.put("phone", "13900000000");
    return objectMapper.writeValueAsString(body);
  }

  /**
   * 构造修改用户的请求体。
   *
   * @param displayName 显示名
   * @param role        角色
   * @param teamId      团队 ID，可为 null
   * @return JSON 字符串
   * @throws Exception JSON 序列化异常
   */
  private String updateBody(String displayName, String role, Long teamId) throws Exception {
    return updateBody(displayName, role, teamId, null);
  }

  /**
   * 构造修改用户的请求体。
   *
   * @param displayName 显示名
   * @param role        角色
   * @param teamId      团队 ID，可为 null
   * @param customerId  客户 ID，可为 null
   * @return JSON 字符串
   * @throws Exception JSON 序列化异常
   */
  private String updateBody(String displayName, String role, Long teamId, Long customerId)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("displayName", displayName);
    body.put("role", role);
    body.put("teamId", teamId);
    body.put("customerId", customerId);
    return objectMapper.writeValueAsString(body);
  }

  /**
   * 为指定用户与角色签发鉴权请求头。
   *
   * @param userId 用户 ID
   * @param role   角色
   * @return 形如 {@code Bearer xxx} 的请求头取值
   */
  private String bearerOf(Long userId, Role role) {
    LoginUser user = new LoginUser(userId, "ut_operator", "操作员", role.name(), null);
    return "Bearer " + jwtUtil.generate(user);
  }

  /**
   * 直接插入一条用户，绕过接口，用于造数。
   *
   * @param username 登录名
   * @param name     显示名
   * @param role     角色存储值
   * @param teamId   团队 ID，可为 null
   * @return 插入后的用户实体（含自增 ID）
   */
  private SysUser insertUser(String username, String name, String role, Long teamId) {
    SysUser user = new SysUser();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(name);
    user.setRole(role);
    user.setTeamId(teamId);
    user.setStatus(1);
    sysUserMapper.insert(user);
    createdUserIds.add(user.getId());
    return user;
  }

  /**
   * 按用户名查询用户。
   *
   * @param username 登录名
   * @return 用户实体，不存在时为 null
   */
  private SysUser findByUsername(String username) {
    return sysUserMapper.selectOne(
        com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaQuery()
            .eq(SysUser::getUsername, username));
  }
}
