package com.servicedesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.servicedesk.entity.SysUser;

/**
 * 用户账号 Mapper，继承 {@link BaseMapper} 获得单表 CRUD 与分页能力。
 *
 * <p>
 * 属于持久化层。接口由启动类上的 {@code @MapperScan("com.servicedesk.mapper")} 生成代理，
 * 无需再加 {@code @Mapper}。没有自定义 SQL 时不要建同名 XML。
 * </p>
 */
public interface SysUserMapper extends BaseMapper<SysUser> {
}
