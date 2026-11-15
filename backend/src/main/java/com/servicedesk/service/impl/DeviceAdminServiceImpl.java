package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.servicedesk.common.api.PageResult;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.dto.request.admin.DeviceCreateRequest;
import com.servicedesk.dto.request.admin.DeviceQueryRequest;
import com.servicedesk.dto.request.admin.DeviceUpdateRequest;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.enums.DeviceStatus;
import com.servicedesk.entity.enums.WarrantyStatus;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
import com.servicedesk.service.DeviceAdminService;
import com.servicedesk.vo.DeviceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 设备档案服务实现。
 *
 * <p>
 * 属于业务实现层。四条约定：
 * </p>
 *
 * <ul>
 * <li>序列号唯一性在写入前显式检查，返回 409，不让 {@code uk_serial_no} 抛出 500；</li>
 * <li>{@code customer_id} 是逻辑外键，必须显式校验存在性，否则会留下悬空引用；</li>
 * <li>保修状态每次读取时按「同一基准日」实时计算，保证同一响应内口径一致；</li>
 * <li>修改走显式 {@code set} 的更新包装器而不是 {@code updateById}：
 * 后者会跳过 null 字段，导致「清空型号 / 清空保修日期」永远不生效。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAdminServiceImpl implements DeviceAdminService {

  /** 设备档案 Mapper。 */
  private final DeviceMapper deviceMapper;

  /** 客户档案 Mapper，用于校验客户存在性与回填客户名。 */
  private final CustomerMapper customerMapper;

  /**
   * 分页查询设备。
   *
   * @param query 查询条件
   * @return 分页结果，含实时计算的保修状态
   */
  @Override
  public PageResult<DeviceVO> page(DeviceQueryRequest query) {
    LambdaQueryWrapper<Device> wrapper = Wrappers.<Device>lambdaQuery()
        .eq(query.getCustomerId() != null, Device::getCustomerId, query.getCustomerId())
        .orderByDesc(Device::getId);

    String keyword = trimToNull(query.getKeyword());
    if (keyword != null) {
      // 关键字条件是「或」关系，必须整体括起来，否则会把客户过滤条件一起或掉
      wrapper.and(w -> w.like(Device::getModel, keyword).or().like(Device::getSerialNo, keyword));
    }

    IPage<Device> page = deviceMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
    Map<Long, String> customerNames = loadCustomerNames(page.getRecords());

    // 基准日只取一次：逐行取 now() 会在跨零点时让同一响应出现两种保修口径
    LocalDate today = LocalDate.now();
    return PageResult.from(page, device -> toVO(device, customerNames.get(device.getCustomerId()), today));
  }

  /**
   * 新增设备档案。
   *
   * @param request 新增入参
   * @return 新建设备
   * @throws BusinessException 客户不存在抛 400；序列号重复抛 409；状态非法或日期先后关系错误抛 400
   */
  @Override
  public DeviceVO create(DeviceCreateRequest request) {
    Customer customer = requireCustomer(request.getCustomerId());

    String serialNo = request.getSerialNo().trim();
    if (existsBySerialNo(serialNo, null)) {
      log.warn("新增设备失败：序列号已存在, serialNo={}, operatorId={}", serialNo, UserContext.getUserId());
      throw new BusinessException(409, "序列号已存在");
    }
    validateDates(request.getPurchaseDate(), request.getWarrantyEnd());

    DeviceStatus status = parseStatus(request.getStatus());

    Device device = new Device();
    device.setCustomerId(customer.getId());
    device.setModel(trimToNull(request.getModel()));
    device.setSerialNo(serialNo);
    device.setPurchaseDate(request.getPurchaseDate());
    device.setWarrantyEnd(request.getWarrantyEnd());
    device.setLocation(trimToNull(request.getLocation()));
    device.setStatus(status.name());
    deviceMapper.insert(device);

    log.info("新增设备成功, deviceId={}, serialNo={}, customerId={}, status={}, operatorId={}",
        device.getId(), serialNo, customer.getId(), status, UserContext.getUserId());
    return toVO(device, customer.getName(), LocalDate.now());
  }

  /**
   * 修改设备档案。
   *
   * <p>
   * 型号、日期、位置按整体覆盖处理（未传即清空）；状态未传时保留原值。
   * </p>
   *
   * @param id      设备 ID
   * @param request 修改入参
   * @return 修改后的设备
   * @throws BusinessException 设备不存在抛 404；客户不存在抛 400；序列号重复抛 409；
   *                           状态非法或日期先后关系错误抛 400
   */
  @Override
  public DeviceVO update(Long id, DeviceUpdateRequest request) {
    Device device = requireDevice(id);
    Customer customer = requireCustomer(request.getCustomerId());

    String serialNo = request.getSerialNo().trim();
    if (existsBySerialNo(serialNo, id)) {
      log.warn("修改设备失败：序列号已存在, deviceId={}, serialNo={}, operatorId={}",
          id, serialNo, UserContext.getUserId());
      throw new BusinessException(409, "序列号已存在");
    }
    validateDates(request.getPurchaseDate(), request.getWarrantyEnd());

    String beforeSerialNo = device.getSerialNo();
    String model = trimToNull(request.getModel());
    String location = trimToNull(request.getLocation());
    DeviceStatus status = isNotBlank(request.getStatus()) ? parseStatus(request.getStatus()) : null;

    LambdaUpdateWrapper<Device> update = Wrappers.<Device>lambdaUpdate()
        .eq(Device::getId, id)
        .set(Device::getCustomerId, customer.getId())
        .set(Device::getModel, model)
        .set(Device::getSerialNo, serialNo)
        .set(Device::getPurchaseDate, request.getPurchaseDate())
        .set(Device::getWarrantyEnd, request.getWarrantyEnd())
        .set(Device::getLocation, location);
    if (status != null) {
      update.set(Device::getStatus, status.name());
    }
    deviceMapper.update(null, update);

    // 同步内存实体用于响应回显，避免改完再查一次库
    device.setCustomerId(customer.getId());
    device.setModel(model);
    device.setSerialNo(serialNo);
    device.setPurchaseDate(request.getPurchaseDate());
    device.setWarrantyEnd(request.getWarrantyEnd());
    device.setLocation(location);
    if (status != null) {
      device.setStatus(status.name());
    }

    log.info("修改设备成功, deviceId={}, serialNo={} -> {}, customerId={}, status={}, operatorId={}",
        id, beforeSerialNo, serialNo, customer.getId(), device.getStatus(), UserContext.getUserId());
    return toVO(device, customer.getName(), LocalDate.now());
  }

  /**
   * 按 ID 查询设备，不存在时抛 404。
   *
   * @param id 设备 ID
   * @return 设备实体
   * @throws BusinessException 设备不存在时抛出 404
   */
  private Device requireDevice(Long id) {
    Device device = deviceMapper.selectById(id);
    if (device == null) {
      log.warn("设备不存在, deviceId={}, operatorId={}", id, UserContext.getUserId());
      throw new BusinessException(404, "设备不存在");
    }
    return device;
  }

  /**
   * 校验客户存在性并返回客户档案。
   *
   * @param customerId 客户 ID，不能为 null
   * @return 客户实体
   * @throws BusinessException 客户不存在时抛出 400
   */
  private Customer requireCustomer(Long customerId) {
    Customer customer = customerMapper.selectById(customerId);
    if (customer == null) {
      log.warn("客户不存在, customerId={}, operatorId={}", customerId, UserContext.getUserId());
      throw new BusinessException(400, "客户不存在");
    }
    return customer;
  }

  /**
   * 校验购买日期与保修到期日的先后关系。
   *
   * <p>
   * 两者都是可选项，只有一个时不校验；同时存在时，保修到期日不得早于购买日期，
   * 否则会算出「买来就已过保」的荒谬保修状态。
   * </p>
   *
   * @param purchaseDate 购买日期，可为 null
   * @param warrantyEnd  保修到期日，可为 null
   * @throws BusinessException 保修到期日早于购买日期时抛出 400
   */
  private void validateDates(LocalDate purchaseDate, LocalDate warrantyEnd) {
    if (purchaseDate != null && warrantyEnd != null && warrantyEnd.isBefore(purchaseDate)) {
      log.warn("保修到期日早于购买日期, purchaseDate={}, warrantyEnd={}, operatorId={}",
          purchaseDate, warrantyEnd, UserContext.getUserId());
      throw new BusinessException(400, "保修到期日不能早于购买日期");
    }
  }

  /**
   * 解析设备状态，非法时返回 400。
   *
   * @param rawStatus 原始状态字符串，可为 null
   * @return 解析后的状态，null/空白时返回默认状态 ACTIVE
   * @throws BusinessException 状态取值非法时抛出 400
   */
  private DeviceStatus parseStatus(String rawStatus) {
    return DeviceStatus.fromOrDefault(rawStatus);
  }

  /**
   * 判断序列号是否已被其他设备占用。
   *
   * @param serialNo  序列号（已去首尾空白）
   * @param excludeId 需要排除的设备 ID（修改时排除自己），新增时传 null
   * @return true 表示已存在
   */
  private boolean existsBySerialNo(String serialNo, Long excludeId) {
    return deviceMapper.exists(Wrappers.<Device>lambdaQuery()
        .eq(Device::getSerialNo, serialNo)
        .ne(excludeId != null, Device::getId, excludeId));
  }

  /**
   * 批量加载客户名。
   *
   * @param devices 当前页设备
   * @return 客户 ID 到客户名的映射
   */
  private Map<Long, String> loadCustomerNames(List<Device> devices) {
    Set<Long> customerIds = devices.stream()
        .map(Device::getCustomerId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    if (customerIds.isEmpty()) {
      return Map.of();
    }
    Collection<Customer> customers = customerMapper.selectByIds(customerIds);
    return customers.stream().collect(Collectors.toMap(Customer::getId, Customer::getName));
  }

  /**
   * 实体转视图对象。
   *
   * @param device       设备实体
   * @param customerName 客户名，可为 null
   * @param today        保修状态计算基准日
   * @return 设备视图
   */
  private DeviceVO toVO(Device device, String customerName, LocalDate today) {
    return DeviceVO.builder()
        .id(device.getId())
        .customerId(device.getCustomerId())
        .customerName(customerName)
        .model(device.getModel())
        .serialNo(device.getSerialNo())
        .purchaseDate(device.getPurchaseDate())
        .warrantyEnd(device.getWarrantyEnd())
        .warrantyStatus(WarrantyStatus.of(device.getWarrantyEnd(), today).name())
        .location(device.getLocation())
        .status(device.getStatus())
        .build();
  }

  /**
   * 判断字符串非空白。
   *
   * @param value 待判断字符串
   * @return true 表示存在非空白内容
   */
  private boolean isNotBlank(String value) {
    return value != null && !value.isBlank();
  }

  /**
   * 去掉首尾空白，空串归一为 null。
   *
   * @param value 原始字符串
   * @return 去空白后的值，空白时返回 null
   */
  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
