package com.servicedesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 团队实体，对应表 {@code team}。
 *
 * <p>
 * 属于持久化层。字段与 {@code docs/ER图.md} 4.2 一一对应，外键均为逻辑外键，不建物理约束。
 * 当前只用于登录与当前用户接口回填 {@code teamName}。
 * </p>
 */
@Data
@TableName("team")
public class Team {

  /** 主键，数据库自增。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 团队名，唯一。 */
  private String name;

  /** 主管用户 ID，逻辑外键 sys_user.id。 */
  private Long supervisorId;

  /** 描述。 */
  private String description;

  /** 创建时间，数据库默认值填充。 */
  private LocalDateTime createdAt;

  /** 更新时间，数据库 ON UPDATE 维护。 */
  private LocalDateTime updatedAt;
}
