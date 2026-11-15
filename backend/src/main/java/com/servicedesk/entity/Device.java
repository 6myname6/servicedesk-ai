package com.servicedesk.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 设备档案实体，对应表 {@code device}。
 *
 * <p>
 * 属于持久化层。字段与 {@code docs/ER图.md} 4.4 一一对应，{@code customer_id}
 * 为逻辑外键，不建物理约束。
 * </p>
 *
 * <p>
 * 保修相关字段只存事实（购买日期、保修到期日），保修状态由
 * {@code WarrantyStatus} 在读接口时实时计算，不落库——落库会随日期推移变成过期数据。
 * </p>
 */
@Data
@TableName("device")
public class Device {

  /** 主键，数据库自增。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 所属客户 ID，逻辑外键 customer.id。 */
  private Long customerId;

  /** 设备型号。 */
  private String model;

  /** 序列号，全局唯一。 */
  private String serialNo;

  /** 购买日期，可为 null。 */
  private LocalDate purchaseDate;

  /** 保修到期日，可为 null；为 null 时保修状态为 UNKNOWN。 */
  private LocalDate warrantyEnd;

  /** 安装位置。 */
  private String location;

  /** 设备状态：ACTIVE / SCRAPPED，见 {@code com.servicedesk.entity.enums.DeviceStatus}。 */
  private String status;

  /** 创建时间，数据库默认值填充。 */
  private LocalDateTime createdAt;

  /** 更新时间，数据库 ON UPDATE 维护。 */
  private LocalDateTime updatedAt;
}
