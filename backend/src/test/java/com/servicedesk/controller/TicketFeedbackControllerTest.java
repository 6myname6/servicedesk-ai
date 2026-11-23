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
import com.servicedesk.entity.TicketFeedback;
import com.servicedesk.entity.enums.DeviceStatus;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
import com.servicedesk.mapper.TicketEventMapper;
import com.servicedesk.mapper.TicketFeedbackMapper;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单评价接口的集成测试（T-06）。
 *
 * <p>
 * 属于接口层测试：启动完整上下文，走真实拦截器与 MySQL，数据由用例自建并依赖
 * {@code @Transactional} 回滚。重点守住四件事：只有本人且已关闭才能评价（404 / 409）、
 * 一单一评、评分越界 400、其它角色一律 403。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketFeedbackControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

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

  /** 工单事件 Mapper，用于断言评价是否写时间线。 */
  @Autowired
  private TicketEventMapper ticketEventMapper;

  /** 回访 Mapper。 */
  @Autowired
  private TicketFeedbackMapper ticketFeedbackMapper;

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

  /** 工程师 A。 */
  private SysUser engineerA;

  /** 主管 A。 */
  private SysUser supervisorA;

  /** 管理员。 */
  private SysUser admin;

  /** 团队 A。 */
  private Team teamA;

  /** 客户。 */
  private Customer customer;

  /** 设备。 */
  private Device device;

  /** SLA 策略。 */
  private SlaPolicy policy;

  /**
   * 每个用例前准备账号、客户与设备。
   */
  @BeforeEach
  void setUp() {
    ticketMapper.delete(null);
    ticketFeedbackMapper.delete(null);
    reporterA = insertUser(Role.REPORTER, null);
    reporterB = insertUser(Role.REPORTER, null);
    engineerA = insertUser(Role.ENGINEER, null);
    supervisorA = insertUser(Role.SUPERVISOR, null);
    admin = insertUser(Role.ADMIN, null);

    teamA = insertTeam("华东售后一组", supervisorA.getId());
    engineerA.setTeamId(teamA.getId());
    sysUserMapper.updateById(engineerA);

    customer = insertCustomer("九江精工机械");
    device = insertDevice(customer.getId(), "SN-FEEDBACK-" + System.nanoTime());
    policy = ensurePolicy("P1");
  }

  /**
   * 报修人评价自己的已关闭工单，返回 A.20 的五个字段。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("评价：报修人评价已关闭工单成功")
  void shouldCreateFeedback() throws Exception {
    Ticket ticket = insertTicket("CLOSED", reporterA.getId());

    mockMvc.perform(post("/tickets/{id}/feedback", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(5, "响应很快，一次修好。"))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.ticketId").value(ticket.getId()))
        .andExpect(jsonPath("$.data.score").value(5))
        .andExpect(jsonPath("$.data.comment").value("响应很快，一次修好。"))
        .andExpect(jsonPath("$.data.createdAt").isNotEmpty());

    assertThat(ticketFeedbackMapper.selectCount(Wrappers.<TicketFeedback>lambdaQuery()
        .eq(TicketFeedback::getTicketId, ticket.getId()))).isEqualTo(1L);

    TicketEvent event = singleEvent(ticket.getId());
    assertThat(event.getType()).isEqualTo("FEEDBACK");
    assertThat(event.getOperatorId()).isEqualTo(reporterA.getId());
    assertThat(event.getContent()).isNull();
    assertThat(objectMapper.readTree(event.getMetadata()).path("score").asInt()).isEqualTo(5);
  }

  /**
   * 评价内容可选，不传时存 NULL。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("评价：不填内容也能提交")
  void shouldAllowEmptyComment() throws Exception {
    Ticket ticket = insertTicket("CLOSED", reporterA.getId());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("score", 4);
    mockMvc.perform(post("/tickets/{id}/feedback", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.comment").doesNotExist());

    TicketFeedback feedback = ticketFeedbackMapper.selectOne(Wrappers.<TicketFeedback>lambdaQuery()
        .eq(TicketFeedback::getTicketId, ticket.getId()));
    assertThat(feedback.getComment()).isNull();
  }

  /**
   * 未关闭的工单不能评价：处理中与已解决都在拒绝范围内。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("状态：未关闭工单评价返回 409")
  void shouldRejectUnclosedTicket() throws Exception {
    Ticket resolved = insertTicket("RESOLVED", reporterA.getId());
    mockMvc.perform(post("/tickets/{id}/feedback", resolved.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(5, null))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("工单未关闭，不能评价"));

    // 处理期间的沟通走私信（RC-002），不能借用评价接口，避免进行中的工单产生回访数据
    Ticket inProgress = insertTicket("IN_PROGRESS", reporterA.getId());
    mockMvc.perform(post("/tickets/{id}/feedback", inProgress.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(5, null))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("工单未关闭，不能评价"));
  }

  /**
   * 一单一评：第二次提交返回 409，不覆盖第一次。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("一单一评：重复评价返回 409")
  void shouldRejectDuplicateFeedback() throws Exception {
    Ticket ticket = insertTicket("CLOSED", reporterA.getId());

    mockMvc.perform(post("/tickets/{id}/feedback", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(5, "第一次"))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk());

    mockMvc.perform(post("/tickets/{id}/feedback", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(1, "第二次"))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("该工单已评价"));

    TicketFeedback feedback = ticketFeedbackMapper.selectOne(Wrappers.<TicketFeedback>lambdaQuery()
        .eq(TicketFeedback::getTicketId, ticket.getId()));
    assertThat(feedback.getScore()).isEqualTo(5);
    assertThat(eventCount(ticket.getId())).isEqualTo(1L);
  }

  /**
   * 评分越界由注解拦成 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("校验：评分 0 或 6 返回 400")
  void shouldRejectScoreOutOfRange() throws Exception {
    Ticket ticket = insertTicket("CLOSED", reporterA.getId());

    mockMvc.perform(post("/tickets/{id}/feedback", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(0, null))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    mockMvc.perform(post("/tickets/{id}/feedback", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(6, null))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 报修人评价他人工单返回 404，提示与「不存在」一致。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：评价他人工单返回 404")
  void shouldReturn404WhenFeedbackOthers() throws Exception {
    Ticket ticket = insertTicket("CLOSED", reporterB.getId());

    mockMvc.perform(post("/tickets/{id}/feedback", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(5, null))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("工单不存在"));
  }

  /**
   * 主管与管理员不能代评，接口级角色校验返回 403。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("权限：主管与管理员评价返回 403")
  void shouldForbidOtherRoles() throws Exception {
    Ticket ticket = insertTicket("CLOSED", reporterB.getId());

    mockMvc.perform(post("/tickets/{id}/feedback", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(5, null))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));

    mockMvc.perform(post("/tickets/{id}/feedback", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(5, null))
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  /**
   * 构造评价请求体。
   *
   * @param score   评分
   * @param comment 评价内容，可为 null
   * @return JSON 字符串
   * @throws Exception 序列化异常
   */
  private String body(int score, String comment) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("score", score);
    if (comment != null) {
      body.put("comment", comment);
    }
    return objectMapper.writeValueAsString(body);
  }

  /**
   * 取工单唯一的一条时间线事件。
   *
   * @param ticketId 工单 ID
   * @return 事件实体
   */
  private TicketEvent singleEvent(Long ticketId) {
    List<TicketEvent> events = ticketEventMapper.selectList(Wrappers.<TicketEvent>lambdaQuery()
        .eq(TicketEvent::getTicketId, ticketId));
    assertThat(events).hasSize(1);
    return events.get(0);
  }

  /**
   * 统计工单的时间线事件条数。
   *
   * @param ticketId 工单 ID
   * @return 事件条数
   */
  private long eventCount(Long ticketId) {
    return ticketEventMapper.selectCount(Wrappers.<TicketEvent>lambdaQuery()
        .eq(TicketEvent::getTicketId, ticketId));
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
    user.setUsername("ut_feedback_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "评价测试账号");
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
   * @param supervisorId 主管 ID
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
    entity.setName(priority + " 评价测试策略-" + System.nanoTime());
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
   * @return 插入后的工单实体
   */
  private Ticket insertTicket(String status, Long reporterId) {
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
    ticket.setSlaPolicyId(policy.getId());
    ticket.setResponseDeadline(now.plusMinutes(30));
    ticket.setResolveDeadline(now.plusHours(4));
    ticket.setPausedTotalMinutes(0);
    ticket.setAiCorrected(0);
    ticketMapper.insert(ticket);
    return ticket;
  }
}
