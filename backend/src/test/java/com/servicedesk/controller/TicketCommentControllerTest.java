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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 处理记录 / 内部备注与时间线的集成测试（F-03、F-04，按 RC-004 不分页）。
 *
 * <p>
 * 属于接口层测试：启动完整上下文，走真实拦截器、MySQL 与 JSON 表达式，数据由用例自建并依赖
 * {@code @Transactional} 回滚。重点守住六件事：写入权限与内部备注越权（400）、终态拒绝（409）、
 * 数据范围（404）、首响补写取最早、内部备注对报修人不可见、时间线正序且硬上限 500 条。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketCommentControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 公开处理记录样例。 */
  private static final String PUBLIC_CONTENT = "已按手册第 3.2 节更换轴承，试机 30 分钟正常。";

  /** 内部备注样例。 */
  private static final String INTERNAL_CONTENT = "客户报价偏高，先按保修处理。";

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

  /** 工单事件 Mapper，用于断言时间线与构造历史事件。 */
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

  /** 报修人，时间线可见性测试的工单归属人。 */
  private SysUser reporter;

  /** 另一个报修人，用于验证报修人的数据范围。 */
  private SysUser otherReporter;

  /** 工程师 A，团队 A 的处理人。 */
  private SysUser engineerA;

  /** 工程师 B，团队 A 的另一个工程师，用于验证「只能操作指派给自己的单」。 */
  private SysUser engineerB;

  /** 主管 A，管理团队 A。 */
  private SysUser supervisorA;

  /** 管理员。 */
  private SysUser admin;

  /** 团队 A。 */
  private Team teamA;

  /** 团队 B，无人管理，用于验证主管跨团队 404。 */
  private Team teamB;

  /** 客户。 */
  private Customer customer;

  /** 设备。 */
  private Device device;

  /** 解决时限 240 分钟的 SLA 策略。 */
  private SlaPolicy policy;

  /**
   * 每个用例前准备两个团队、五类账号与基础档案。
   */
  @BeforeEach
  void setUp() {
    supervisorA = insertUser(Role.SUPERVISOR, null);
    admin = insertUser(Role.ADMIN, null);
    reporter = insertUser(Role.REPORTER, null);
    otherReporter = insertUser(Role.REPORTER, null);

    teamA = insertTeam("处理记录测试一组", supervisorA.getId());
    teamB = insertTeam("处理记录测试二组", null);

    engineerA = insertUser(Role.ENGINEER, teamA.getId());
    engineerB = insertUser(Role.ENGINEER, teamA.getId());

    customer = insertCustomer("九江精工机械");
    device = insertDevice(customer.getId(), "SN-COMMENT-" + System.nanoTime());
    policy = ensurePolicy("P1");
  }

  /**
   * 工程师写公开处理记录：返回 COMMENT 事件并补写首次响应时间。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("处理记录：工程师写公开记录返回 COMMENT 并补写首响")
  void shouldAddPublicCommentByEngineer() throws Exception {
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId());

    postComment(ticket.getId(), engineerA, Role.ENGINEER, PUBLIC_CONTENT, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.type").value("COMMENT"))
        .andExpect(jsonPath("$.data.content").value(PUBLIC_CONTENT))
        .andExpect(jsonPath("$.data.internal").value(false))
        .andExpect(jsonPath("$.data.operatorId").value(engineerA.getId()))
        .andExpect(jsonPath("$.data.operatorRole").value("ENGINEER"))
        .andExpect(jsonPath("$.data.metadata.internal").value(false));

    Ticket saved = ticketMapper.selectById(ticket.getId());
    assertThat(saved.getFirstResponseAt()).isNotNull();
    assertThat(saved.getVersion()).isZero();

    TicketEvent event = singleEvent(ticket.getId());
    assertThat(event.getType()).isEqualTo("COMMENT");
    assertThat(event.getFromStatus()).isNull();
    assertThat(event.getToStatus()).isNull();
    assertThat(event.getOperatorId()).isEqualTo(engineerA.getId());
    assertThat(event.getContent()).isEqualTo(PUBLIC_CONTENT);
    assertThat(objectMapper.readTree(event.getMetadata()).path("internal").asBoolean()).isFalse();
  }

  /**
   * 首响取最早：已有 first_response_at 时，处理记录不覆盖更早的值。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("处理记录：已有首响时不覆盖更早的值")
  void shouldKeepEarlierFirstResponse() throws Exception {
    LocalDateTime respondedAt = LocalDateTime.now().withNano(0).minusHours(1);
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId(),
        respondedAt);

    postComment(ticket.getId(), engineerA, Role.ENGINEER, PUBLIC_CONTENT, false)
        .andExpect(status().isOk());

    assertThat(ticketMapper.selectById(ticket.getId()).getFirstResponseAt()).isEqualTo(respondedAt);
  }

  /**
   * 报修人可以写对客记录，但不补写首次响应时间。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("处理记录：报修人可写公开记录且不补首响")
  void shouldAllowReporterToWritePublicComment() throws Exception {
    Ticket ticket = insertTicket("NEW", reporter.getId(), null, null);

    postComment(ticket.getId(), reporter, Role.REPORTER, "设备在车间二层，白天都有人。", false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.type").value("COMMENT"))
        .andExpect(jsonPath("$.data.operatorRole").value("REPORTER"))
        .andExpect(jsonPath("$.data.internal").value(false));

    Ticket saved = ticketMapper.selectById(ticket.getId());
    assertThat(saved.getFirstResponseAt()).isNull();
    assertThat(eventCount(ticket.getId())).isEqualTo(1);
  }

  /**
   * 报修人写内部备注返回 400，且不落事件。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("内部备注：报修人写入返回 400")
  void shouldRejectInternalCommentFromReporter() throws Exception {
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId());

    postComment(ticket.getId(), reporter, Role.REPORTER, INTERNAL_CONTENT, true)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("报修人不能写内部备注"));

    assertThat(eventCount(ticket.getId())).isZero();
  }

  /**
   * 工程师与主管都能写内部备注，metadata 记录 internal=true。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("内部备注：工程师与主管可写，metadata 标记 internal=true")
  void shouldAddInternalCommentByEngineerAndSupervisor() throws Exception {
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId());

    postComment(ticket.getId(), engineerA, Role.ENGINEER, INTERNAL_CONTENT, true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.internal").value(true))
        .andExpect(jsonPath("$.data.metadata.internal").value(true));

    postComment(ticket.getId(), supervisorA, Role.SUPERVISOR, "主管补充：优先保生产。", true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.operatorRole").value("SUPERVISOR"))
        .andExpect(jsonPath("$.data.internal").value(true));

    List<TicketEvent> events = ticketEventMapper.selectList(Wrappers.<TicketEvent>lambdaQuery()
        .eq(TicketEvent::getTicketId, ticket.getId()));
    assertThat(events).hasSize(2);
    for (TicketEvent event : events) {
      assertThat(objectMapper.readTree(event.getMetadata()).path("internal").asBoolean()).isTrue();
    }
  }

  /**
   * 内部备注对报修人不可见，工程师可见全部。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("时间线：报修人看不到内部备注")
  void shouldHideInternalCommentFromReporter() throws Exception {
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId());

    postComment(ticket.getId(), engineerA, Role.ENGINEER, "对客记录", false)
        .andExpect(status().isOk());
    postComment(ticket.getId(), engineerA, Role.ENGINEER, "内部备注", true)
        .andExpect(status().isOk());

    mockMvc.perform(get("/tickets/{id}/events", ticket.getId())
            .header("Authorization", bearerOf(reporter, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].content").value("对客记录"))
        .andExpect(jsonPath("$.data[0].internal").value(false));

    mockMvc.perform(get("/tickets/{id}/events", ticket.getId())
            .header("Authorization", bearerOf(engineerA, Role.ENGINEER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[1].content").value("内部备注"))
        .andExpect(jsonPath("$.data[1].internal").value(true));
  }

  /**
   * 三个终态（CLOSED / REJECTED / CANCELLED）都不可再写处理记录，返回 409 且不落事件。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("处理记录：终态工单返回 409")
  void shouldRejectCommentOnFinishedTicket() throws Exception {
    Ticket closed = insertTicket("CLOSED", reporter.getId(), engineerA.getId(), teamA.getId());
    postComment(closed.getId(), engineerA, Role.ENGINEER, PUBLIC_CONTENT, false)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("工单已结束，不能追加处理记录"));
    assertThat(eventCount(closed.getId())).isZero();

    Ticket rejected = insertTicket("REJECTED", reporter.getId(), engineerA.getId(), teamA.getId());
    postComment(rejected.getId(), engineerA, Role.ENGINEER, PUBLIC_CONTENT, false)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("工单已结束，不能追加处理记录"));
    assertThat(eventCount(rejected.getId())).isZero();

    Ticket cancelled = insertTicket("CANCELLED", reporter.getId(), null, null);
    postComment(cancelled.getId(), reporter, Role.REPORTER, PUBLIC_CONTENT, false)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("工单已结束，不能追加处理记录"));
    assertThat(eventCount(cancelled.getId())).isZero();
  }

  /**
   * 空白正文与超过 2000 字的正文都返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("校验：空白或超长正文返回 400")
  void shouldRejectBlankAndTooLongContent() throws Exception {
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId());

    postComment(ticket.getId(), engineerA, Role.ENGINEER, "   \n  ", false)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    postComment(ticket.getId(), engineerA, Role.ENGINEER, "字".repeat(2001), false)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));

    assertThat(eventCount(ticket.getId())).isZero();
  }

  /**
   * 数据范围外写处理记录统一 404。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：写处理记录越权与不存在统一 404")
  void shouldReturn404ForInvisibleTicket() throws Exception {
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId());

    postComment(ticket.getId(), otherReporter, Role.REPORTER, PUBLIC_CONTENT, false)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("工单不存在"));
    postComment(ticket.getId(), engineerB, Role.ENGINEER, PUBLIC_CONTENT, false)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("工单不存在"));

    Ticket otherTeam = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamB.getId());
    postComment(otherTeam.getId(), supervisorA, Role.SUPERVISOR, PUBLIC_CONTENT, false)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("工单不存在"));

    assertThat(eventCount(ticket.getId())).isZero();
    assertThat(eventCount(otherTeam.getId())).isZero();
  }

  /**
   * 时间线按 created_at 正序，同一时刻按 id 正序。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("时间线：按时间与 ID 正序返回")
  void shouldOrderTimelineByCreatedAtAndId() throws Exception {
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId());
    LocalDateTime second = LocalDateTime.now().withNano(0);
    LocalDateTime first = second.minusHours(1);

    insertEvent(ticket.getId(), "CREATE", reporter.getId(), "同一时刻一", "{\"internal\":false}", second);
    insertEvent(ticket.getId(), "START", engineerA.getId(), "更早", "{\"internal\":false}", first);
    insertEvent(ticket.getId(), "COMMENT", engineerA.getId(), "同一时刻二", "{\"internal\":false}", second);

    mockMvc.perform(get("/tickets/{id}/events", ticket.getId())
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(3))
        .andExpect(jsonPath("$.data[0].content").value("更早"))
        .andExpect(jsonPath("$.data[1].content").value("同一时刻一"))
        .andExpect(jsonPath("$.data[2].content").value("同一时刻二"))
        .andExpect(jsonPath("$.data[0].operatorRole").value("ENGINEER"));
  }

  /**
   * 时间线不再接受分页参数：带 page/size 也返回完整数组。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("时间线：分页参数被忽略，直接返回数组")
  void shouldIgnorePagingParameters() throws Exception {
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId());
    LocalDateTime base = LocalDateTime.now().withNano(0);
    insertEvent(ticket.getId(), "CREATE", reporter.getId(), "事件1", "{\"internal\":false}", base);
    insertEvent(ticket.getId(), "START", engineerA.getId(), "事件2", "{\"internal\":false}",
        base.plusSeconds(1));

    mockMvc.perform(get("/tickets/{id}/events", ticket.getId())
            .param("page", "2")
            .param("size", "1")
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].content").value("事件1"));
  }

  /**
   * 时间线硬上限 500 条：送入 501 条时只返回最近 500 条，最早的被丢弃。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("时间线：超过 500 条时只返回最近 500 条")
  void shouldCapTimelineAt500() throws Exception {
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId());
    LocalDateTime base = LocalDateTime.now().withNano(0).minusHours(2);
    for (int i = 1; i <= 501; i++) {
      insertEvent(ticket.getId(), "COMMENT", engineerA.getId(), "事件" + i,
          "{\"internal\":false}", base.plusSeconds(i));
    }

    mockMvc.perform(get("/tickets/{id}/events", ticket.getId())
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(500))
        .andExpect(jsonPath("$.data[0].content").value("事件2"))
        .andExpect(jsonPath("$.data[499].content").value("事件501"));
  }

  /**
   * 数据范围外读时间线统一 404。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("时间线：越权读取与不存在统一 404")
  void shouldReturn404ForInvisibleTimeline() throws Exception {
    Ticket ticket = insertTicket("IN_PROGRESS", reporter.getId(), engineerA.getId(), teamA.getId());

    mockMvc.perform(get("/tickets/{id}/events", ticket.getId())
            .header("Authorization", bearerOf(otherReporter, Role.REPORTER)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("工单不存在"));

    mockMvc.perform(get("/tickets/{id}/events", ticket.getId())
            .header("Authorization", bearerOf(engineerB, Role.ENGINEER)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("工单不存在"));
  }

  /**
   * 发起一次追加处理记录请求。
   *
   * @param ticketId 工单 ID
   * @param user     操作人
   * @param role     操作人角色
   * @param content  正文
   * @param internal 是否内部备注
   * @return MockMvc 结果，供断言链继续使用
   * @throws Exception MockMvc 调用异常
   */
  private ResultActions postComment(Long ticketId, SysUser user, Role role, String content,
      boolean internal) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("content", content);
    body.put("internal", internal);
    return mockMvc.perform(post("/tickets/{id}/comments", ticketId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(body))
        .header("Authorization", bearerOf(user, role)));
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
   * 直接插入一条时间线事件，用于构造排序与上限场景。
   *
   * @param ticketId   工单 ID
   * @param type       事件类型
   * @param operatorId 操作人 ID，可为 null
   * @param content    正文
   * @param metadata   metadata JSON 原文
   * @param createdAt  发生时间
   * @return 插入后的事件实体
   */
  private TicketEvent insertEvent(Long ticketId, String type, Long operatorId, String content,
      String metadata, LocalDateTime createdAt) {
    TicketEvent event = new TicketEvent();
    event.setTicketId(ticketId);
    event.setType(type);
    event.setOperatorId(operatorId);
    event.setContent(content);
    event.setMetadata(metadata);
    event.setCreatedAt(createdAt);
    ticketEventMapper.insert(event);
    return event;
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
    user.setUsername("ut_comment_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "处理记录测试账号");
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
    entity.setName(priority + " 处理记录测试策略-" + System.nanoTime());
    entity.setPriority(priority);
    entity.setResponseMinutes(30);
    entity.setResolveMinutes(240);
    entity.setEnabled(1);
    slaPolicyMapper.insert(entity);
    return entity;
  }

  /**
   * 直接插入一张没有首响时间的工单。
   *
   * @param status     状态
   * @param reporterId 报修人 ID
   * @param assigneeId 处理人 ID，可为 null
   * @param teamId     团队 ID，可为 null
   * @return 插入后的工单实体
   */
  private Ticket insertTicket(String status, Long reporterId, Long assigneeId, Long teamId) {
    return insertTicket(status, reporterId, assigneeId, teamId, null);
  }

  /**
   * 直接插入一张工单，并指定首次响应时间。
   *
   * @param status          状态
   * @param reporterId      报修人 ID
   * @param assigneeId      处理人 ID，可为 null
   * @param teamId          团队 ID，可为 null
   * @param firstResponseAt 首次响应时间，可为 null
   * @return 插入后的工单实体
   */
  private Ticket insertTicket(String status, Long reporterId, Long assigneeId, Long teamId,
      LocalDateTime firstResponseAt) {
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
    ticket.setResolveDeadline(now.plusMinutes(240));
    ticket.setFirstResponseAt(firstResponseAt);
    ticket.setPausedTotalMinutes(0);
    ticket.setAiCorrected(0);
    ticketMapper.insert(ticket);
    return ticket;
  }
}
