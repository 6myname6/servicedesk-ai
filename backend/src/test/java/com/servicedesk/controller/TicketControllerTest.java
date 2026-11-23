package com.servicedesk.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketAttachment;
import com.servicedesk.entity.TicketEvent;
import com.servicedesk.entity.enums.DeviceStatus;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TicketAttachmentMapper;
import com.servicedesk.mapper.TicketEventMapper;
import com.servicedesk.mapper.TicketMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 建单接口的集成测试（T-01）。
 *
 * <p>
 * 属于接口层测试：启动完整上下文，走真实拦截器、MySQL 与 Redis（工单号日序列），
 * 数据由用例自建并依赖 {@code @Transactional} 回滚；Redis 序列键在 {@code @AfterEach} 手工清理。
 * </p>
 *
 * <p>
 * 重点覆盖四类风险：工单号与 SLA deadline 的算法口径、客户/设备逻辑外键校验、
 * 附件「本人且未关联」的归属约束、以及无启用 SLA 策略时的 503 降级。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TicketControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 工单号格式：TK + yyyyMMdd + 6 位日序列。 */
  private static final String TICKET_NO_PATTERN = "TK\\d{14}";

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** JSON 解析与构造工具。 */
  @Autowired
  private ObjectMapper objectMapper;

  /** 用于直接签发指定角色的 token，绕开登录流程。 */
  @Autowired
  private JwtUtil jwtUtil;

  /** 业务时钟，用于清理当日 Redis 序列键。 */
  @Autowired
  private Clock clock;

  /** Redis 模板，仅用于清理工单号序列键（不参与数据库事务）。 */
  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  /** 工单 Mapper。 */
  @Autowired
  private TicketMapper ticketMapper;

  /** 附件 Mapper。 */
  @Autowired
  private TicketAttachmentMapper attachmentMapper;

  /** 工单事件 Mapper，用于断言建单与挂附件是否写时间线。 */
  @Autowired
  private TicketEventMapper ticketEventMapper;

  /** 客户 Mapper。 */
  @Autowired
  private CustomerMapper customerMapper;

  /** 设备 Mapper。 */
  @Autowired
  private DeviceMapper deviceMapper;

  /** SLA 策略 Mapper。 */
  @Autowired
  private SlaPolicyMapper slaPolicyMapper;

  /** 用户 Mapper。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** 密码编码器，保证造出的账号密码列符合 BCrypt 存储约定。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** 本次用例的报修人（建单人）。 */
  private SysUser reporter;

  /** 本次用例的另一个报修人，用于附件越权断言。 */
  private SysUser otherReporter;

  /** 本次用例的客户。 */
  private Customer customer;

  /** 归属该客户的设备。 */
  private Device device;

  /**
   * 每个用例前准备账号、客户、设备，并确保 P1/P2/P3 策略存在。
   */
  @BeforeEach
  void setUp() {
    reporter = insertUser(Role.REPORTER);
    otherReporter = insertUser(Role.REPORTER);
    customer = insertCustomer("九江精工机械");
    device = insertDevice(customer.getId(), "SN-TICKET-" + System.nanoTime());
    ensurePolicy("P1", 30, 240);
    ensurePolicy("P2", 120, 480);
    ensurePolicy("P3", 480, 1440);
  }

  /**
   * 清理当日工单号序列键：Redis 不参与数据库事务，回滚不会退号，需用例自行清理。
   */
  @AfterEach
  void tearDown() {
    String datePart = LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    stringRedisTemplate.delete("ticket:seq:" + datePart);
  }

  /**
   * 只传必填字段：分类默认 OTHER、紧急度默认 P2，状态 NEW，两个 deadline 按 P2 策略计算。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("建单：默认分类/紧急度，按 P2 策略写 deadline")
  void shouldCreateTicketWithDefaults() throws Exception {
    SlaPolicy policy = requireEnabledPolicy("P2");

    MvcResult result = mockMvc.perform(post("/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("空压机运行异响", "开机 10 分钟后金属摩擦声", customer.getId(), null, null, null, null))
            .header("Authorization", bearerOf(reporter)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.id").isNumber())
        .andExpect(jsonPath("$.data.ticketNo").value(org.hamcrest.Matchers.matchesPattern(TICKET_NO_PATTERN)))
        .andExpect(jsonPath("$.data.status").value("NEW"))
        .andExpect(jsonPath("$.data.category").value("OTHER"))
        .andExpect(jsonPath("$.data.urgency").value("P2"))
        .andExpect(jsonPath("$.data.customer.name").value("九江精工机械"))
        .andExpect(jsonPath("$.data.reporter.displayName").value("REPORTER测试账号"))
        .andExpect(jsonPath("$.data.assignee").value(nullValue()))
        .andExpect(jsonPath("$.data.team").value(nullValue()))
        .andExpect(jsonPath("$.data.ai").value(nullValue()))
        .andExpect(jsonPath("$.data.attachments.length()").value(0))
        .andExpect(jsonPath("$.data.sla.policyId").value(policy.getId()))
        .andExpect(jsonPath("$.data.sla.responseRemainingMinutes").value(policy.getResponseMinutes()))
        .andExpect(jsonPath("$.data.sla.resolveRemainingMinutes").value(policy.getResolveMinutes()))
        .andReturn();

    JsonNode data = dataOf(result);
    assertDeadlineGap(data, policy);

    Ticket saved = ticketMapper.selectOne(Wrappers.<Ticket>lambdaQuery()
        .eq(Ticket::getTicketNo, data.path("ticketNo").asText()));
    assertThat(saved).isNotNull();
    assertThat(saved.getStatus()).isEqualTo("NEW");
    assertThat(saved.getReporterId()).isEqualTo(reporter.getId());
    assertThat(saved.getDeviceId()).isNull();
    assertThat(saved.getSlaPolicyId()).isEqualTo(policy.getId());
    assertThat(saved.getPausedTotalMinutes()).isZero();
    assertThat(saved.getAiCorrected()).isZero();

    TicketEvent event = singleEvent(saved.getId());
    assertThat(event.getType()).isEqualTo("CREATE");
    assertThat(event.getFromStatus()).isNull();
    assertThat(event.getToStatus()).isEqualTo("NEW");
    assertThat(event.getOperatorId()).isEqualTo(reporter.getId());
    assertThat(event.getContent()).isNull();
    assertThat(event.getMetadata()).isNull();
  }

  /**
   * 显式传分类、紧急度与设备：deadline 按 P1 策略计算，设备快照回填序列号。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("建单：显式分类/紧急度/设备，按 P1 策略写 deadline")
  void shouldCreateTicketWithExplicitFieldsAndDevice() throws Exception {
    SlaPolicy policy = requireEnabledPolicy("P1");

    MvcResult result = mockMvc.perform(post("/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("空压机压力异常", "压力表持续波动", customer.getId(), device.getId(),
                "HARDWARE", "P1", null))
            .header("Authorization", bearerOf(reporter)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.category").value("HARDWARE"))
        .andExpect(jsonPath("$.data.urgency").value("P1"))
        .andExpect(jsonPath("$.data.device.serialNo").value(device.getSerialNo()))
        .andExpect(jsonPath("$.data.sla.policyName").value(policy.getName()))
        .andReturn();

    assertDeadlineGap(dataOf(result), policy);
  }

  /**
   * 标题为空白时返回 400，不落库。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("建单：标题为空返回 400")
  void shouldRejectBlankTitle() throws Exception {
    mockMvc.perform(post("/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("  ", "描述", customer.getId(), null, null, null, null))
            .header("Authorization", bearerOf(reporter)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 客户不存在时返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("建单：客户不存在返回 400")
  void shouldRejectUnknownCustomer() throws Exception {
    mockMvc.perform(post("/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("空压机异响", "描述", 999_999_999L, null, null, null, null))
            .header("Authorization", bearerOf(reporter)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("客户不存在"));
  }

  /**
   * 设备不属于所选客户时返回 400，避免工单把客户与设备挂错。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("建单：设备不属于客户返回 400")
  void shouldRejectDeviceOfAnotherCustomer() throws Exception {
    Customer otherCustomer = insertCustomer("南昌重工");
    Device foreignDevice = insertDevice(otherCustomer.getId(), "SN-FOREIGN-" + System.nanoTime());

    mockMvc.perform(post("/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("空压机异响", "描述", customer.getId(), foreignDevice.getId(), null, null, null))
            .header("Authorization", bearerOf(reporter)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("设备不存在或不属于该客户"));
  }

  /**
   * 分类不在字典内时返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("建单：非法分类返回 400")
  void shouldRejectInvalidCategory() throws Exception {
    mockMvc.perform(post("/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("空压机异响", "描述", customer.getId(), null, "FOO", null, null))
            .header("Authorization", bearerOf(reporter)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 紧急度没有启用策略时返回 503：这是配置问题，不能静默生成无 deadline 的工单。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("建单：无启用 SLA 策略返回 503")
  void shouldReturn503WithoutEnabledPolicy() throws Exception {
    slaPolicyMapper.update(null, Wrappers.<SlaPolicy>lambdaUpdate()
        .eq(SlaPolicy::getPriority, "P3")
        .set(SlaPolicy::getEnabled, 0));

    mockMvc.perform(post("/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("咨询耗材更换", "描述", customer.getId(), null, null, "P3", null))
            .header("Authorization", bearerOf(reporter)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503))
        .andExpect(jsonPath("$.message").value("SLA 策略未配置，请联系管理员"));

    assertThat(countReporterTickets()).isZero();
  }

  /**
   * 附件回填：响应带附件，且库里 ticket_id 指向新工单。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("建单：回填本人未关联附件")
  void shouldLinkUploadedAttachments() throws Exception {
    TicketAttachment first = insertAttachment(reporter.getId(), null, "https://cdn.example.com/a1.jpg");
    TicketAttachment second = insertAttachment(reporter.getId(), null, "https://cdn.example.com/a2.jpg");

    MvcResult result = mockMvc.perform(post("/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("空压机异响", "带两张图", customer.getId(), null, null, null,
                List.of(first.getFileUrl(), second.getFileUrl())))
            .header("Authorization", bearerOf(reporter)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.attachments.length()").value(2))
        .andExpect(jsonPath("$.data.attachments[0].fileUrl").value(first.getFileUrl()))
        .andReturn();

    Long ticketId = dataOf(result).path("id").asLong();
    assertThat(attachmentMapper.selectById(first.getId()).getTicketId()).isEqualTo(ticketId);
    assertThat(attachmentMapper.selectById(second.getId()).getTicketId()).isEqualTo(ticketId);

    List<TicketEvent> events = eventsOf(ticketId);
    assertThat(events).hasSize(3);
    assertThat(events.get(0).getType()).isEqualTo("CREATE");
    TicketEvent firstAttach = events.get(1);
    TicketEvent secondAttach = events.get(2);
    assertThat(firstAttach.getType()).isEqualTo("ATTACH");
    assertThat(objectMapper.readTree(firstAttach.getMetadata()).path("action").asText())
        .isEqualTo("upload");
    assertThat(objectMapper.readTree(firstAttach.getMetadata()).path("attachmentId").asLong())
        .isEqualTo(first.getId());
    assertThat(objectMapper.readTree(firstAttach.getMetadata()).path("fileName").asText())
        .isEqualTo("photo.jpg");
    assertThat(objectMapper.readTree(secondAttach.getMetadata()).path("attachmentId").asLong())
        .isEqualTo(second.getId());
  }

  /**
   * 他人上传的附件不能被挂到自己的工单上。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("建单：引用他人附件返回 400 且不建单")
  void shouldRejectOthersAttachment() throws Exception {
    TicketAttachment other = insertAttachment(otherReporter.getId(), null, "https://cdn.example.com/other.jpg");

    mockMvc.perform(post("/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("空压机异响", "描述", customer.getId(), null, null, null,
                List.of(other.getFileUrl())))
            .header("Authorization", bearerOf(reporter)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("附件不存在或不可用"));

    assertThat(countReporterTickets()).isZero();
  }

  /**
   * 已关联工单的附件不能再次使用。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("建单：引用已关联附件返回 400")
  void shouldRejectAlreadyLinkedAttachment() throws Exception {
    TicketAttachment linked = insertAttachment(reporter.getId(), 999_999L, "https://cdn.example.com/used.jpg");

    mockMvc.perform(post("/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("空压机异响", "描述", customer.getId(), null, null, null,
                List.of(linked.getFileUrl())))
            .header("Authorization", bearerOf(reporter)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("附件不存在或不可用"));
  }

  /**
   * 超过 5 个附件在入参校验阶段返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("建单：附件超过 5 个返回 400")
  void shouldRejectMoreThanFiveAttachments() throws Exception {
    List<String> urls = List.of("https://cdn.example.com/1.jpg", "https://cdn.example.com/2.jpg",
        "https://cdn.example.com/3.jpg", "https://cdn.example.com/4.jpg",
        "https://cdn.example.com/5.jpg", "https://cdn.example.com/6.jpg");

    mockMvc.perform(post("/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("空压机异响", "描述", customer.getId(), null, null, null, urls))
            .header("Authorization", bearerOf(reporter)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 未登录返回 401。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("建单：未登录返回 401")
  void shouldRequireLogin() throws Exception {
    mockMvc.perform(post("/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("空压机异响", "描述", customer.getId(), null, null, null, null)))
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
    String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    return objectMapper.readTree(content).path("data");
  }

  /**
   * 断言两个 deadline 与 createdAt 的差值等于策略配置的分钟数。
   *
   * @param data   响应 data 节点
   * @param policy 命中的策略
   */
  private void assertDeadlineGap(JsonNode data, SlaPolicy policy) {
    LocalDateTime createdAt = LocalDateTime.parse(data.path("createdAt").asText());
    LocalDateTime responseDeadline = LocalDateTime.parse(data.path("sla").path("responseDeadline").asText());
    LocalDateTime resolveDeadline = LocalDateTime.parse(data.path("sla").path("resolveDeadline").asText());
    assertThat(Duration.between(createdAt, responseDeadline).toMinutes())
        .isEqualTo(policy.getResponseMinutes().longValue());
    assertThat(Duration.between(createdAt, resolveDeadline).toMinutes())
        .isEqualTo(policy.getResolveMinutes().longValue());
  }

  /**
   * 统计当前报修人名下的工单数，用于断言失败请求没有建单。
   *
   * @return 工单数
   */
  private long countReporterTickets() {
    return ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
        .eq(Ticket::getReporterId, reporter.getId()));
  }

  /**
   * 为指定用户与角色签发鉴权请求头。
   *
   * @param user 账号
   * @return 形如 {@code Bearer xxx} 的请求头取值
   */
  private String bearerOf(SysUser user) {
    LoginUser loginUser = new LoginUser(user.getId(), user.getUsername(), user.getDisplayName(),
        user.getRole(), user.getTeamId());
    return "Bearer " + jwtUtil.generate(loginUser);
  }

  /**
   * 构造建单请求体，值为 null 的字段照样提交，用于验证必填校验。
   *
   * @param title       标题
   * @param description 描述
   * @param customerId  客户 ID
   * @param deviceId    设备 ID，可为 null
   * @param category    分类，可为 null
   * @param urgency     紧急度，可为 null
   * @param imageUrls   附件 URL，可为 null
   * @return JSON 字符串
   * @throws Exception JSON 序列化异常
   */
  private String body(String title, String description, Long customerId, Long deviceId,
      String category, String urgency, List<String> imageUrls) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", title);
    body.put("description", description);
    body.put("customerId", customerId);
    body.put("deviceId", deviceId);
    body.put("category", category);
    body.put("urgency", urgency);
    body.put("imageUrls", imageUrls);
    return objectMapper.writeValueAsString(body);
  }

  /**
   * 查询启用中的策略；不存在则补一条（测试事务内，结束即回滚）。
   *
   * @param priority 紧急度
   * @return 启用中的策略
   */
  private SlaPolicy requireEnabledPolicy(String priority) {
    SlaPolicy policy = slaPolicyMapper.selectOne(Wrappers.<SlaPolicy>lambdaQuery()
        .eq(SlaPolicy::getPriority, priority)
        .eq(SlaPolicy::getEnabled, 1));
    assertThat(policy).as("SLA 策略 %s 应存在", priority).isNotNull();
    return policy;
  }

  /**
   * 确保策略存在，避免用例依赖种子数据的具体时限。
   *
   * @param priority        紧急度
   * @param responseMinutes 响应时限（分钟）
   * @param resolveMinutes  解决时限（分钟）
   */
  private void ensurePolicy(String priority, int responseMinutes, int resolveMinutes) {
    SlaPolicy existing = slaPolicyMapper.selectOne(Wrappers.<SlaPolicy>lambdaQuery()
        .eq(SlaPolicy::getPriority, priority));
    if (existing != null) {
      return;
    }
    SlaPolicy policy = new SlaPolicy();
    policy.setName(priority + " 测试策略");
    policy.setPriority(priority);
    policy.setResponseMinutes(responseMinutes);
    policy.setResolveMinutes(resolveMinutes);
    policy.setEnabled(1);
    slaPolicyMapper.insert(policy);
  }

  /**
   * 直接插入一条账号，绕过接口，用于造数。
   *
   * @param role 角色
   * @return 插入后的账号实体（含自增 ID）
   */
  private SysUser insertUser(Role role) {
    SysUser user = new SysUser();
    user.setUsername("ut_ticket_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "测试账号");
    user.setRole(role.name());
    user.setStatus(1);
    sysUserMapper.insert(user);
    return user;
  }

  /**
   * 直接插入一个客户，绕过接口，用于造数。
   *
   * @param name 客户名
   * @return 插入后的客户实体（含自增 ID）
   */
  private Customer insertCustomer(String name) {
    Customer entity = new Customer();
    entity.setName(name);
    entity.setPhone("0792-" + System.nanoTime() % 1_000_000);
    entity.setLevel("NORMAL");
    customerMapper.insert(entity);
    return entity;
  }

  /**
   * 直接插入一台设备，绕过接口，用于造数。
   *
   * @param customerId 所属客户 ID
   * @param serialNo   序列号
   * @return 插入后的设备实体（含自增 ID）
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
   * 查询工单的全部时间线事件，按主键正序。
   *
   * @param ticketId 工单 ID
   * @return 事件列表
   */
  private List<TicketEvent> eventsOf(Long ticketId) {
    return ticketEventMapper.selectList(Wrappers.<TicketEvent>lambdaQuery()
        .eq(TicketEvent::getTicketId, ticketId)
        .orderByAsc(TicketEvent::getId));
  }

  /**
   * 取工单唯一的一条时间线事件。
   *
   * @param ticketId 工单 ID
   * @return 事件实体
   */
  private TicketEvent singleEvent(Long ticketId) {
    List<TicketEvent> events = eventsOf(ticketId);
    assertThat(events).hasSize(1);
    return events.get(0);
  }

  /**
   * 直接插入一条附件记录，绕过上传接口，用于建单关联用例。
   *
   * @param uploaderId 上传人 ID
   * @param ticketId   已关联工单 ID，未关联时为 null
   * @param fileUrl    附件 URL
   * @return 插入后的附件实体（含自增 ID）
   */
  private TicketAttachment insertAttachment(Long uploaderId, Long ticketId, String fileUrl) {
    TicketAttachment attachment = new TicketAttachment();
    attachment.setTicketId(ticketId);
    attachment.setUploaderId(uploaderId);
    attachment.setFileName("photo.jpg");
    attachment.setFileUrl(fileUrl);
    attachment.setFileSize(1024L);
    attachment.setMimeType("image/jpeg");
    attachment.setCreatedAt(LocalDateTime.now());
    attachmentMapper.insert(attachment);
    return attachment;
  }
}
