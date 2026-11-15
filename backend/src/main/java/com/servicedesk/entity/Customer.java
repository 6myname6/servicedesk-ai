package com.servicedesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户档案实体，对应表 {@code customer}。
 *
 * <p>
 * 属于持久化层。字段与 {@code docs/ER图.md} 4.3 一一对应，下划线转驼峰由
 * {@code mybatis-plus.configuration.map-underscore-to-camel-case} 处理。
 * {@code created_at}/{@code updated_at} 交给数据库默认值维护，插入时保持为 null 即可。
 * </p>
 */
@Data
@TableName("customer")
public class Customer {

  /** 主键，数据库自增。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 客户名，不允许为空，不做唯一约束（同一集团可能存在同名子公司）。 */
  private String name;

  /** 联系人。 */
  private String contact;

  /** 联系电话。 */
  private String phone;

  /** 地址。 */
  private String address;

  /** 客户等级：KEY / NORMAL，见 {@code com.servicedesk.entity.enums.CustomerLevel}。 */
  private String level;

  /** 备注。 */
  private String remark;

  /** 创建时间，数据库默认值填充。 */
  private LocalDateTime createdAt;

  /** 更新时间，数据库 ON UPDATE 维护。 */
  private LocalDateTime updatedAt;
}
