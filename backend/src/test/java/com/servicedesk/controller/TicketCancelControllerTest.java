package com.servicedesk.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Team;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketEvent;
import com.servicedesk.entity.enums.DeviceStatus;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
import com.servicedesk.mapper.TicketEventMapper;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 取消工单接口的集成测试（T-05）。
 *
 * <p>
 * 属于接口层测试：启动完整上下文，走真实拦截器、MySQL 与乐观锁，数据由用例自建并依赖
 * {@code @Transactional} 回滚。重点守住四件事：角色与数据范围的两层拦截（403 / 404）、
 * 状态机（`RESOLVED` 与终态不可取消）、版本冲突 409、时间线必须写入取消原因。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketCancelControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 取消原因样例。 */
  private static final String REASON = "客户自行恢复，无需上门";

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** JSON 序列化工具。 */
  @Autowired
  private ObjectMapper objectMapper;

  /** 用于直接签发指定角色的 token。 */
  @Autowired
  private JwtUtil jwtUtil;

  /** 工单 Mapper。 */
  @Autowired
  private TicketMapper ticketMapper;

  /** 工单事件 Mapper，用于断言时间线。 */
  @Autowired
  private TicketEventMapper ticketEventMapper;

  /** 客户 Mapper。 */
  @Autowired
  private CustomerMapper customerMapper;

  /** 设备 Mapper。 */
  @Autowired
  private DeviceMapper deviceMapper;

  /** 团队 Mapper。 */
  @Autowired
  private TeamMapper teamMapper;

  /** 用户 Mapper。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** SLA 策略 Mapper。 */
  @Autowired
  private SlaPolicyMapper slaPolicyMapper;

  /** 密码编码器。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** 报修人 A。 */
  private SysUser reporterA;

  /** 报修人 B，用于构造他人的工单。 */
  private SysUser reporterB;

  /** 工程师 A，属于团队 A。 */
  private SysUser engineerA;

  /** 工程师 B，属于团队 B。 */
  private SysUser engineerB;

  /** 主管 A，管理团队 A。 */
  private SysUser supervisorA;

  /** 管理员。 */
  private SysUser admin;

  /** 团队 A。 */
  private Team teamA;

  /** 团队 B。 */
  private Team teamB;

  /** 客户。 */
  private Customer customer;

  /** 设备。 */
  private Device device;

  /** SLA 策略。 */
  private SlaPolicy policy;

  /**
   * 每个用例前准备两套团队、客户与五类账号。
   */
  @BeforeEach
  void setUp() {
    ticketMapper.delete(null);
    reporterA = insertUser(Role.REPORTER, null);
    reporterB = insertUser(Role.REPORTER, null);
    engineerA = insertUser(Role.ENGINEER, null);
    engineerB = insertUser(Role.ENGINEER, null);
    supervisorA = insertUser(Role.SUPERVISOR, null);
    admin = insertUser(Role.ADMIN, null);

    teamA = insertTeam("华东售后一组", supervisorA.getId());
    teamB = insertTeam("华东售后二组", null);
    engineerA.setTeamId(teamA.getId());
    engineerB.setTeamId(teamB.getId());
    sysUserMapper.updateById(engineerA);
    sysUserMapper.updateById(engineerB);

    customer = insertCustomer("九江精工机械");
    device = insertDevice(customer.getId(), "SN-CANCEL-" + System.nanoTime());
    policy = ensurePolicy("P1");
  }

  /**
   * 报修人取消自己的 NEW 工单：状态变更、版本自增、时间线写入原因。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("取消：报修人取消自己的 NEW 工单并写时间线")
  void shouldCancelOwnNewTicket() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null);

    mockMvc.perform(post("/tickets/{id}/cancel", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(REASON, 0))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.status").value("CANCELLED"))
        .andExpect(jsonPath("$.data.version").value(1));

    TicketEvent event = ticketEventMapper.selectOne(Wrappers.<TicketEvent>lambdaQuery()
        .eq(TicketEvent::getTicketId, ticket.getId()));
    assertThat(event).isNotNull();
    assertThat(event.getType()).isEqualTo("CANCEL");
    assertThat(event.getFromStatus()).isEqualTo("NEW");
    assertThat(event.getToStatus()).isEqualTo("CANCELLED");
    assertThat(event.getContent()).isEqualTo(REASON);
    assertThat(event.getOperatorId()).isEqualTo(reporterA.getId());
  }

  /**
   * 暂停中的工单取消后必须清空 {@code paused_at}。
   *
   * <p>
   * 专门守住「updateById 跳过 null 字段」这个坑：状态更新与清空暂停标记是两次写入，
   * 少了后一次，终态工单会一直显示「暂停中」。
   * </p>
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("取消：暂停中的工单取消后清零 paused_at")
  void shouldClearPausedAtWhenCancellingPausedTicket() throws Exception {
    Ticket ticket = insertTicket("WAITING_CUSTOMER", reporterA.getId(), engineerA.getId(), teamA.getId());
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getPausedAt, LocalDateTime.now().withNano(0).minusHours(1)));

    mockMvc.perform(post("/tickets/{id}/cancel", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(REASON, 0))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"))
        .andExpect(jsonPath("$.data.sla.paused").value(false));

    assertThat(ticketMapper.selectById(ticket.getId()).getPausedAt()).isNull();
  }

  /**
   * 已派单的工单报修人也能取消（产品 7.1：未终结且未解决均可取消）。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("取消：报修人可取消已派单的工单")
  void shouldCancelAssignedTicket() throws Exception {
    Ticket ticket = insertTicket("ASSIGNED", reporterA.getId(), engineerA.getId(), teamA.getId());

    mockMvc.perform(post("/tickets/{id}/cancel", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(REASON, 0))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));
  }

  /**
   * 主管只能取消本团队工单。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：主管取消本团队成功，跨团队 404")
  void shouldLimitSupervisorToOwnTeam() throws Exception {
    Ticket inTeam = insertTicket("IN_PROGRESS", reporterB.getId(), engineerA.getId(), teamA.getId());
    Ticket otherTeam = insertTicket("IN_PROGRESS", reporterB.getId(), engineerB.getId(), teamB.getId());

    mockMvc.perform(post("/tickets/{id}/cancel", inTeam.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(REASON, 0))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));

    mockMvc.perform(post("/tickets/{id}/cancel", otherTeam.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(REASON, 0))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isNotFound());
  }

  /**
   * 管理员可取消任意未终结工单。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("取消：管理员可取消他人工单")
  void shouldAllowAdminCancelAnyTicket() throws Exception {
    Ticket ticket = insertTicket("ACCEPTED", reporterB.getId(), engineerB.getId(), teamB.getId());

    mockMvc.perform(post("/tickets/{id}/cancel", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(REASON, 0))
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELLED"));
  }

  /**
   * 工程师调用取消接口被拦截器拒绝，即使他是该单处理人。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("权限：工程师取消返回 403")
  void shouldForbidEngineer() throws Exception {
    Ticket ticket = insertTicket("ASSIGNED", reporterB.getId(), engineerA.getId(), teamA.getId());

    mockMvc.perform(post("/tickets/{id}/cancel", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(REASON, 0))
            .header("Authorization", bearerOf(engineerA, Role.ENGINEER)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  /**
   * RESOLVED 只能验收或重开，不能取消。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("状态：RESOLVED 取消返回 409")
  void shouldRejectResolvedTicket() throws Exception {
    Ticket ticket = insertTicket("RESOLVED", reporterA.getId(), engineerA.getId(), teamA.getId());

    mockMvc.perform(post("/tickets/{id}/cancel", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(REASON, 0))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("当前状态 RESOLVED 不可取消"));
  }

  /**
   * 终态不可再次取消，且不产生第二条事件。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("状态：已取消工单再取消返回 409")
  void shouldRejectCancelledTicket() throws Exception {
    Ticket ticket = insertTicket("CANCELLED", reporterA.getId(), null, null);

    mockMvc.perform(post("/tickets/{id}/cancel", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(REASON, 0))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    assertThat(ticketEventMapper.selectCount(Wrappers.<TicketEvent>lambdaQuery()
        .eq(TicketEvent::getTicketId, ticket.getId()))).isZero();
  }

  /**
   * 旧版本号提交返回 409，且状态不变。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("并发：旧 version 取消返回 409")
  void shouldRejectStaleVersion() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null);
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getVersion, 3));

    mockMvc.perform(post("/tickets/{id}/cancel", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(REASON, 0))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("工单已被他人修改，请刷新后重试"));

    assertThat(ticketMapper.selectById(ticket.getId()).getStatus()).isEqualTo("NEW");
  }

  /**
   * 原因为空白、版本缺失都返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("校验：原因为空或缺少 version 返回 400")
  void shouldRejectBlankReasonAndMissingVersion() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null);

    mockMvc.perform(post("/tickets/{id}/cancel", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("   ", 0))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    Map<String, Object> noVersion = new LinkedHashMap<>();
    noVersion.put("reason", REASON);
    mockMvc.perform(post("/tickets/{id}/cancel", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(noVersion))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 报修人取消他人工单返回 404，提示与「不存在」一致。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：报修人取消他人工单返回 404")
  void shouldReturn404WhenReporterCancelsOthers() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterB.getId(), null, null);

    mockMvc.perform(post("/tickets/{id}/cancel", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(REASON, 0))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("工单不存在"));
  }

  /**
   * 构造取消请求体。
   *
   * @param reason  取消原因
   * @param version 版本号
   * @return JSON 字符串
   * @throws Exception 序列化异常
   */
  private String body(String reason, int version) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", reason);
    body.put("version", version);
    return objectMapper.writeValueAsString(body);
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
   * 直接插入一个账号，绕过接口，用于造数。
   *
   * @param role   角色
   * @param teamId 团队 ID，可为 null
   * @return 插入后的账号实体
   */
  private SysUser insertUser(Role role, Long teamId) {
    SysUser user = new SysUser();
    user.setUsername("ut_cancel_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "取消测试账号");
    user.setRole(role.name());
    user.setTeamId(teamId);
    user.setStatus(1);
    sysUserMapper.insert(user);
    return user;
  }

  /**
   * 直接插入一个团队。
   *
   * @param name         团队名
   * @param supervisorId 主管 ID，可为 null
   * @return 插入后的团队实体
   */
  private Team insertTeam(String name, Long supervisorId) {
    Team team = new Team();
    team.setName(name + "-" + System.nanoTime());
    team.setSupervisorId(supervisorId);
    teamMapper.insert(team);
    return team;
  }

  /**
   * 直接插入一个客户。
   *
   * @param name 客户名
   * @return 插入后的客户实体
   */
  private Customer insertCustomer(String name) {
    Customer entity = new Customer();
    entity.setName(name + "-" + System.nanoTime());
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
   * 确保指定优先级存在一条启用策略，已存在则复用。
   *
   * @param priority 优先级
   * @return 策略实体
   */
  private SlaPolicy ensurePolicy(String priority) {
    SlaPolicy existing = slaPolicyMapper.selectOne(Wrappers.<SlaPolicy>lambdaQuery()
        .eq(SlaPolicy::getPriority, priority)
        .eq(SlaPolicy::getEnabled, 1)
        .last("LIMIT 1"));
    if (existing != null) {
      return existing;
    }
    SlaPolicy entity = new SlaPolicy();
    entity.setName(priority + " 取消测试策略-" + System.nanoTime());
    entity.setPriority(priority);
    entity.setResponseMinutes(30);
    entity.setResolveMinutes(240);
    entity.setEnabled(1);
    slaPolicyMapper.insert(entity);
    return entity;
  }

  /**
   * 直接插入一张工单。
   *
   * @param status     状态
   * @param reporterId 报修人 ID
   * @param assigneeId 处理人 ID，可为 null
   * @param teamId     团队 ID，可为 null
   * @return 插入后的工单实体
   */
  private Ticket insertTicket(String status, Long reporterId, Long assigneeId, Long teamId) {
    LocalDateTime now = LocalDateTime.now().withNano(0);
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
    ticket.setAssigneeId(assigneeId);
    ticket.setTeamId(teamId);
    ticket.setSlaPolicyId(policy.getId());
    ticket.setResponseDeadline(now.plusMinutes(30));
    ticket.setResolveDeadline(now.plusHours(4));
    ticket.setPausedTotalMinutes(0);
    ticket.setAiCorrected(0);
    ticketMapper.insert(ticket);
    return ticket;
  }
}
