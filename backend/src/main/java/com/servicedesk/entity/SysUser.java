package com.servicedesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户账号实体，对应表 {@code sys_user}。
 *
 * <p>
 * 属于持久化层。字段与 {@code docs/ER图.md} 4.1 一一对应，下划线转驼峰由
 * {@code mybatis-plus.configuration.map-underscore-to-camel-case} 处理。
 * {@code created_at}/{@code updated_at} 交给数据库默认值维护，插入时保持为 null 即可。
 * </p>
 */
@Data
@TableName("sys_user")
public class SysUser {

  /** 主键，数据库自增。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 登录名，唯一。 */
  private String username;

  /** 密码哈希，BCrypt 存储，禁止存明文。 */
  private String passwordHash;

  /** 显示名。 */
  private String displayName;

  /** 角色：REPORTER / ENGINEER / SUPERVISOR / ADMIN。 */
  private String role;

  /** 所属团队 ID，逻辑外键 team.id。 */
  private Long teamId;

  /** 手机号。 */
  private String phone;

  /** 邮箱。 */
  private String email;

  /** 状态：1 启用 / 0 停用。 */
  private Integer status;

  /** 创建时间，数据库默认值填充。 */
  private LocalDateTime createdAt;

  /** 更新时间，数据库 ON UPDATE 维护。 */
  private LocalDateTime updatedAt;
}
