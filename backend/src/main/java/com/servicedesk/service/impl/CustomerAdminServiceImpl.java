package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.servicedesk.common.api.PageResult;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.dto.request.admin.CustomerCreateRequest;
import com.servicedesk.dto.request.admin.CustomerQueryRequest;
import com.servicedesk.dto.request.admin.CustomerUpdateRequest;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.enums.CustomerLevel;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.service.CustomerAdminService;
import com.servicedesk.vo.CustomerVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 客户档案服务实现。
 *
 * <p>
 * 属于业务实现层。两条约定：
 * </p>
 *
 * <ul>
 * <li>等级一律经 {@link CustomerLevel#fromOrDefault(String)} 归一化后落库，
 * 保证库中只有 {@code KEY/NORMAL} 两种取值，不存大小写混写；</li>
 * <li>客户名不做唯一性校验（业务允许同名），只做存在性校验与长度校验。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerAdminServiceImpl implements CustomerAdminService {

  /** 客户档案 Mapper。 */
  private final CustomerMapper customerMapper;

  /**
   * 分页查询客户。
   *
   * @param query 查询条件
   * @return 分页结果
   */
  @Override
  public PageResult<CustomerVO> page(CustomerQueryRequest query) {
    LambdaQueryWrapper<Customer> wrapper = Wrappers.<Customer>lambdaQuery().orderByDesc(Customer::getId);

    String keyword = trimToNull(query.getKeyword());
    if (keyword != null) {
      // 关键字条件是「或」关系，必须整体括起来，否则会把排序之外的条件一起或掉
      wrapper.and(w -> w.like(Customer::getName, keyword).or().like(Customer::getPhone, keyword));
    }

    IPage<Customer> page = customerMapper.selectPage(new Page<>(query.getPage(), query.getSize()), wrapper);
    return PageResult.from(page, this::toVO);
  }

  /**
   * 新建客户档案。
   *
   * @param request 新增入参
   * @return 新建客户
   * @throws BusinessException 客户等级非法时抛出 400
   */
  @Override
  public CustomerVO create(CustomerCreateRequest request) {
    CustomerLevel level = parseLevel(request.getLevel());

    Customer customer = new Customer();
    customer.setName(request.getName().trim());
    customer.setContact(trimToNull(request.getContact()));
    customer.setPhone(trimToNull(request.getPhone()));
    customer.setAddress(trimToNull(request.getAddress()));
    customer.setLevel(level.name());
    customer.setRemark(trimToNull(request.getRemark()));
    customerMapper.insert(customer);

    log.info("新建客户成功, customerId={}, name={}, level={}, operatorId={}",
        customer.getId(), customer.getName(), level, UserContext.getUserId());
    return toVO(customer);
  }

  /**
   * 修改客户档案。
   *
   * @param id      客户 ID
   * @param request 修改入参
   * @return 修改后的客户
   * @throws BusinessException 客户不存在抛 404；客户等级非法抛 400
   */
  @Override
  public CustomerVO update(Long id, CustomerUpdateRequest request) {
    Customer customer = requireCustomer(id);
    CustomerLevel level = parseLevel(request.getLevel());

    String beforeName = customer.getName();
    customer.setName(request.getName().trim());
    customer.setContact(trimToNull(request.getContact()));
    customer.setPhone(trimToNull(request.getPhone()));
    customer.setAddress(trimToNull(request.getAddress()));
    customer.setLevel(level.name());
    customer.setRemark(trimToNull(request.getRemark()));
    customerMapper.updateById(customer);

    log.info("修改客户成功, customerId={}, name={} -> {}, level={}, operatorId={}",
        id, beforeName, customer.getName(), level, UserContext.getUserId());
    return toVO(customer);
  }

  /**
   * 按 ID 查询客户，不存在时抛 404。
   *
   * @param id 客户 ID
   * @return 客户实体
   * @throws BusinessException 客户不存在时抛出 404
   */
  private Customer requireCustomer(Long id) {
    Customer customer = customerMapper.selectById(id);
    if (customer == null) {
      log.warn("客户不存在, customerId={}, operatorId={}", id, UserContext.getUserId());
      throw new BusinessException(404, "客户不存在");
    }
    return customer;
  }

  /**
   * 解析客户等级，非法时返回 400。
   *
   * @param rawLevel 原始等级字符串，可为 null
   * @return 解析后的等级，null/空白时返回默认等级 NORMAL
   * @throws BusinessException 等级取值非法时抛出 400
   */
  private CustomerLevel parseLevel(String rawLevel) {
    return CustomerLevel.fromOrDefault(rawLevel);
  }

  /**
   * 实体转视图对象。
   *
   * @param customer 客户实体
   * @return 客户视图
   */
  private CustomerVO toVO(Customer customer) {
    return CustomerVO.builder()
        .id(customer.getId())
        .name(customer.getName())
        .contact(customer.getContact())
        .phone(customer.getPhone())
        .address(customer.getAddress())
        .level(customer.getLevel())
        .remark(customer.getRemark())
        .build();
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
