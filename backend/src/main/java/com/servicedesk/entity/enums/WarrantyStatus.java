package com.servicedesk.entity.enums;

import java.time.LocalDate;

/**
 * 保修状态（3 种），读接口时按保修到期日实时计算，不落库。
 *
 * <p>
 * 属于枚举字典层，取值见接口文档附录 A.6。计算口径：
 * </p>
 *
 * <ul>
 * <li>到期日为空 → {@link #UNKNOWN}（未登记保修信息，不代表已过保）；</li>
 * <li>到期日 &gt;= 今天 → {@link #IN_WARRANTY}，到期当天仍算保修内；</li>
 * <li>到期日 &lt; 今天 → {@link #EXPIRED}。</li>
 * </ul>
 */
public enum WarrantyStatus {

  /** 保修内：保修到期日晚于或等于今天 */
  IN_WARRANTY,

  /** 已过保：保修到期日早于今天 */
  EXPIRED,

  /** 未知：未登记保修到期日 */
  UNKNOWN;

  /**
   * 按保修到期日计算保修状态。
   *
   * <p>
   * 必须传入「今天」而不是在方法内取 {@link LocalDate#now()}：批量列表计算时
   * 每行都取一次 now() 会在跨零点时得到不同日期，导致同一响应内口径不一致。
   * </p>
   *
   * @param warrantyEnd 保修到期日，可为 null
   * @param today       计算基准日，不能为 null
   * @return 保修状态
   */
  public static WarrantyStatus of(LocalDate warrantyEnd, LocalDate today) {
    if (warrantyEnd == null) {
      return UNKNOWN;
    }
    return warrantyEnd.isBefore(today) ? EXPIRED : IN_WARRANTY;
  }
}
