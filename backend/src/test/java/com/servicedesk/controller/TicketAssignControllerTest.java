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
 * 派单接口的集成测试（F-01）。
 *
 * <p>
 * 属于接口层测试：启动完整上下文，走真实拦截器、MySQL 与乐观锁，数据由用例自建并依赖
 * {@code @Transactional} 回滚。重点守住五件事：角色（403）、工单数据范围（404）、
 * 状态机（仅 NEW，其余 409）、被派人的四项校验（400）、
 * 派单后团队随工程师更新且时间线可见。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketAssignControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 派单备注样例。 */
  private static final String COMMENT = "张工熟悉该型号";

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

  /** 报修人。 */
  private SysUser reporter;

  /** 工程师 A，属于团队 A。 */
  private SysUser engineerA;

  /** 工程师 B，属于团队 B；团队 B 也由主管 A 管理。 */
  private SysUser engineerB;

  /** 工程师 C，属于团队 C；团队 C 无主管。 */
  private SysUser engineerC;

  /** 已停用工程师，属于团队 A。 */
  private SysUser disabledEngineer;

  /** 主管 A，管理团队 A 与团队 B。 */
  private SysUser supervisorA;

  /** 管理员。 */
  private SysUser admin;

  /** 团队 A。 */
  private Team teamA;

  /** 团队 B。 */
  private Team teamB;

  /** 团队 C。 */
  private Team teamC;

  /** 客户。 */
  private Customer customer;

  /** 设备。 */
  private Device device;

  /** SLA 策略。 */
  private SlaPolicy policy;

  /**
   * 每个用例前准备三个团队、五类账号与基础档案。
   */
  @BeforeEach
  void setUp() {
    ticketMapper.delete(null);
    ticketEventMapper.delete(null);

    supervisorA = insertUser(Role.SUPERVISOR, null, 1);
    admin = insertUser(Role.ADMIN, null, 1);
    reporter = insertUser(Role.REPORTER, null, 1);

    teamA = insertTeam("派单测试一组", supervisorA.getId());
    teamB = insertTeam("派单测试二组", supervisorA.getId());
    teamC = insertTeam("派单测试三组", null);

    engineerA = insertUser(Role.ENGINEER, teamA.getId(), 1);
    engineerB = insertUser(Role.ENGINEER, teamB.getId(), 1);
    engineerC = insertUser(Role.ENGINEER, teamC.getId(), 1);
    disabledEngineer = insertUser(Role.ENGINEER, teamA.getId(), 0);

    customer = insertCustomer("九江精工机械");
    device = insertDevice(customer.getId(), "SN-ASSIGN-" + System.nanoTime());
    policy = ensurePolicy("P1");
  }

  /**
   * 待派单池（NEW 且 team_id 为空）允许主管派给本团队工程师。
   *
   * <p>
   * 同时守住四个结果：状态 ASSIGNED、处理人与团队落库、版本自增、时间线写 ASSIGN。
   * </p>
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("派单：主管把待派单工单派给本团队工程师并写时间线")
  void shouldAssignPendingTicketToOwnTeamEngineer() throws Exception {
    Ticket ticket = insertTicket("NEW", reporter.getId(), null, null);

    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(engineerA.getId(), 0))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.data.assignee.id").value(engineerA.getId().intValue()))
        .andExpect(jsonPath("$.data.team.id").value(teamA.getId().intValue()))
        .andExpect(jsonPath("$.data.version").value(1));

    Ticket saved = ticketMapper.selectById(ticket.getId());
    assertThat(saved.getAssigneeId()).isEqualTo(engineerA.getId());
    assertThat(saved.getTeamId()).isEqualTo(teamA.getId());

    TicketEvent event = ticketEventMapper.selectOne(Wrappers.<TicketEvent>lambdaQuery()
        .eq(TicketEvent::getTicketId, ticket.getId()));
    assertThat(event).isNotNull();
    assertThat(event.getType()).isEqualTo("ASSIGN");
    assertThat(event.getFromStatus()).isEqualTo("NEW");
    assertThat(event.getToStatus()).isEqualTo("ASSIGNED");
    assertThat(event.getOperatorId()).isEqualTo(supervisorA.getId());
    assertThat(event.getContent()).contains(engineerA.getDisplayName()).contains(COMMENT);
  }

  /**
   * 主管管理多个团队时，可以改派给另一个团队的工程师，团队随之更新。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("派单：多团队主管改派后工单团队随工程师更新")
  void shouldUpdateTeamWhenSupervisorReassignsAcrossOwnTeams() throws Exception {
    Ticket ticket = insertTicket("NEW", reporter.getId(), engineerA.getId(), teamA.getId());

    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(engineerB.getId(), 0))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.assignee.id").value(engineerB.getId().intValue()))
        .andExpect(jsonPath("$.data.team.id").value(teamB.getId().intValue()));

    assertThat(ticketMapper.selectById(ticket.getId()).getTeamId()).isEqualTo(teamB.getId());
  }

  /**
   * 不能派给不属于主管管理团队的工程师。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("校验：跨团队工程师返回 400")
  void shouldRejectEngineerOfOtherTeam() throws Exception {
    Ticket ticket = insertTicket("NEW", reporter.getId(), null, null);

    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(engineerC.getId(), 0))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("只能派给本团队工程师"));

    assertThat(ticketMapper.selectById(ticket.getId()).getStatus()).isEqualTo("NEW");
  }

  /**
   * 被派人必须是 ENGINEER。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("校验：派给非工程师返回 400")
  void shouldRejectNonEngineer() throws Exception {
    Ticket ticket = insertTicket("NEW", reporter.getId(), null, null);

    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(reporter.getId(), 0))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("只能派给工程师"));
  }

  /**
   * 不存在或已停用的账号不能被派单。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("校验：工程师不存在或停用返回 400")
  void shouldRejectMissingOrDisabledEngineer() throws Exception {
    Ticket ticket = insertTicket("NEW", reporter.getId(), null, null);

    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(999_999_999L, 0))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("工程师不存在或已停用"));

    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(disabledEngineer.getId(), 0))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("工程师不存在或已停用"));
  }

  /**
   * 只有 NEW 可以派单。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("状态：已派单的工单再次派单返回 409")
  void shouldRejectAlreadyAssignedTicket() throws Exception {
    Ticket ticket = insertTicket("ASSIGNED", reporter.getId(), engineerA.getId(), teamA.getId());

    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(engineerA.getId(), 0))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("当前状态 ASSIGNED 不可派单"));

    assertThat(ticketEventMapper.selectCount(Wrappers.<TicketEvent>lambdaQuery()
        .eq(TicketEvent::getTicketId, ticket.getId()))).isZero();
  }

  /**
   * 旧版本号提交返回 409，且工单不被修改。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("并发：旧 version 派单返回 409")
  void shouldRejectStaleVersion() throws Exception {
    Ticket ticket = insertTicket("NEW", reporter.getId(), null, null);
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getVersion, 3));

    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(engineerA.getId(), 0))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("工单已被他人修改，请刷新后重试"));

    Ticket saved = ticketMapper.selectById(ticket.getId());
    assertThat(saved.getStatus()).isEqualTo("NEW");
    assertThat(saved.getAssigneeId()).isNull();
  }

  /**
   * 工程师与报修人调用派单接口被拦截器拒绝。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("权限：工程师与报修人派单返回 403")
  void shouldForbidNonSupervisor() throws Exception {
    Ticket ticket = insertTicket("NEW", reporter.getId(), null, null);

    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(engineerA.getId(), 0))
            .header("Authorization", bearerOf(engineerA, Role.ENGINEER)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(engineerA.getId(), 0))
            .header("Authorization", bearerOf(reporter, Role.REPORTER)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  /**
   * 主管不能给不属于自己团队的工单派单，越权与不存在统一 404。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：主管派其他团队工单返回 404")
  void shouldReturn404WhenAssigningOtherTeamTicket() throws Exception {
    Ticket ticket = insertTicket("NEW", reporter.getId(), engineerC.getId(), teamC.getId());

    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(engineerA.getId(), 0))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("工单不存在"));
  }

  /**
   * 管理员不受团队限制，可以派给任意团队的工程师。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("派单：管理员可跨团队派单")
  void shouldAllowAdminAssignAcrossTeams() throws Exception {
    Ticket ticket = insertTicket("NEW", reporter.getId(), engineerC.getId(), teamC.getId());

    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(engineerA.getId(), 0))
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.data.team.id").value(teamA.getId().intValue()));
  }

  /**
   * 缺少工程师或版本号返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("校验：缺 assigneeId 或 version 返回 400")
  void shouldRejectMissingRequiredFields() throws Exception {
    Ticket ticket = insertTicket("NEW", reporter.getId(), null, null);

    Map<String, Object> noAssignee = new LinkedHashMap<>();
    noAssignee.put("version", 0);
    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(noAssignee))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    Map<String, Object> noVersion = new LinkedHashMap<>();
    noVersion.put("assigneeId", engineerA.getId());
    mockMvc.perform(post("/tickets/{id}/assign", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(noVersion))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 构造派单请求体。
   *
   * @param assigneeId 工程师 ID
   * @param version    版本号
   * @return JSON 字符串
   * @throws Exception 序列化异常
   */
  private String body(Long assigneeId, int version) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("assigneeId", assigneeId);
    body.put("comment", COMMENT);
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
   * @param status 状态：1 启用 / 0 停用
   * @return 插入后的账号实体
   */
  private SysUser insertUser(Role role, Long teamId, int status) {
    SysUser user = new SysUser();
    user.setUsername("ut_assign_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "派单测试账号");
    user.setRole(role.name());
    user.setTeamId(teamId);
    user.setStatus(status);
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
    entity.setName(priority + " 派单测试策略-" + System.nanoTime());
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
