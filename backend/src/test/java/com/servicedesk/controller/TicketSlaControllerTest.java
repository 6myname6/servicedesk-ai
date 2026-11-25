package com.servicedesk.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.enums.DeviceStatus;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单 SLA 倒计时接口的集成测试（S-02，接口文档 5.5）。
 *
 * <p>
 * 属于接口层测试：启动完整上下文，走真实拦截器与 MySQL，数据由用例自建并依赖
 * {@code @Transactional} 回滚。重点守住四件事：剩余分钟按请求时刻实时计算、
 * 响应与解决两个时钟独立判定、终态停止计时（详情与列表口径一致）、越权统一 404。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketSlaControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 建单策略的响应时限，用于构造「还剩 30 分钟」的工单。 */
  private static final int RESPONSE_MINUTES = 30;

  /** 建单策略的解决时限，用于构造「还剩 4 小时」的工单。 */
  private static final int RESOLVE_MINUTES = 240;

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** 用于直接签发指定角色的 token。 */
  @Autowired
  private JwtUtil jwtUtil;

  /** 工单 Mapper。 */
  @Autowired
  private TicketMapper ticketMapper;

  /** 客户 Mapper。 */
  @Autowired
  private CustomerMapper customerMapper;

  /** 设备 Mapper。 */
  @Autowired
  private DeviceMapper deviceMapper;

  /** 用户 Mapper。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** SLA 策略 Mapper。 */
  @Autowired
  private SlaPolicyMapper slaPolicyMapper;

  /** 密码编码器。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** 报修人 A，用例中作为工单归属人。 */
  private SysUser reporterA;

  /** 报修人 B，用于构造 A 不可见的工单。 */
  private SysUser reporterB;

  /** 工单所属客户。 */
  private Customer customer;

  /** 客户名下设备。 */
  private Device device;

  /** 用例使用的启用策略。 */
  private SlaPolicy policy;

  /**
   * 每个用例前清空工单并准备账号、客户、设备与策略。
   */
  @BeforeEach
  void setUp() {
    // 开发库有种子工单，清空后剩余分钟与列表断言不受干扰；事务结束自动回滚
    ticketMapper.delete(null);
    reporterA = insertUser(Role.REPORTER);
    reporterB = insertUser(Role.REPORTER);
    customer = insertCustomer("九江精工机械");
    device = insertDevice(customer.getId(), "SN-SLA-" + System.nanoTime());
    policy = ensurePolicy();
  }

  /**
   * 未响应时按请求时刻实时计算剩余分钟：两个截止时间与策略信息都应返回。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("倒计时：未响应时返回两个 deadline 与剩余分钟")
  void shouldReturnRemainingMinutesForPendingTicket() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("NEW", reporterA.getId(),
        now.plusMinutes(RESPONSE_MINUTES), now.plusMinutes(RESOLVE_MINUTES));

    MvcResult result = mockMvc.perform(get("/tickets/{id}/sla", ticket.getId())
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.policyId").value(policy.getId().intValue()))
        .andExpect(jsonPath("$.data.policyName").value(policy.getName()))
        .andExpect(jsonPath("$.data.firstResponseAt").value(nullValue()))
        .andExpect(jsonPath("$.data.paused").value(false))
        .andExpect(jsonPath("$.data.pausedTotalMinutes").value(0))
        .andExpect(jsonPath("$.data.responseBreached").value(false))
        .andExpect(jsonPath("$.data.resolveBreached").value(false))
        .andReturn();

    // 剩余分钟按「请求时刻 → deadline」截断到分钟，允许测试执行耗掉 1 分钟
    int responseRemaining = readInt(result, "responseRemainingMinutes");
    int resolveRemaining = readInt(result, "resolveRemainingMinutes");
    assertThat(responseRemaining).isBetween(RESPONSE_MINUTES - 1, RESPONSE_MINUTES);
    assertThat(resolveRemaining).isBetween(RESOLVE_MINUTES - 1, RESOLVE_MINUTES);
  }

  /**
   * 响应与解决两个时钟独立判定：只过响应截止时，只有响应超时。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("倒计时：只过响应截止时只标记响应超时")
  void shouldMarkResponseBreachOnly() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("ASSIGNED", reporterA.getId(), now.minusHours(1), now.plusHours(2));

    mockMvc.perform(get("/tickets/{id}/sla", ticket.getId())
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.responseBreached").value(true))
        .andExpect(jsonPath("$.data.resolveBreached").value(false))
        .andExpect(jsonPath("$.data.firstResponseAt").value(nullValue()));
  }

  /**
   * 已响应之后即使过了响应截止，也不再标记响应超时；首响时间原样返回。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("倒计时：已响应后不再标记响应超时")
  void shouldNotMarkResponseBreachAfterFirstResponse() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("IN_PROGRESS", reporterA.getId(), now.minusHours(1), now.plusHours(2));
    LocalDateTime firstResponseAt = now.minusMinutes(90);
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getFirstResponseAt, firstResponseAt));

    mockMvc.perform(get("/tickets/{id}/sla", ticket.getId())
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.responseBreached").value(false))
        .andExpect(jsonPath("$.data.firstResponseAt").value(firstResponseAt.toString()));
  }

  /**
   * 终态停止计时：详情与列表两条读路径都不能把已关闭 / 已取消的单标成超时。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("倒计时：终态工单详情与列表都不标超时")
  void shouldNotMarkBreachForTerminalTicket() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("CANCELLED", reporterA.getId(), now.minusHours(3), now.minusHours(2));

    mockMvc.perform(get("/tickets/{id}/sla", ticket.getId())
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.responseBreached").value(false))
        .andExpect(jsonPath("$.data.resolveBreached").value(false));

    // 列表的 slaBreached 与详情共用同一口径：终态不标红，但剩余分钟照常为负
    mockMvc.perform(get("/tickets").param("status", "CANCELLED")
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.records[0].slaBreached").value(false))
        .andExpect(jsonPath("$.data.records[0].slaRemainingMinutes").isNumber());
  }

  /**
   * 数据范围外统一 404：报修人看不了他人的工单，SLA 接口同样如此。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：报修人查他人工单返回 404")
  void shouldReturn404WhenReporterAccessOthers() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("NEW", reporterB.getId(), now.plusMinutes(30), now.plusHours(4));

    mockMvc.perform(get("/tickets/{id}/sla", ticket.getId())
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("工单不存在"));
  }

  /**
   * 历史数据没有策略也没有 deadline 时返回 null，不能 500。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("容错：无 SLA 数据时 data 为 null")
  void shouldReturnNullWhenNoSlaData() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null);
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getSlaPolicyId, null));

    mockMvc.perform(get("/tickets/{id}/sla", ticket.getId())
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(nullValue()));
  }

  /**
   * 未登录访问返回 401，不进入数据范围判定。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("鉴权：未登录返回 401")
  void shouldReturn401WhenAnonymous() throws Exception {
    mockMvc.perform(get("/tickets/{id}/sla", 1L).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  /**
   * 为指定用户与角色签发鉴权请求头。
   *
   * @param user 账号
   * @param role 角色
   * @return 形如 {@code Bearer xxx} 的请求头取值
   */
  private String bearerOf(SysUser user, Role role) {
    LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getDisplayName(),
        role.name(), user.getTeamId());
    return "Bearer " + jwtUtil.generate(loginUser);
  }

  /**
   * 从响应 JSON 中读取指定整数字段。
   *
   * @param result MockMvc 结果
   * @param field  SLA 对象内的字段名
   * @return 字段值
   * @throws Exception JSON 解析异常
   */
  private int readInt(MvcResult result, String field) throws Exception {
    String body = result.getResponse().getContentAsString();
    return com.jayway.jsonpath.JsonPath.read(body, "$.data." + field);
  }

  /**
   * 直接插入一个账号，绕过接口，用于造数。
   *
   * @param role 角色
   * @return 插入后的账号实体
   */
  private SysUser insertUser(Role role) {
    SysUser user = new SysUser();
    user.setUsername("ut_sla_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "SLA测试账号");
    user.setRole(role.name());
    user.setStatus(1);
    sysUserMapper.insert(user);
    return user;
  }

  /**
   * 直接插入一个客户。
   *
   * @param name 客户名
   * @return 插入后的客户实体
   */
  private Customer insertCustomer(String name) {
    Customer entity = new Customer();
    entity.setName(name);
    entity.setPhone("0792-0000000");
    entity.setLevel("NORMAL");
    customerMapper.insert(entity);
    return entity;
  }

  /**
   * 直接插入一台设备。
   *
   * @param customerId 所属客户 ID
   * @param serialNo   序列号
   * @return 插入后的设备实体
   */
  private Device insertDevice(Long customerId, String serialNo) {
    Device entity = new Device();
    entity.setCustomerId(customerId);
    entity.setModel("AC-2000");
    entity.setSerialNo(serialNo);
    entity.setStatus(DeviceStatus.ACTIVE.name());
    deviceMapper.insert(entity);
    return entity;
  }

  /**
   * 确保 P1 存在一条启用策略，已存在则复用，避免与开发库种子数据冲突。
   *
   * @return 启用中的 P1 策略
   */
  private SlaPolicy ensurePolicy() {
    SlaPolicy existing = slaPolicyMapper.selectOne(Wrappers.<SlaPolicy>lambdaQuery()
        .eq(SlaPolicy::getPriority, "P1")
        .eq(SlaPolicy::getEnabled, 1)
        .last("LIMIT 1"));
    if (existing != null) {
      return existing;
    }
    SlaPolicy entity = new SlaPolicy();
    entity.setName("P1 测试策略");
    entity.setPriority("P1");
    entity.setResponseMinutes(RESPONSE_MINUTES);
    entity.setResolveMinutes(RESOLVE_MINUTES);
    entity.setEnabled(1);
    slaPolicyMapper.insert(entity);
    return entity;
  }

  /**
   * 直接插入一张工单。
   *
   * @param status           状态
   * @param reporterId       报修人 ID
   * @param responseDeadline 响应截止，可为 null
   * @param resolveDeadline  解决截止，可为 null
   * @return 插入后的工单实体
   */
  private Ticket insertTicket(String status, Long reporterId,
      LocalDateTime responseDeadline, LocalDateTime resolveDeadline) {
    Ticket ticket = new Ticket();
    ticket.setTicketNo("TK" + System.nanoTime());
    ticket.setTitle("空压机运行异响");
    ticket.setDescription("开机 10 分钟后出现金属摩擦声，压力表波动。");
    ticket.setCategory("HARDWARE");
    ticket.setUrgency("P1");
    ticket.setStatus(status);
    ticket.setCustomerId(customer.getId());
    ticket.setDeviceId(device.getId());
    ticket.setReporterId(reporterId);
    ticket.setSlaPolicyId(policy.getId());
    ticket.setResponseDeadline(responseDeadline);
    ticket.setResolveDeadline(resolveDeadline);
    ticket.setPausedTotalMinutes(0);
    ticket.setAiCorrected(0);
    ticketMapper.insert(ticket);
    return ticket;
  }
}
