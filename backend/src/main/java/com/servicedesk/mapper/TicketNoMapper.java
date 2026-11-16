package com.servicedesk.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工单号降级查询 Mapper，只提供「当日最大工单号」一个查询。
 *
 * <p>
 * 属于持久化层。之所以不继承 {@code BaseMapper}：工单实体与常规 CRUD 属于建单功能，
 * 本类只服务于 Redis 不可用时的号段兜底，保持最小依赖面。
 * </p>
 *
 * <p>
 * 接口由启动类上的 {@code @MapperScan("com.servicedesk.mapper")} 生成代理，
 * 不需要额外加 {@code @Mapper}。
 * </p>
 */
public interface TicketNoMapper {

    /**
     * 查询指定前缀下最大的工单号。
     *
     * <p>
     * 用 {@code LIKE 'prefix%'} 而不是 {@code RIGHT(ticket_no, 6)}：前缀匹配是范围扫描，
     * 可以走 {@code uk_ticket_no} 索引；对列做函数运算会让索引失效，退化为全表扫描。
     * </p>
     *
     * @param prefix 工单号前缀，形如 {@code TK20261115}
     * @return 当日最大工单号；当日无工单时返回 null
     */
    @Select("SELECT MAX(ticket_no) FROM ticket WHERE ticket_no LIKE CONCAT(#{prefix}, '%')")
    String selectMaxTicketNo(@Param("prefix") String prefix);
}
