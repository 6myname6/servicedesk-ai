package com.servicedesk.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Team;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.enums.DeviceStatus;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单列表接口的集成测试（T-02）。
 *
 * <p>
 * 属于接口层测试：启动完整上下文，走真实拦截器与 MySQL，数据由用例自建并依赖
 * {@code @Transactional} 回滚。重点守住三件事：数据范围决定可见行（且影响 total）、
 * 过滤条件之间是「与」关系、名称与 SLA 剩余时间按当前页正确回填。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketListControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** JSON 解析工具。 */
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

  /** 密码编码器。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** 报修人 A：只能看到自己报的工单。 */
  private SysUser reporterA;

  /** 报修人 B：用于构造不可见数据。 */
  private SysUser reporterB;

  /** 工程师 A：属于团队 A。 */
  private SysUser engineerA;

  /** 工程师 B：属于团队 B。 */
  private SysUser engineerB;

  /** 主管 A：管理团队 A。 */
  private SysUser supervisorA;

  /** 管理员。 */
  private SysUser admin;

  /** 团队 A。 */
  private Team teamA;

  /** 团队 B。 */
  private Team teamB;

  /** 客户 A。 */
  private Customer customerA;

  /** 客户 B。 */
  private Customer customerB;

  /** 客户 A 的设备。 */
  private Device deviceA;

  /** 客户 B 的设备。 */
  private Device deviceB;

  /** 列表排序用的基准时刻，固定在过去，避免与种子数据混排。 */
  private LocalDateTime baseTime;

  /**
   * 每个用例前准备两套团队/客户/设备与五类账号。
   */
  @BeforeEach
  void setUp() {
    // 开发库有 200 张种子工单；清空后管理员视角的 total 才可断言，事务结束自动回滚
    ticketMapper.delete(null);
    baseTime = LocalDateTime.now().withNano(0).minusDays(30);
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
  }

  /**
   * 报修人只能看到自己提交的工单，且 total 只统计可见行；名称与 SLA 剩余时间正确回填。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：报修人只看到自己的工单，total 不含他人")
  void shouldPageReporterOwnTickets() throws Exception {
    Ticket own = insertTicket("空压机异响", "NEW", "P2", "HARDWARE",
        customerA.getId(), deviceA.getId(), reporterA.getId(), null, null,
        baseTime.plusHours(2), LocalDateTime.now().plusHours(1));
    insertTicket("他人设备漏水", "NEW", "P2", "OTHER",
        customerB.getId(), deviceB.getId(), reporterB.getId(), null, null,
        baseTime.plusHours(1), LocalDateTime.now().plusHours(1));

    MvcResult result = mockMvc.perform(get("/tickets")
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.records[0].id").value(own.getId()))
        .andExpect(jsonPath("$.data.records[0].customerName").value("九江精工机械"))
        .andExpect(jsonPath("$.data.records[0].deviceModel").value(deviceA.getModel()))
        .andExpect(jsonPath("$.data.records[0].slaBreached").value(false))
        .andReturn();

    JsonNode item = dataOf(result).path("records").get(0);
    assertThat(item.path("slaRemainingMinutes").asLong()).isBetween(1L, 60L);
  }

  /**
   * 工程师只看到指派给自己的工单，本团队未指派给他的也看不到。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：工程师只看到指派给自己的工单")
  void shouldPageEngineerAssignedTickets() throws Exception {
    Ticket assigned = insertTicket("派给我的单", "ASSIGNED", "P1", "HARDWARE",
        customerA.getId(), null, reporterA.getId(), engineerA.getId(), teamA.getId(),
        baseTime.plusHours(2), LocalDateTime.now().plusHours(4));
    insertTicket("派给同事的单", "ASSIGNED", "P1", "HARDWARE",
        customerA.getId(), null, reporterB.getId(), engineerB.getId(), teamB.getId(),
        baseTime.plusHours(1), LocalDateTime.now().plusHours(4));
    insertTicket("本团队待派单", "NEW", "P1", "HARDWARE",
        customerA.getId(), null, reporterB.getId(), null, null,
        baseTime.plusHours(3), LocalDateTime.now().plusHours(4));

    mockMvc.perform(get("/tickets")
            .header("Authorization", bearerOf(engineerA, Role.ENGINEER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.records[0].id").value(assigned.getId()))
        .andExpect(jsonPath("$.data.records[0].assigneeName").value(engineerA.getDisplayName()))
        .andExpect(jsonPath("$.data.records[0].teamName").value(teamA.getName()));
  }

  /**
   * 主管只看到本团队工单；未派单（team_id 为空）的单不属于任何团队，主管看不到。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：主管只看本团队，未派单的不在范围内")
  void shouldPageSupervisorTeamTickets() throws Exception {
    insertTicket("团队 A 单", "IN_PROGRESS", "P2", "SOFTWARE",
        customerA.getId(), null, reporterA.getId(), engineerA.getId(), teamA.getId(),
        baseTime.plusHours(3), LocalDateTime.now().plusHours(8));
    insertTicket("团队 A 单 2", "ASSIGNED", "P2", "SOFTWARE",
        customerA.getId(), null, reporterA.getId(), engineerA.getId(), teamA.getId(),
        baseTime.plusHours(2), LocalDateTime.now().plusHours(8));
    insertTicket("团队 B 单", "IN_PROGRESS", "P2", "SOFTWARE",
        customerB.getId(), null, reporterB.getId(), engineerB.getId(), teamB.getId(),
        baseTime.plusHours(1), LocalDateTime.now().plusHours(8));
    insertTicket("未派单", "NEW", "P2", "SOFTWARE",
        customerA.getId(), null, reporterA.getId(), null, null,
        baseTime.plusHours(4), LocalDateTime.now().plusHours(8));

    mockMvc.perform(get("/tickets")
            .header("Authorization", bearerOf(supervisorA, Role.SUPERVISOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2))
        .andExpect(jsonPath("$.data.records[0].title").value("团队 A 单"))
        .andExpect(jsonPath("$.data.records[1].title").value("团队 A 单 2"));
  }

  /**
   * 管理员不加行级条件，能看到全部工单。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("数据范围：管理员全量可见")
  void shouldPageAllForAdmin() throws Exception {
    insertTicket("单 1", "NEW", "P2", "OTHER", customerA.getId(), null, reporterA.getId(),
        null, null, baseTime.plusHours(3), LocalDateTime.now().plusHours(8));
    insertTicket("单 2", "NEW", "P2", "OTHER", customerB.getId(), null, reporterB.getId(),
        null, null, baseTime.plusHours(2), LocalDateTime.now().plusHours(8));
    insertTicket("单 3", "NEW", "P2", "OTHER", customerB.getId(), null, reporterB.getId(),
        null, null, baseTime.plusHours(1), LocalDateTime.now().plusHours(8));

    mockMvc.perform(get("/tickets")
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(3))
        .andExpect(jsonPath("$.data.records[0].title").value("单 1"));
  }

  /**
   * 关键字匹配标题或工单号。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("过滤：关键字匹配标题或工单号")
  void shouldFilterByKeyword() throws Exception {
    Ticket titleHit = insertTicket("空压机运行异响", "NEW", "P2", "OTHER",
        customerA.getId(), null, reporterA.getId(), null, null,
        baseTime.plusHours(3), LocalDateTime.now().plusHours(8));
    Ticket noHit = insertTicket("冷却塔漏水", "NEW", "P2", "OTHER",
        customerA.getId(), null, reporterA.getId(), null, null,
        baseTime.plusHours(2), LocalDateTime.now().plusHours(8));
    insertTicket("无关工单", "NEW", "P2", "OTHER",
        customerA.getId(), null, reporterA.getId(), null, null,
        baseTime.plusHours(1), LocalDateTime.now().plusHours(8));

    mockMvc.perform(get("/tickets")
            .param("keyword", "空压机")
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.records[0].id").value(titleHit.getId()));

    mockMvc.perform(get("/tickets")
            .param("keyword", noHit.getTicketNo())
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.records[0].id").value(noHit.getId()));
  }

  /**
   * 状态、紧急度、分类三个条件同时生效，且非法取值返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("过滤：状态/紧急度/分类组合，非法取值 400")
  void shouldFilterByStatusUrgencyAndCategory() throws Exception {
    Ticket hit = insertTicket("命中的单", "IN_PROGRESS", "P1", "HARDWARE",
        customerA.getId(), null, reporterA.getId(), null, null,
        baseTime.plusHours(3), LocalDateTime.now().plusHours(4));
    insertTicket("状态不符", "NEW", "P1", "HARDWARE", customerA.getId(), null,
        reporterA.getId(), null, null, baseTime.plusHours(2), LocalDateTime.now().plusHours(4));
    insertTicket("紧急度不符", "IN_PROGRESS", "P2", "HARDWARE", customerA.getId(), null,
        reporterA.getId(), null, null, baseTime.plusHours(1), LocalDateTime.now().plusHours(4));

    mockMvc.perform(get("/tickets")
            .param("status", "IN_PROGRESS")
            .param("urgency", "P1")
            .param("category", "HARDWARE")
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.records[0].id").value(hit.getId()));

    mockMvc.perform(get("/tickets")
            .param("status", "FOO")
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 按工程师与团队过滤。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("过滤：按工程师与团队")
  void shouldFilterByAssigneeAndTeam() throws Exception {
    insertTicket("工程师 A 的单", "ASSIGNED", "P1", "HARDWARE",
        customerA.getId(), null, reporterA.getId(), engineerA.getId(), teamA.getId(),
        baseTime.plusHours(3), LocalDateTime.now().plusHours(4));
    insertTicket("团队 B 的单", "ASSIGNED", "P1", "HARDWARE",
        customerB.getId(), null, reporterB.getId(), engineerB.getId(), teamB.getId(),
        baseTime.plusHours(2), LocalDateTime.now().plusHours(4));

    mockMvc.perform(get("/tickets")
            .param("assigneeId", String.valueOf(engineerA.getId()))
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.records[0].assigneeName").value(engineerA.getDisplayName()));

    mockMvc.perform(get("/tickets")
            .param("teamId", String.valueOf(teamB.getId()))
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.records[0].teamName").value(teamB.getName()));
  }

  /**
   * mine=true 表示「我报修或我处理」，是数据范围之上的追加条件。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("过滤：mine=true 取我报修或我处理")
  void shouldFilterMine() throws Exception {
    insertTicket("管理员报修", "NEW", "P2", "OTHER", customerA.getId(), null,
        admin.getId(), engineerA.getId(), teamA.getId(),
        baseTime.plusHours(3), LocalDateTime.now().plusHours(8));
    insertTicket("管理员处理", "NEW", "P2", "OTHER", customerA.getId(), null,
        reporterA.getId(), admin.getId(), null,
        baseTime.plusHours(2), LocalDateTime.now().plusHours(8));
    insertTicket("与我无关", "NEW", "P2", "OTHER", customerA.getId(), null,
        reporterA.getId(), engineerA.getId(), null,
        baseTime.plusHours(1), LocalDateTime.now().plusHours(8));

    mockMvc.perform(get("/tickets")
            .param("mine", "true")
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2));
  }

  /**
   * escalated=true 只看响应或解决任一已升级的工单。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("过滤：escalated=true 只看已升级")
  void shouldFilterEscalated() throws Exception {
    Ticket responseEscalated = insertTicket("响应超时", "ASSIGNED", "P0", "HARDWARE",
        customerA.getId(), null, reporterA.getId(), engineerA.getId(), teamA.getId(),
        baseTime.plusHours(3), LocalDateTime.now().plusHours(2));
    Ticket resolveEscalated = insertTicket("解决超时", "IN_PROGRESS", "P1", "HARDWARE",
        customerA.getId(), null, reporterA.getId(), engineerA.getId(), teamA.getId(),
        baseTime.plusHours(2), LocalDateTime.now().minusHours(1));
    insertTicket("未升级", "NEW", "P2", "OTHER", customerA.getId(), null,
        reporterA.getId(), null, null, baseTime.plusHours(1), LocalDateTime.now().plusHours(8));

    markEscalated(responseEscalated.getId(), true, false);
    markEscalated(resolveEscalated.getId(), false, true);

    mockMvc.perform(get("/tickets")
            .param("escalated", "true")
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2));
  }

  /**
   * 分页参数生效：total 与可见行一致，size 控制返回条数，倒序稳定。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("分页：size 与 total 正确，按创建时间倒序")
  void shouldPageWithSizeAndTotal() throws Exception {
    insertTicket("单 1", "NEW", "P2", "OTHER", customerA.getId(), null, reporterA.getId(),
        null, null, baseTime.plusHours(3), LocalDateTime.now().plusHours(8));
    insertTicket("单 2", "NEW", "P2", "OTHER", customerA.getId(), null, reporterA.getId(),
        null, null, baseTime.plusHours(2), LocalDateTime.now().plusHours(8));
    insertTicket("单 3", "NEW", "P2", "OTHER", customerA.getId(), null, reporterA.getId(),
        null, null, baseTime.plusHours(1), LocalDateTime.now().plusHours(8));

    mockMvc.perform(get("/tickets")
            .param("page", "1")
            .param("size", "2")
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(3))
        .andExpect(jsonPath("$.data.pages").value(2))
        .andExpect(jsonPath("$.data.records.length()").value(2))
        .andExpect(jsonPath("$.data.records[0].title").value("单 1"))
        .andExpect(jsonPath("$.data.records[1].title").value("单 2"));

    mockMvc.perform(get("/tickets")
            .param("page", "2")
            .param("size", "2")
            .header("Authorization", bearerOf(admin, Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.records.length()").value(1))
        .andExpect(jsonPath("$.data.records[0].title").value("单 3"));
  }

  /**
   * 解决 SLA 已超时的工单：剩余分钟为负，slaBreached 为 true。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("SLA：超时工单剩余为负且标记超时")
  void shouldReturnNegativeRemainingWhenBreached() throws Exception {
    insertTicket("已超时", "IN_PROGRESS", "P2", "OTHER", customerA.getId(), null,
        reporterA.getId(), engineerA.getId(), teamA.getId(),
        baseTime.plusHours(1), LocalDateTime.now().minusHours(2));

    MvcResult result = mockMvc.perform(get("/tickets")
            .header("Authorization", bearerOf(reporterA, Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.records[0].slaBreached").value(true))
        .andReturn();

    long remaining = dataOf(result).path("records").get(0).path("slaRemainingMinutes").asLong();
    assertThat(remaining).isNegative();
  }

  /**
   * 未登录返回 401。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("权限：未登录返回 401")
  void shouldRequireLogin() throws Exception {
    mockMvc.perform(get("/tickets").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  /**
   * 解析响应体中的 {@code data} 节点。
   *
   * @param result MockMvc 结果
   * @return data 节点
   * @throws Exception JSON 解析异常
   */
  private JsonNode dataOf(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .path("data");
  }

  /**
   * 标记工单的升级时间，用于 escalated 过滤用例。
   *
   * @param ticketId         工单 ID
   * @param responseEscalated 是否标记响应升级
   * @param resolveEscalated  是否标记解决升级
   */
  private void markEscalated(Long ticketId, boolean responseEscalated, boolean resolveEscalated) {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticketId)
        .set(responseEscalated, Ticket::getResponseEscalatedAt, now)
        .set(resolveEscalated, Ticket::getResolveEscalatedAt, now));
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
   * @return 插入后的账号实体（含自增 ID）
   */
  private SysUser insertUser(Role role, Long teamId) {
    SysUser user = new SysUser();
    user.setUsername("ut_list_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "列表测试账号");
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
    Customer customer = new Customer();
    customer.setName(name);
    customer.setPhone("0792-" + System.nanoTime() % 1_000_000);
    customer.setLevel("NORMAL");
    customerMapper.insert(customer);
    return customer;
  }

  /**
   * 直接插入一台设备。
   *
   * @param customerId 所属客户 ID
   * @param serialNo   序列号
   * @return 插入后的设备实体
   */
  private Device insertDevice(Long customerId, String serialNo) {
    Device device = new Device();
    device.setCustomerId(customerId);
    device.setModel("AC-2000");
    device.setSerialNo(serialNo);
    device.setStatus(DeviceStatus.ACTIVE.name());
    deviceMapper.insert(device);
    return device;
  }

  /**
   * 直接插入一张工单，工单号用时间戳保证唯一。
   *
   * @param title           标题
   * @param status          状态
   * @param urgency         紧急度
   * @param category        分类
   * @param customerId      客户 ID
   * @param deviceId        设备 ID，可为 null
   * @param reporterId      报修人 ID
   * @param assigneeId      处理人 ID，可为 null
   * @param teamId          团队 ID，可为 null
   * @param createdAt       创建时间
   * @param resolveDeadline 解决截止时间
   * @return 插入后的工单实体
   */
  private Ticket insertTicket(String title, String status, String urgency, String category,
      Long customerId, Long deviceId, Long reporterId, Long assigneeId, Long teamId,
      LocalDateTime createdAt, LocalDateTime resolveDeadline) {
    Ticket ticket = new Ticket();
    ticket.setTicketNo("TK" + System.nanoTime());
    ticket.setTitle(title);
    ticket.setDescription("列表测试描述");
    ticket.setStatus(status);
    ticket.setUrgency(urgency);
    ticket.setCategory(category);
    ticket.setCustomerId(customerId);
    ticket.setDeviceId(deviceId);
    ticket.setReporterId(reporterId);
    ticket.setAssigneeId(assigneeId);
    ticket.setTeamId(teamId);
    ticket.setResponseDeadline(createdAt.plusMinutes(30));
    ticket.setResolveDeadline(resolveDeadline);
    ticket.setPausedTotalMinutes(0);
    ticket.setAiCorrected(0);
    ticket.setCreatedAt(createdAt);
    ticketMapper.insert(ticket);
    return ticket;
  }
}
