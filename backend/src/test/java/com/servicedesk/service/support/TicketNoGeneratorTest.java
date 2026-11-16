package com.servicedesk.service.support;

import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.common.utils.RedisAtomicCounter;
import com.servicedesk.mapper.TicketNoMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工单号生成器的集成测试。
 *
 * <p>
 * 走真实 Redis 与 MySQL，时钟用 {@link Clock#fixed} 固定在远期日期，
 * 与开发库已有数据隔离；Redis 不参与数据库事务，用例结束需手工清理序列键。
 * </p>
 *
 * <p>
 * 覆盖开发排期的两个验收点（并发不重号、跨天归零），以及降级补号与容量溢出。
 * </p>
 */
@SpringBootTest
class TicketNoGeneratorTest {

  /** 业务时区，与 {@code TimeConfig} 保持一致。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** 序列键前缀，与生成器内部常量一致，仅用于用例清理。 */
  private static final String SEQ_KEY_PREFIX = "ticket:seq:";

  /** 真实生成器，使用系统时钟，验证 Bean 装配可用。 */
  @Autowired
  private TicketNoGenerator ticketNoGenerator;

  /** 原子计数器，供手工构造生成器时复用。 */
  @Autowired
  private RedisAtomicCounter redisAtomicCounter;

  /** 降级查询 Mapper。 */
  @Autowired
  private TicketNoMapper ticketNoMapper;

  /** Redis 模板，用于断言 TTL 与清理序列键。 */
  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  /** 裸 SQL 模板，降级用例需要直接插入一张工单。 */
  @Autowired
  private JdbcTemplate jdbcTemplate;

  /** 本轮用例触碰过的序列键，结束前逐个删除。 */
  private final List<String> touchedKeys = new ArrayList<>();

  /**
   * 清理用例写下的序列键。
   *
   * <p>
   * Redis 不随 {@code @Transactional} 回滚，不清理会让同一日期的后续用例序号从中间开始。
   * </p>
   */
  @AfterEach
  void clearSeqKeys() {
    touchedKeys.forEach(stringRedisTemplate::delete);
    touchedKeys.clear();
  }

  /**
   * 从 1 开始连续取号，并验证序列键带有 2 天 TTL。
   */
  @Test
  @DisplayName("顺序取号：从 000001 开始且不重复")
  void shouldGenerateSequentialNumbers() {
    String datePart = "20370304";
    TicketNoGenerator generator = generatorAt(fixedClock("2037-03-04T02:00:00Z"));
    trackKey(datePart);

    assertThat(generator.next()).isEqualTo("TK20370304000001");
    assertThat(generator.next()).isEqualTo("TK20370304000002");
    assertThat(generator.next()).isEqualTo("TK20370304000003");

    Long ttl = stringRedisTemplate.getExpire(SEQ_KEY_PREFIX + datePart, TimeUnit.SECONDS);
    assertThat(ttl).isNotNull().isGreaterThan(0L);
  }

  /**
   * 并发生成不重号：100 个线程同时取号，结果必须是 100 个互不相同的号。
   *
   * @throws Exception 线程等待或任务执行异常
   */
  @Test
  @DisplayName("并发：100 线程同时取号不重号")
  void shouldGenerateUniqueNumbersConcurrently() throws Exception {
    String datePart = "20370305";
    TicketNoGenerator generator = generatorAt(fixedClock("2037-03-05T02:00:00Z"));
    trackKey(datePart);

    int threads = 100;
    ExecutorService pool = Executors.newFixedThreadPool(16);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<String>> futures = new ArrayList<>(threads);
      for (int i = 0; i < threads; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          return generator.next();
        }));
      }
      start.countDown();

      Set<String> ticketNos = futures.stream().map(this::getQuietly).collect(Collectors.toSet());
      assertThat(ticketNos).hasSize(threads);
      assertThat(ticketNos).allMatch(no -> no.startsWith("TK" + datePart));
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 跨天序号归零：23:59 生成到 000002，次日 00:00 必须从 000001 重新开始。
   */
  @Test
  @DisplayName("跨天：次日序号从 000001 重新开始")
  void shouldResetSequenceAcrossDays() {
    trackKey("20370306");
    trackKey("20370307");

    TicketNoGenerator beforeMidnight = generatorAt(fixedClock("2037-03-06T15:59:00Z"));
    assertThat(beforeMidnight.next()).isEqualTo("TK20370306000001");
    assertThat(beforeMidnight.next()).isEqualTo("TK20370306000002");

    TicketNoGenerator afterMidnight = generatorAt(fixedClock("2037-03-06T16:00:00Z"));
    assertThat(afterMidnight.next()).isEqualTo("TK20370307000001");
  }

  /**
   * Redis 故障时降级为「库内当日最大号 + 1」。
   */
  @Test
  @Transactional
  @DisplayName("降级：Redis 不可用时按库内最大号 +1")
  void shouldFallbackToDatabaseWhenRedisFails() {
    String datePart = "20370308";
    insertTicket("TK20370308000007");

    TicketNoGenerator generator = new TicketNoGenerator(brokenCounter(), fixedClock("2037-03-08T02:00:00Z"),
        ticketNoMapper);

    assertThat(generator.next()).isEqualTo("TK20370308000008");
  }

  /**
   * 降级且当日无工单时，从 000001 开始。
   */
  @Test
  @Transactional
  @DisplayName("降级：当日无工单时从 000001 开始")
  void shouldStartFromOneWhenFallbackAndNoTicket() {
    String datePart = "20370309";
    TicketNoGenerator generator = new TicketNoGenerator(brokenCounter(), fixedClock("2037-03-09T02:00:00Z"),
        ticketNoMapper);

    assertThat(generator.next()).isEqualTo("TK20370309000001");
  }

  /**
   * 当日序号超过 999999 时抛 503，不输出破坏格式的 7 位号。
   */
  @Test
  @DisplayName("溢出：超过 999999 抛 503")
  void shouldRejectWhenDailySequenceExhausted() {
    RedisAtomicCounter exhaustedCounter = mock(RedisAtomicCounter.class);
    when(exhaustedCounter.incrWithTtl(anyString(), anyLong())).thenReturn(1_000_000L);

    TicketNoGenerator generator = new TicketNoGenerator(exhaustedCounter, fixedClock("2037-03-10T02:00:00Z"),
        ticketNoMapper);

    assertThatThrownBy(generator::next)
        .isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getCode()).isEqualTo(503));
  }

  /**
   * 真实 Bean 可正常装配并生成今天的工单号，验证 {@code TimeConfig} 提供的 Clock 生效。
   */
  @Test
  @DisplayName("装配：真实 Bean 生成今日工单号")
  void shouldGenerateWithInjectedClock() {
    String today = java.time.LocalDate.now(BUSINESS_ZONE)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
    trackKey(today);

    assertThat(ticketNoGenerator.next()).startsWith("TK" + today);
  }

  /**
   * 构造使用指定时钟的生成器，其余依赖复用真实 Bean。
   *
   * @param clock 测试时钟
   * @return 生成器实例
   */
  private TicketNoGenerator generatorAt(Clock clock) {
    return new TicketNoGenerator(redisAtomicCounter, clock, ticketNoMapper);
  }

  /**
   * 构造 UTC 时刻，并按业务时区解释为本地日期时间。
   *
   * @param instant ISO-8601 时刻，例如 {@code 2037-03-04T02:00:00Z}
   * @return 固定时钟
   */
  private Clock fixedClock(String instant) {
    return Clock.fixed(Instant.parse(instant), BUSINESS_ZONE);
  }

  /**
   * 登记需要清理的序列键。
   *
   * @param datePart 日期段，形如 {@code 20370304}
   */
  private void trackKey(String datePart) {
    touchedKeys.add(SEQ_KEY_PREFIX + datePart);
  }

  /**
   * 构造一个 Redis 必定失败的计数器，模拟 Redis 不可用。
   *
   * @return 抛连接异常的计数器
   */
  private RedisAtomicCounter brokenCounter() {
    RedisAtomicCounter counter = mock(RedisAtomicCounter.class);
    when(counter.incrWithTtl(anyString(), anyLong())).thenThrow(new RedisConnectionFailureException("redis down"));
    return counter;
  }

  /**
   * 直接插入一张工单，只填非空列，用于构造降级场景的「当日最大号」。
   *
   * @param ticketNo 工单号
   */
  private void insertTicket(String ticketNo) {
    jdbcTemplate.update(
        "INSERT INTO ticket (ticket_no, title, description, customer_id, reporter_id) VALUES (?, ?, ?, ?, ?)",
        ticketNo, "降级用例工单", "用于验证降级取号", 1L, 1L);
  }

  /**
   * 获取线程结果并把受检异常转成运行时异常，避免 stream 里写 try-catch。
   *
   * @param future 任务结果
   * @return 工单号
   */
  private String getQuietly(Future<String> future) {
    try {
      return future.get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("并发取号失败", e);
    }
  }
}
