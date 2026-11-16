package com.servicedesk.service.support;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.common.utils.RedisAtomicCounter;
import com.servicedesk.mapper.TicketNoMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 工单号生成器：{@code TK} + {@code yyyyMMdd} + 6 位日序列，如 {@code TK20261115000001}。
 *
 * <p>
 * 属于业务支撑层，只负责「生成一个号」，不落库、不校验唯一性、不参与建单事务。
 * 号段来自 Redis {@code ticket:seq:{yyyyMMdd}} 的自增计数，跨天靠键名切换自然归零，
 * 因此没有任何清零代码。
 * </p>
 *
 * <p>
 * 关键约定：
 * </p>
 *
 * <ul>
 * <li>不幂等：每调用一次消耗一个序号，业务失败也不回收，允许跳号——
 * 工单号只要求唯一且可读，不要求连续；</li>
 * <li>不保证唯一：Redis 故障降级后多实例可能撞号，最终由 {@code ticket.uk_ticket_no}
 * 唯一索引兜底，建单失败重试即可；</li>
 * <li>事务边界：只写 Redis，不参与 Spring 数据库事务，数据库回滚不会退号。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketNoGenerator {
    /** 工单号前缀 */
    private static final String PREFIX = "TK";
    /** Redis 日序列键前缀，完整键形如 {@code ticket:seq:20261115}。 */
    private static final String SEQ_KEY_PREFIX = "ticket:seq:";
    /** 日期段格式 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 序列TTL：2天，覆盖跨天与时钟回拨 */
    private static final long SEQ_TTL_SECONDS = 2 * 24 * 60 * 60;
    /** 日序列位数，产品约定6位 */
    private static final int SEQ_WIDTH = 6;
    /** 单日序号上限（6 位最大值），超出说明当日容量耗尽 */
    private static final long SEQ_MAX = 999_999L;

    /** 原子计数器，提供「自增 + 首次设置 TTL」原语。 */
    private final RedisAtomicCounter redisAtomicCounter;

    /** 业务时钟，禁止直接调用 {@code LocalDate.now()}。 */
    private final Clock clock;

    /** 降级查询当日最大工单号，仅在 Redis 不可用时使用。 */
    private final TicketNoMapper ticketNoMapper;

    /**
     * 生成下一个工单号。
     *
     * <p>
     * 幂等性：不幂等，每调用一次消耗一个序号。
     * 事务边界：只写 Redis，不参与数据库事务。
     * </p>
     *
     * @return 形如 {@code TK20261115000001} 的工单号
     * @throws BusinessException 当日序号超过 999999 时抛出，状态码 503
     */
    public String next() {
        // 日期段只算一次，键名与返回值共用，避免跨0点时两者取到不同日期
        String datePart = LocalDate.now(clock).format(DATE_FORMAT);
        try {
            return nextFromRedis(datePart);
        } catch (DataAccessException e) {
            // 只接 Redis 访问异常：BusinessException（容量耗尽）等业务异常不在此吞掉
            log.error("Redis 不可用，工单号降级为库内当日最大号+1, date={}", datePart, e);
            return nextFromDb(datePart);
        }
    }

    /**
     * 主路径：Redis 原子自增取号。
     *
     * @param datePart 日期段，形如 {@code 20261115}
     * @return 工单号
     * @throws BusinessException 当日序号超过 999999 时抛出 503
     */
    private String nextFromRedis(String datePart) {
        // Lua脚本原子完成INCR+EXPIRE，今日首次调用返回1，跨天自动归零
        long seq = redisAtomicCounter.incrWithTtl(SEQ_KEY_PREFIX + datePart, SEQ_TTL_SECONDS);

        if (seq > SEQ_MAX) {
            log.error("今日工单号已耗尽，date={}，seq={}", datePart, seq);
            throw new BusinessException(503, "工单号生成容量耗尽,请联系管理员");
        }
        String ticketNo = format(datePart, seq);
        log.info("生成工单号:{},seq={}", ticketNo, seq);
        return ticketNo;
    }

    /**
     * 降级路径：取库内当日最大号 + 1。
     *
     * <p>
     * 不保证唯一：多实例同时降级会生成相同的号，唯一性由 {@code uk_ticket_no} 兜底，
     * 建单方捕获 {@code DuplicateKeyException} 重试即可。这里不做「生成后再查一次」，
     * 那既挡不住并发，又多一次查询。
     * </p>
     *
     * @param datePart 日期段，形如 {@code 20261115}
     * @return 工单号
     * @throws BusinessException 当日序号超过 999999 时抛出 503
     */
    private String nextFromDb(String datePart) {
        String maxTicketNo = ticketNoMapper.selectMaxTicketNo(PREFIX + datePart);
        long nextSeq = parseSeq(maxTicketNo, datePart) + 1;

        if (nextSeq > SEQ_MAX) {
            log.error("今日工单号已耗尽（降级路径），date={}，seq={}", datePart, nextSeq);
            throw new BusinessException(503, "工单号生成容量耗尽,请联系管理员");
        }
        String ticketNo = format(datePart, nextSeq);
        log.warn("工单号降级生成, ticketNo={}", ticketNo);
        return ticketNo;
    }

    /**
     * 从库内最大工单号中解析当日序号。
     *
     * <p>
     * 脏数据（长度不足、非数字）按 0 处理并告警：降级路径本身已是故障态，
     * 再抛异常会让建单直接失败，而按 0 处理最多产生一次撞号，由唯一索引兜底。
     * </p>
     *
     * @param maxTicketNo 库内当日最大工单号，可为 null
     * @param datePart    日期段，形如 {@code 20261115}
     * @return 当日已用序号，无工单或数据异常时返回 0
     */
    private long parseSeq(String maxTicketNo, String datePart) {
        if (maxTicketNo == null) {
            return 0L;
        }
        int prefixLength = PREFIX.length() + datePart.length();
        if (maxTicketNo.length() <= prefixLength) {
            log.warn("工单号格式异常，降级序号按 0 处理, ticketNo={}", maxTicketNo);
            return 0L;
        }
        try {
            return Long.parseLong(maxTicketNo.substring(prefixLength));
        } catch (NumberFormatException e) {
            log.warn("工单号序号段非数字，降级序号按 0 处理, ticketNo={}", maxTicketNo, e);
            return 0L;
        }
    }

    /**
     * 按「前缀 + 日期段 + 左补零序号」拼装工单号。
     *
     * @param datePart 日期段，形如 {@code 20261115}
     * @param seq      当日序号，调用方需保证不超过 {@link #SEQ_MAX}
     * @return 工单号
     */
    private String format(String datePart, long seq) {
        // %06d：不足6位左补零，seq已保证不超过6位
        return PREFIX + datePart + String.format("%0" + SEQ_WIDTH + "d", seq);
    }
}
