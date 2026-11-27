package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketEvent;
import com.servicedesk.entity.enums.DeviceStatus;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TicketEventMapper;
import com.servicedesk.mapper.TicketMapper;
import com.servicedesk.service.SlaEscalationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA 超时升级的集成测试（S-03），覆盖开发文档 7.2 的三条规则。
 *
 * <p>
 * 属于业务层测试：启动完整上下文并走真实 MySQL，数据由用例自建并依赖 {@code @Transactional}
 * 回滚。每个用例前清空工单与事件表，使「扫描一轮升级了几条」可以精确断言，
 * 不受开发库种子数据影响。
 * </p>
 *
 * <p>
 * 重点守住四件事：响应与解决两类超时独立升级、重复扫描不重复写事件（幂等）、
 * 已响应 / 已解决 / 终态不升级、以及事件本身的系统事件语义（{@code operator_id} 为空）。
 * </p>
 */
@SpringBootTest
@Transactional
class SlaEscalationServiceImplTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 被测服务，直接调用 {@code escalateOverdue()}，不依赖真实的一分钟调度。 */
  @Autowired
  private SlaEscalationService slaEscalationService;

  /** 工单 Mapper，用于造数与断言升级标记。 */
  @Autowired
  private TicketMapper ticketMapper;

  /** 工单事件 Mapper，用于清表与断言 ESCALATE 事件。 */
  @Autowired
  private TicketEventMapper ticketEventMapper;

  /** 用户 Mapper。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** 客户 Mapper。 */
  @Autowired
  private CustomerMapper customerMapper;

  /** 设备 Mapper。 */
  @Autowired
  private DeviceMapper deviceMapper;

  /** 密码编码器。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** JSON 解析工具，用于读取事件 metadata。 */
  @Autowired
  private ObjectMapper objectMapper;

  /** 报修人，工单必填字段。 */
  private SysUser reporter;

  /** 工单所属客户。 */
  private Customer customer;

  /** 客户名下设备。 */
  private Device device;

  /**
   * 每个用例前清空工单与事件，保证扫描结果只包含本用例造的数据。
   */
  @BeforeEach
  void setUp() {
    ticketEventMapper.delete(null);
    ticketMapper.delete(null);
    reporter = insertUser();
    customer = insertCustomer();
    device = insertDevice(customer.getId());
  }

  /**
   * 未响应且过了响应截止：升级标记写入，并追加一条系统 ESCALATE 事件。
   *
   * @throws Exception JSON 解析异常
   */
  @Test
  @DisplayName("响应超时：写升级标记与 ESCALATE 事件")
  void shouldEscalateResponseOverdue() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("ASSIGNED", now.minusHours(1), now.plusHours(2));

    int escalated = slaEscalationService.escalateOverdue();

    assertThat(escalated).isEqualTo(1);
    Ticket updated = ticketMapper.selectById(ticket.getId());
    assertThat(updated.getResponseEscalatedAt()).isNotNull();
    assertThat(updated.getResolveEscalatedAt()).isNull();

    List<TicketEvent> events = escalateEventsOf(ticket.getId());
    assertThat(events).hasSize(1);
    TicketEvent event = events.get(0);
    assertThat(event.getType()).isEqualTo("ESCALATE");
    assertThat(event.getOperatorId()).isNull();
    assertThat(event.getContent()).contains("响应");

    JsonNode metadata = objectMapper.readTree(event.getMetadata());
    assertThat(metadata.path("escalationType").asText()).isEqualTo("RESPONSE");
    assertThat(metadata.path("overdueMinutes").asLong()).isGreaterThanOrEqualTo(59);
  }

  /**
   * 幂等：同一张单连续扫描两轮，第二轮不再升级、不再新增事件。
   */
  @Test
  @DisplayName("幂等：重复扫描只升级一次")
  void shouldEscalateOnlyOnce() {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("IN_PROGRESS", now.minusHours(1), now.plusHours(2));

    int first = slaEscalationService.escalateOverdue();
    int second = slaEscalationService.escalateOverdue();

    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();
    assertThat(escalateEventsOf(ticket.getId())).hasSize(1);
  }

  /**
   * 已响应：过了响应截止也不算响应超时，但解决时钟仍按自己的 deadline 判定。
   */
  @Test
  @DisplayName("已响应：不再升级响应超时")
  void shouldNotEscalateResponseAfterFirstResponse() {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("IN_PROGRESS", now.minusHours(1), now.plusHours(2));
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getFirstResponseAt, now.minusMinutes(90)));

    int escalated = slaEscalationService.escalateOverdue();

    assertThat(escalated).isZero();
    assertThat(ticketMapper.selectById(ticket.getId()).getResponseEscalatedAt()).isNull();
    assertThat(escalateEventsOf(ticket.getId())).isEmpty();
  }

  /**
   * 终态停止计时：已取消的工单即使两个 deadline 都过了也不升级。
   */
  @Test
  @DisplayName("终态：已取消工单不升级")
  void shouldNotEscalateTerminalTicket() {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("CANCELLED", now.minusHours(3), now.minusHours(2));

    int escalated = slaEscalationService.escalateOverdue();

    assertThat(escalated).isZero();
    Ticket updated = ticketMapper.selectById(ticket.getId());
    assertThat(updated.getResponseEscalatedAt()).isNull();
    assertThat(updated.getResolveEscalatedAt()).isNull();
    assertThat(escalateEventsOf(ticket.getId())).isEmpty();
  }

  /**
   * 解决超时：响应已在时限内完成，但解决 deadline 过了，只升级解决一类。
   *
   * @throws Exception JSON 解析异常
   */
  @Test
  @DisplayName("解决超时：只写 RESOLVE 事件")
  void shouldEscalateResolveOverdue() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("IN_PROGRESS", now.plusHours(1), now.minusHours(2));
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getFirstResponseAt, now.minusMinutes(30)));

    int escalated = slaEscalationService.escalateOverdue();

    assertThat(escalated).isEqualTo(1);
    Ticket updated = ticketMapper.selectById(ticket.getId());
    assertThat(updated.getResponseEscalatedAt()).isNull();
    assertThat(updated.getResolveEscalatedAt()).isNotNull();

    List<TicketEvent> events = escalateEventsOf(ticket.getId());
    assertThat(events).hasSize(1);
    JsonNode metadata = objectMapper.readTree(events.get(0).getMetadata());
    assertThat(metadata.path("escalationType").asText()).isEqualTo("RESOLVE");
    assertThat(events.get(0).getContent()).contains("解决");
  }

  /**
   * 同一张单响应与解决都超时：同一轮写两条事件，两类标记各自独立。
   */
  @Test
  @DisplayName("双超时：同一轮写两条 ESCALATE 事件")
  void shouldEscalateBothClocks() {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("ASSIGNED", now.minusHours(3), now.minusHours(2));

    int escalated = slaEscalationService.escalateOverdue();

    assertThat(escalated).isEqualTo(2);
    Ticket updated = ticketMapper.selectById(ticket.getId());
    assertThat(updated.getResponseEscalatedAt()).isNotNull();
    assertThat(updated.getResolveEscalatedAt()).isNotNull();
    assertThat(escalateEventsOf(ticket.getId())).hasSize(2);
  }

  /**
   * 两个 deadline 都还没到：不升级，不产生事件。
   */
  @Test
  @DisplayName("未到时限：不升级")
  void shouldNotEscalateBeforeDeadline() {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("NEW", now.plusMinutes(30), now.plusHours(4));

    int escalated = slaEscalationService.escalateOverdue();

    assertThat(escalated).isZero();
    assertThat(escalateEventsOf(ticket.getId())).isEmpty();
  }

  /**
   * 延迟队列路径：到点的响应超时单被升级，写标记与一条系统事件。
   *
   * @throws Exception JSON 解析异常
   */
  @Test
  @DisplayName("单条升级：响应到点写升级标记与事件")
  void shouldEscalateOneByQueueMessage() throws Exception {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("ASSIGNED", now.minusMinutes(1), now.plusHours(2));

    boolean escalated = slaEscalationService.escalateOne(ticket.getId(), "RESPONSE");

    assertThat(escalated).isTrue();
    Ticket updated = ticketMapper.selectById(ticket.getId());
    assertThat(updated.getResponseEscalatedAt()).isNotNull();
    assertThat(updated.getResolveEscalatedAt()).isNull();
    List<TicketEvent> events = escalateEventsOf(ticket.getId());
    assertThat(events).hasSize(1);
    assertThat(objectMapper.readTree(events.get(0).getMetadata()).path("escalationType").asText())
        .isEqualTo("RESPONSE");
  }

  /**
   * 延迟队列路径的幂等：同一条消息重复消费只有第一次成功，事件不重复。
   */
  @Test
  @DisplayName("单条升级：重复消费只升级一次")
  void shouldEscalateOneOnlyOnce() {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("IN_PROGRESS", now.minusMinutes(1), now.plusHours(2));

    boolean first = slaEscalationService.escalateOne(ticket.getId(), "RESPONSE");
    boolean second = slaEscalationService.escalateOne(ticket.getId(), "RESPONSE");

    assertThat(first).isTrue();
    assertThat(second).isFalse();
    assertThat(escalateEventsOf(ticket.getId())).hasSize(1);
  }

  /**
   * 消息早到：deadline 还没过时不升级，消息被安静丢弃。
   */
  @Test
  @DisplayName("单条升级：未到时限不升级")
  void shouldNotEscalateBeforeDeadlineByQueueMessage() {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("NEW", now.plusMinutes(30), now.plusHours(4));

    boolean escalated = slaEscalationService.escalateOne(ticket.getId(), "RESPONSE");

    assertThat(escalated).isFalse();
    assertThat(ticketMapper.selectById(ticket.getId()).getResponseEscalatedAt()).isNull();
    assertThat(escalateEventsOf(ticket.getId())).isEmpty();
  }

  /**
   * 已响应：响应类消息到点也抢不到升级权。
   */
  @Test
  @DisplayName("单条升级：已响应不升级响应超时")
  void shouldNotEscalateResponseAfterFirstResponseByQueueMessage() {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("IN_PROGRESS", now.minusMinutes(10), now.plusHours(2));
    ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
        .eq(Ticket::getId, ticket.getId())
        .set(Ticket::getFirstResponseAt, now.minusMinutes(5)));

    assertThat(slaEscalationService.escalateOne(ticket.getId(), "RESPONSE")).isFalse();
    assertThat(escalateEventsOf(ticket.getId())).isEmpty();
  }

  /**
   * 终态：解决类消息到点也不升级。
   */
  @Test
  @DisplayName("单条升级：终态不升级")
  void shouldNotEscalateTerminalTicketByQueueMessage() {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("CLOSED", now.minusHours(3), now.minusHours(2));

    assertThat(slaEscalationService.escalateOne(ticket.getId(), "RESOLVE")).isFalse();
    assertThat(ticketMapper.selectById(ticket.getId()).getResolveEscalatedAt()).isNull();
    assertThat(escalateEventsOf(ticket.getId())).isEmpty();
  }

  /**
   * 升级类型不在字典内：不升级、不写事件。
   */
  @Test
  @DisplayName("单条升级：类型非法不处理")
  void shouldRejectUnknownEscalationType() {
    LocalDateTime now = LocalDateTime.now().withNano(0);
    Ticket ticket = insertTicket("ASSIGNED", now.minusHours(1), now.minusHours(1));

    assertThat(slaEscalationService.escalateOne(ticket.getId(), "BOGUS")).isFalse();
    assertThat(escalateEventsOf(ticket.getId())).isEmpty();
  }

  /**
   * 工单不存在（建单事务回滚后的滞留消息）：记录日志并忽略，不抛异常。
   */
  @Test
  @DisplayName("单条升级：工单不存在不抛异常")
  void shouldIgnoreMissingTicket() {
    assertThat(slaEscalationService.escalateOne(-1L, "RESPONSE")).isFalse();
  }

  /**
   * 查询某张工单的 ESCALATE 事件，按写入顺序返回。
   *
   * @param ticketId 工单 ID
   * @return ESCALATE 事件列表
   */
  private List<TicketEvent> escalateEventsOf(Long ticketId) {
    return ticketEventMapper.selectList(Wrappers.<TicketEvent>lambdaQuery()
        .eq(TicketEvent::getTicketId, ticketId)
        .eq(TicketEvent::getType, "ESCALATE")
        .orderByAsc(TicketEvent::getId));
  }

  /**
   * 直接插入一个报修人账号，绕过接口，用于造数。
   *
   * @return 插入后的账号实体
   */
  private SysUser insertUser() {
    SysUser user = new SysUser();
    user.setUsername("ut_escalate_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName("超时升级测试账号");
    user.setRole(Role.REPORTER.name());
    user.setStatus(1);
    sysUserMapper.insert(user);
    return user;
  }

  /**
   * 直接插入一个客户。
   *
   * @return 插入后的客户实体
   */
  private Customer insertCustomer() {
    Customer entity = new Customer();
    entity.setName("九江精工机械");
    entity.setPhone("0792-0000000");
    entity.setLevel("NORMAL");
    customerMapper.insert(entity);
    return entity;
  }

  /**
   * 直接插入一台设备。
   *
   * @param customerId 所属客户 ID
   * @return 插入后的设备实体
   */
  private Device insertDevice(Long customerId) {
    Device entity = new Device();
    entity.setCustomerId(customerId);
    entity.setModel("AC-2000");
    entity.setSerialNo("SN-ESC-" + System.nanoTime());
    entity.setStatus(DeviceStatus.ACTIVE.name());
    deviceMapper.insert(entity);
    return entity;
  }

  /**
   * 直接插入一张工单。
   *
   * @param status           状态
   * @param responseDeadline 响应截止
   * @param resolveDeadline  解决截止
   * @return 插入后的工单实体
   */
  private Ticket insertTicket(String status, LocalDateTime responseDeadline,
      LocalDateTime resolveDeadline) {
    Ticket ticket = new Ticket();
    ticket.setTicketNo("TK" + System.nanoTime());
    ticket.setTitle("空压机运行异响");
    ticket.setDescription("开机 10 分钟后出现金属摩擦声，压力表波动。");
    ticket.setCategory("HARDWARE");
    ticket.setUrgency("P1");
    ticket.setStatus(status);
    ticket.setCustomerId(customer.getId());
    ticket.setDeviceId(device.getId());
    ticket.setReporterId(reporter.getId());
    ticket.setResponseDeadline(responseDeadline);
    ticket.setResolveDeadline(resolveDeadline);
    ticket.setPausedTotalMinutes(0);
    ticket.setAiCorrected(0);
    ticketMapper.insert(ticket);
    return ticket;
  }
}
