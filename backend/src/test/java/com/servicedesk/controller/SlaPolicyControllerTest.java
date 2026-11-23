package com.servicedesk.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TicketMapper;
import com.servicedesk.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SLA 策略管理接口的集成测试（S-01）。
 *
 * <p>
 * 属于接口层测试，启动完整上下文并走真实拦截器与 MySQL；数据由用例自建，
 * 依靠 {@code @Transactional} 回滚，不污染开发库。
 * </p>
 *
 * <p>
 * 策略表的优先级只有 P0 - P3 四个取值且带唯一索引，无法像团队名那样用随机后缀避让，
 * 因此每个用例开始前清空该表（事务内删除，回滚后恢复），使断言不受开发库现有数据影响。
 * </p>
 *
 * <p>
 * 重点覆盖三类风险：越权（非管理员写操作 403）、唯一性（重复优先级 409）、
 * 以及删除语义（被工单引用时降级为停用而不是物理删除）。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SlaPolicyControllerTest {

  /** 测试用明文密码，满足账号表的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 新增成功时未传 enabled，按接口文档默认为启用。 */
  private static final int ENABLED_FLAG = 1;

  /** 停用状态的落库取值。 */
  private static final int DISABLED_FLAG = 0;

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** JSON 解析与构造工具。 */
  @Autowired
  private ObjectMapper objectMapper;

  /** 用于直接签发指定角色的 token，绕开登录流程。 */
  @Autowired
  private JwtUtil jwtUtil;

  /** 用户 Mapper，用于造账号数据与工单报修人。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** SLA 策略 Mapper，用于造数与断言落库结果。 */
  @Autowired
  private SlaPolicyMapper slaPolicyMapper;

  /** 工单 Mapper，用于造「策略被引用」的数据。 */
  @Autowired
  private TicketMapper ticketMapper;

  /** 密码编码器，保证造出的账号密码列符合 BCrypt 存储约定。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** 本次用例新建的管理员账号，作为请求发起者。 */
  private SysUser admin;

  /** 本次用例新建的主管账号，用于验证越权。 */
  private SysUser supervisor;

  /**
   * 每个用例前清空策略表并准备管理员与主管账号。
   */
  @BeforeEach
  void setUp() {
    slaPolicyMapper.delete(Wrappers.<SlaPolicy>lambdaQuery().isNotNull(SlaPolicy::getId));
    admin = insertUser(Role.ADMIN, "管理员");
    supervisor = insertUser(Role.SUPERVISOR, "王主管");
  }

  /**
   * 列表接口对登录用户开放，并按优先级升序返回。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("列表：登录用户可读，按优先级升序")
  void shouldListPoliciesOrderedByPriority() throws Exception {
    insertPolicy("P2", "P2 一般", 120, 480, ENABLED_FLAG);
    insertPolicy("P0", "P0 紧急", 15, 120, ENABLED_FLAG);
    insertPolicy("P3", "P3 咨询", 480, 1440, ENABLED_FLAG);
    insertPolicy("P1", "P1 标准", 30, 240, DISABLED_FLAG);

    SysUser reporter = insertUser(Role.REPORTER, "报修人");

    mockMvc.perform(get("/sla-policies").header("Authorization", bearerOf(reporter.getId(), Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data[*].priority").value(contains("P0", "P1", "P2", "P3")))
        .andExpect(jsonPath("$.data[1].enabled").value(false));
  }

  /**
   * 未登录访问列表先被鉴权拦截器拦下，返回 401。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("列表：未登录返回 401")
  void shouldRejectListWithoutToken() throws Exception {
    mockMvc.perform(get("/sla-policies"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  /**
   * 新增成功：未传 enabled 时默认启用，字段正确落库。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：成功返回策略且 enabled 默认为 true")
  void shouldCreatePolicyWithDefaultEnabled() throws Exception {
    mockMvc.perform(post("/sla-policies")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("P2 一般", "P2", 120, 480, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("P2 一般"))
        .andExpect(jsonPath("$.data.priority").value("P2"))
        .andExpect(jsonPath("$.data.responseMinutes").value(120))
        .andExpect(jsonPath("$.data.resolveMinutes").value(480))
        .andExpect(jsonPath("$.data.enabled").value(true));

    SlaPolicy saved = findByPriority("P2");
    assertThat(saved).isNotNull();
    assertThat(saved.getEnabled()).isEqualTo(ENABLED_FLAG);
  }

  /**
   * 新增成功：显式传 enabled=false 时落库为停用。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：enabled=false 时落库为停用")
  void shouldCreateDisabledPolicy() throws Exception {
    mockMvc.perform(post("/sla-policies")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("P3 咨询", "P3", 480, 1440, false))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false));

    assertThat(findByPriority("P3").getEnabled()).isEqualTo(DISABLED_FLAG);
  }

  /**
   * 同一优先级重复新增返回 409，而不是让唯一索引抛 500。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：优先级重复返回 409")
  void shouldRejectDuplicatePriority() throws Exception {
    insertPolicy("P1", "P1 标准", 30, 240, ENABLED_FLAG);

    mockMvc.perform(post("/sla-policies")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("P1 另一套", "P1", 30, 240, true))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("该优先级的策略已存在"));
  }

  /**
   * 解决时限小于响应时限返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：解决时限小于响应时限返回 400")
  void shouldRejectResolveLessThanResponse() throws Exception {
    mockMvc.perform(post("/sla-policies")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("P0 异常", "P0", 120, 60, true))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("解决时限不能小于响应时限"));
  }

  /**
   * 时限为非正数被参数校验拦下，返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：响应时限为 0 返回 400")
  void shouldRejectNonPositiveMinutes() throws Exception {
    mockMvc.perform(post("/sla-policies")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("P2 一般", "P2", 0, 480, true))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 优先级不在 P0 - P3 字典内返回 400，且不落库。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：优先级不在字典内返回 400")
  void shouldRejectUnknownPriority() throws Exception {
    mockMvc.perform(post("/sla-policies")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("P9 未知", "P9", 30, 240, true))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(findByPriority("P9")).isNull();
  }

  /**
   * 非管理员新增策略返回 403。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：主管调用返回 403")
  void shouldRejectCreateByNonAdmin() throws Exception {
    mockMvc.perform(post("/sla-policies")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("P2 一般", "P2", 120, 480, true))
            .header("Authorization", bearerOf(supervisor.getId(), Role.SUPERVISOR)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(findByPriority("P2")).isNull();
  }

  /**
   * 修改成功：名称、两个时限与启用状态整体覆盖，优先级保持不变。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：成功更新时限与启用状态且优先级不变")
  void shouldUpdatePolicy() throws Exception {
    SlaPolicy policy = insertPolicy("P2", "P2 一般", 120, 480, ENABLED_FLAG);

    mockMvc.perform(put("/sla-policies/{id}", policy.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody("P2 一般（调整）", 60, 360, false))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("P2 一般（调整）"))
        .andExpect(jsonPath("$.data.priority").value("P2"))
        .andExpect(jsonPath("$.data.responseMinutes").value(60))
        .andExpect(jsonPath("$.data.resolveMinutes").value(360))
        .andExpect(jsonPath("$.data.enabled").value(false));

    SlaPolicy updated = slaPolicyMapper.selectById(policy.getId());
    assertThat(updated.getPriority()).isEqualTo("P2");
    assertThat(updated.getEnabled()).isEqualTo(DISABLED_FLAG);
  }

  /**
   * 修改不存在的策略返回 404。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：策略不存在返回 404")
  void shouldReturn404WhenUpdateMissingPolicy() throws Exception {
    mockMvc.perform(put("/sla-policies/{id}", 999_999_999L)
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody("任意策略", 30, 240, true))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("SLA 策略不存在"));
  }

  /**
   * 修改时解决时限小于响应时限返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：解决时限小于响应时限返回 400")
  void shouldRejectResolveLessThanResponseOnUpdate() throws Exception {
    SlaPolicy policy = insertPolicy("P1", "P1 标准", 30, 240, ENABLED_FLAG);

    mockMvc.perform(put("/sla-policies/{id}", policy.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody("P1 标准", 240, 30, true))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 非管理员修改策略返回 403。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：主管调用返回 403")
  void shouldRejectUpdateByNonAdmin() throws Exception {
    SlaPolicy policy = insertPolicy("P1", "P1 标准", 30, 240, ENABLED_FLAG);

    mockMvc.perform(put("/sla-policies/{id}", policy.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody("P1 标准", 30, 240, true))
            .header("Authorization", bearerOf(supervisor.getId(), Role.SUPERVISOR)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  /**
   * 无工单引用时物理删除。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("删除：无引用时物理删除")
  void shouldPhysicallyDeleteUnreferencedPolicy() throws Exception {
    SlaPolicy policy = insertPolicy("P3", "P3 咨询", 480, 1440, ENABLED_FLAG);

    mockMvc.perform(delete("/sla-policies/{id}", policy.getId())
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    assertThat(slaPolicyMapper.selectById(policy.getId())).isNull();
  }

  /**
   * 被工单引用时仅停用，保留策略记录供历史工单查询。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("删除：被工单引用时仅停用")
  void shouldDisableReferencedPolicy() throws Exception {
    SlaPolicy policy = insertPolicy("P2", "P2 一般", 120, 480, ENABLED_FLAG);
    insertTicketReferencing(policy.getId(), admin.getId());

    mockMvc.perform(delete("/sla-policies/{id}", policy.getId())
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    SlaPolicy saved = slaPolicyMapper.selectById(policy.getId());
    assertThat(saved).isNotNull();
    assertThat(saved.getEnabled()).isEqualTo(DISABLED_FLAG);
  }

  /**
   * 已被引用且已停用的策略重复删除仍返回成功，不重复写库。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("删除：已停用且被引用时重复调用幂等返回成功")
  void shouldReturnSuccessWhenDeletingDisabledReferencedPolicyAgain() throws Exception {
    SlaPolicy policy = insertPolicy("P1", "P1 标准", 30, 240, DISABLED_FLAG);
    insertTicketReferencing(policy.getId(), admin.getId());

    mockMvc.perform(delete("/sla-policies/{id}", policy.getId())
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    SlaPolicy saved = slaPolicyMapper.selectById(policy.getId());
    assertThat(saved).isNotNull();
    assertThat(saved.getEnabled()).isEqualTo(DISABLED_FLAG);
  }

  /**
   * 删除不存在的策略返回 404。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("删除：策略不存在返回 404")
  void shouldReturn404WhenDeleteMissingPolicy() throws Exception {
    mockMvc.perform(delete("/sla-policies/{id}", 999_999_999L)
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("SLA 策略不存在"));
  }

  /**
   * 非管理员删除策略返回 403，策略不被改动。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("删除：主管调用返回 403")
  void shouldRejectDeleteByNonAdmin() throws Exception {
    SlaPolicy policy = insertPolicy("P0", "P0 紧急", 15, 120, ENABLED_FLAG);

    mockMvc.perform(delete("/sla-policies/{id}", policy.getId())
            .header("Authorization", bearerOf(supervisor.getId(), Role.SUPERVISOR)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    assertThat(slaPolicyMapper.selectById(policy.getId())).isNotNull();
  }

  /**
   * 构造新增请求体。
   *
   * @param name            策略名
   * @param priority        优先级
   * @param responseMinutes 响应时限
   * @param resolveMinutes  解决时限
   * @param enabled         是否启用，可为 null 表示不传
   * @return JSON 字符串
   * @throws Exception JSON 序列化异常
   */
  private String createBody(String name, String priority, int responseMinutes, int resolveMinutes,
      Boolean enabled) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("priority", priority);
    body.put("responseMinutes", responseMinutes);
    body.put("resolveMinutes", resolveMinutes);
    body.put("enabled", enabled);
    return objectMapper.writeValueAsString(body);
  }

  /**
   * 构造修改请求体（不含优先级）。
   *
   * @param name            策略名
   * @param responseMinutes 响应时限
   * @param resolveMinutes  解决时限
   * @param enabled         是否启用
   * @return JSON 字符串
   * @throws Exception JSON 序列化异常
   */
  private String updateBody(String name, int responseMinutes, int resolveMinutes, boolean enabled)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("responseMinutes", responseMinutes);
    body.put("resolveMinutes", resolveMinutes);
    body.put("enabled", enabled);
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
   * 直接插入一条账号，绕过接口，用于造数。
   *
   * @param role 角色
   * @param name 显示名
   * @return 插入后的账号实体（含自增 ID）
   */
  private SysUser insertUser(Role role, String name) {
    SysUser user = new SysUser();
    user.setUsername("ut_sla_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(name);
    user.setRole(role.name());
    user.setStatus(1);
    sysUserMapper.insert(user);
    return user;
  }

  /**
   * 直接插入一条策略，绕过接口，用于造数。
   *
   * @param priority        优先级
   * @param name            策略名
   * @param responseMinutes 响应时限
   * @param resolveMinutes  解决时限
   * @param enabled         启用标记
   * @return 插入后的策略实体（含自增 ID）
   */
  private SlaPolicy insertPolicy(String priority, String name, int responseMinutes,
      int resolveMinutes, int enabled) {
    SlaPolicy policy = new SlaPolicy();
    policy.setName(name);
    policy.setPriority(priority);
    policy.setResponseMinutes(responseMinutes);
    policy.setResolveMinutes(resolveMinutes);
    policy.setEnabled(enabled);
    slaPolicyMapper.insert(policy);
    return policy;
  }

  /**
   * 插入一条引用指定策略的工单，用于验证删除降级为停用。
   *
   * @param policyId   策略 ID
   * @param reporterId 报修人 ID
   */
  private void insertTicketReferencing(Long policyId, Long reporterId) {
    Ticket ticket = new Ticket();
    ticket.setTicketNo("TK" + System.nanoTime());
    ticket.setTitle("SLA 策略引用测试");
    ticket.setDescription("仅用于验证删除语义");
    ticket.setStatus("NEW");
    ticket.setCustomerId(1L);
    ticket.setReporterId(reporterId);
    ticket.setSlaPolicyId(policyId);
    ticketMapper.insert(ticket);
  }

  /**
   * 按优先级查询策略。
   *
   * @param priority 优先级
   * @return 策略实体，不存在时为 null
   */
  private SlaPolicy findByPriority(String priority) {
    return slaPolicyMapper.selectOne(Wrappers.<SlaPolicy>lambdaQuery()
        .eq(SlaPolicy::getPriority, priority));
  }
}
