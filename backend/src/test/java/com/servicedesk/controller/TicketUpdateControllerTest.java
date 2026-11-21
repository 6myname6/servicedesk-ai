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
import com.servicedesk.entity.enums.DeviceStatus;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 编辑工单接口的集成测试（T-04）。
 *
 * <p>
 * 属于接口层测试：启动完整上下文，走真实拦截器、MySQL 与 MyBatis-Plus 乐观锁，
 * 数据由用例自建并依赖 {@code @Transactional} 回滚。重点守住五件事：
 * 角色与数据范围的两层拦截（403 / 404）、状态与版本冲突（409）、
 * 字段可选语义（不传保留原值、空更新不写库）、紧急度变化重算 SLA、暂停中平移暂停起点。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketUpdateControllerTest {

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

  /** 客户 A。 */
  private Customer customerA;

  /** 客户 B，用于构造跨客户设备。 */
  private Customer customerB;

  /** 客户 A 的设备。 */
  private Device deviceA;

  /** 客户 B 的设备，用于断言跨客户 400。 */
  private Device deviceB;

  /** P1 策略。 */
  private SlaPolicy p1;

  /** P2 策略，紧急度变化后应命中它。 */
  private SlaPolicy p2;

  /**
   * 每个用例前准备两套团队、客户、设备与五类账号，并保证 P1/P2 策略存在。
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

    customerA = insertCustomer("九江精工机械");
    customerB = insertCustomer("南昌重工");
    deviceA = insertDevice(customerA.getId(), "SN-A-" + System.nanoTime());
    deviceB = insertDevice(customerB.getId(), "SN-B-" + System.nanoTime());
    p1 = ensurePolicy("P1", 30, 240);
    p2 = ensurePolicy("P2", 120, 480);
  }

  /**
   * 报修人编辑自己的 NEW 工单：字段生效，version 自增 1。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("编辑：报修人改自己的 NEW 工单成功，version 加一")
  void shouldUpdateOwnNewTicket() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null, p1);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "空压机异响（已补充视频）");
    body.put("description", "开机 10 分钟后金属摩擦声，已上传视频。");
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.title").value("空压机异响（已补充视频）"))
        .andExpect(jsonPath("$.data.description").value("开机 10 分钟后金属摩擦声，已上传视频。"))
        .andExpect(jsonPath("$.data.version").value(1));
  }

  /**
   * 只传 title 时，分类、紧急度、设备与描述都必须保持原值。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("字段可选：只传 title，其余字段保持原值")
  void shouldKeepOtherFieldsWhenOnlyTitlePassed() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null, p1);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "只改标题");
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("只改标题"))
        .andExpect(jsonPath("$.data.description").value("开机 10 分钟后出现金属摩擦声，压力表波动。"))
        .andExpect(jsonPath("$.data.category").value("HARDWARE"))
        .andExpect(jsonPath("$.data.urgency").value("P1"))
        .andExpect(jsonPath("$.data.device.id").value(deviceA.getId()));
  }

  /**
   * 空更新不写库：version 与 updated_at 都不变。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("空更新：只带 version 时不写库，version 不变")
  void shouldNotWriteWhenNothingChanged() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null, p1);
    LocalDateTime updatedBefore = ticketMapper.selectById(ticket.getId()).getUpdatedAt();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(0));

    Ticket reloaded = ticketMapper.selectById(ticket.getId());
    assertThat(reloaded.getVersion()).isZero();
    assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedBefore);
  }

  /**
   * 用旧版本号提交返回 409，且不产生写入。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("并发：旧 version 提交返回 409")
  void shouldRejectStaleVersion() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null, p1);
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getVersion, 3));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "旧版本提交");
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("工单已被他人修改，请刷新后重试"));

    assertThat(ticketMapper.selectById(ticket.getId()).getTitle()).isEqualTo("空压机运行异响");
  }

  /**
   * 报修人对自己已派单的工单发起编辑返回 409。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("状态：报修人改已派单工单返回 409")
  void shouldRejectReporterEditAfterAssigned() throws Exception {
    Ticket ticket = insertTicket("ASSIGNED", reporterA.getId(), engineerA.getId(), teamA.getId(), p1);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "派单后还想改");
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("工单已派单，不能编辑"));
  }

  /**
   * 报修人改他人的工单：越权与不存在统一 404。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：报修人改他人工单返回 404")
  void shouldReturn404WhenReporterUpdatesOthers() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterB.getId(), null, null, p1);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "越权修改");
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("工单不存在"));
  }

  /**
   * 主管只能改本团队工单，改他团队返回 404。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：主管改本团队成功，跨团队 404")
  void shouldLimitSupervisorToOwnTeam() throws Exception {
    Ticket inTeam = insertTicket("IN_PROGRESS", reporterB.getId(), engineerA.getId(), teamA.getId(), p1);
    Ticket otherTeam = insertTicket("IN_PROGRESS", reporterB.getId(), engineerB.getId(), teamB.getId(), p1);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "主管改标题");
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", inTeam.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.title").value("主管改标题"));

    Map<String, Object> crossBody = new LinkedHashMap<>();
    crossBody.put("title", "跨团队修改");
    crossBody.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", otherTeam.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(crossBody))
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isNotFound());
  }

  /**
   * 工程师调用编辑接口被角色拦截器拒绝（403），即使他是该单处理人。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("权限：工程师调用编辑返回 403")
  void shouldForbidEngineer() throws Exception {
    Ticket ticket = insertTicket("ASSIGNED", reporterB.getId(), engineerA.getId(), teamA.getId(), p1);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "工程师改标题");
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(engineerA, Role.ENGINEER)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  /**
   * 终态工单任何人都不能编辑。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("状态：终态工单管理员也改不了，返回 409")
  void shouldRejectTerminalTicketForAdmin() throws Exception {
    Ticket ticket = insertTicket("CLOSED", reporterA.getId(), engineerA.getId(), teamA.getId(), p1);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "关闭后修改");
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("工单已结束，不能编辑"));
  }

  /**
   * 紧急度从 P1 改成 P2：换策略，两个 deadline 以本次请求时刻为基准重算。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("SLA：紧急度变化后按新策略从 now 重算 deadline")
  void shouldRebaseSlaWhenUrgencyChanged() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null, p1);
    LocalDateTime before = LocalDateTime.now().withNano(0).minusMinutes(1);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("urgency", "P2");
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.urgency").value("P2"))
        .andExpect(jsonPath("$.data.sla.policyId").value(p2.getId()));

    Ticket reloaded = ticketMapper.selectById(ticket.getId());
    assertThat(reloaded.getSlaPolicyId()).isEqualTo(p2.getId());
    assertThat(Duration.between(before, reloaded.getResponseDeadline()).toMinutes()).isBetween(119L, 121L);
    assertThat(Duration.between(before, reloaded.getResolveDeadline()).toMinutes()).isBetween(479L, 481L);
  }

  /**
   * 传了与库里相同的紧急度，不触发重算，也不写库。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("SLA：紧急度未变化时不重算 deadline")
  void shouldNotRebaseWhenUrgencyUnchanged() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null, p1);
    LocalDateTime responseBefore = ticket.getResponseDeadline();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("urgency", "P1");
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value(0));

    Ticket reloaded = ticketMapper.selectById(ticket.getId());
    assertThat(reloaded.getResponseDeadline()).isEqualTo(responseBefore);
    assertThat(reloaded.getSlaPolicyId()).isEqualTo(p1.getId());
  }

  /**
   * 暂停中改紧急度：暂停起点平移到本次请求时刻，累计暂停分钟不变。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("SLA：暂停中重算会把 paused_at 平移到 now")
  void shouldShiftPausedAtWhenRebasing() throws Exception {
    Ticket ticket = insertTicket("WAITING_CUSTOMER", reporterA.getId(), engineerA.getId(), teamA.getId(), p1);
    LocalDateTime pausedAt = LocalDateTime.now().withNano(0).minusHours(2);
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getPausedAt, pausedAt));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("urgency", "P2");
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sla.paused").value(true));

    Ticket reloaded = ticketMapper.selectById(ticket.getId());
    assertThat(reloaded.getPausedAt()).isAfter(pausedAt);
    assertThat(reloaded.getPausedTotalMinutes()).isZero();
  }

  /**
   * 设备不属于该工单的客户时返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("校验：设备不属于该客户返回 400")
  void shouldRejectDeviceOfAnotherCustomer() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null, p1);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("deviceId", deviceB.getId());
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("设备不存在或不属于该客户"));
  }

  /**
   * 标题传空白串返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("校验：标题为空白返回 400")
  void shouldRejectBlankTitle() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null, p1);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "   ");
    body.put("version", 0);

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("标题不能为空白"));
  }

  /**
   * 缺少 version 返回 400，由 {@code @NotNull} 拦截。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("校验：缺少 version 返回 400")
  void shouldRejectMissingVersion() throws Exception {
    Ticket ticket = insertTicket("NEW", reporterA.getId(), null, null, p1);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "没有版本号");

    mockMvc.perform(put("/tickets/{id}", ticket.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body))
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
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
    user.setUsername("ut_update_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "编辑测试账号");
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
   * @param priority        优先级
   * @param responseMinutes 响应分钟
   * @param resolveMinutes  解决分钟
   * @return 策略实体
   */
  private SlaPolicy ensurePolicy(String priority, int responseMinutes, int resolveMinutes) {
    SlaPolicy existing = slaPolicyMapper.selectOne(Wrappers.<SlaPolicy>lambdaQuery()
        .eq(SlaPolicy::getPriority, priority)
        .eq(SlaPolicy::getEnabled, 1)
        .last("LIMIT 1"));
    if (existing != null) {
      return existing;
    }
    SlaPolicy entity = new SlaPolicy();
    entity.setName(priority + " 测试策略-" + System.nanoTime());
    entity.setPriority(priority);
    entity.setResponseMinutes(responseMinutes);
    entity.setResolveMinutes(resolveMinutes);
    entity.setEnabled(1);
    slaPolicyMapper.insert(entity);
    return entity;
  }

  /**
   * 直接插入一张工单，deadline 按策略与当前时刻推算。
   *
   * @param status     状态
   * @param reporterId 报修人 ID
   * @param assigneeId 处理人 ID，可为 null
   * @param teamId     团队 ID，可为 null
   * @param policy     SLA 策略
   * @return 插入后的工单实体
   */
  private Ticket insertTicket(String status, Long reporterId, Long assigneeId, Long teamId,
      SlaPolicy policy) {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = new Ticket();
    ticket.setTicketNo("TK" + System.nanoTime());
    ticket.setTitle("空压机运行异响");
    ticket.setDescription("开机 10 分钟后出现金属摩擦声，压力表波动。");
    ticket.setCategory("HARDWARE");
    ticket.setUrgency(policy.getPriority());
    ticket.setStatus(status);
    ticket.setCustomerId(customerA.getId());
    ticket.setDeviceId(deviceA.getId());
    ticket.setReporterId(reporterId);
    ticket.setAssigneeId(assigneeId);
    ticket.setTeamId(teamId);
    ticket.setSlaPolicyId(policy.getId());
    ticket.setResponseDeadline(now.plusMinutes(policy.getResponseMinutes()));
    ticket.setResolveDeadline(now.plusMinutes(policy.getResolveMinutes()));
    ticket.setPausedTotalMinutes(0);
    ticket.setAiCorrected(0);
    ticketMapper.insert(ticket);
    return ticket;
  }
}
