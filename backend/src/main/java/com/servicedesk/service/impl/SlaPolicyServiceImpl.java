package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.dto.request.sla.SlaPolicyCreateRequest;
import com.servicedesk.dto.request.sla.SlaPolicyUpdateRequest;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.enums.Urgency;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.TicketMapper;
import com.servicedesk.service.SlaPolicyService;
import com.servicedesk.vo.SlaPolicyVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SLA 策略管理服务实现。
 *
 * <p>
 * 属于业务实现层。四条约定：
 * </p>
 *
 * <ul>
 * <li>优先级既是匹配键也是唯一索引列，只在新增时校验唯一性，修改接口不接收该字段；</li>
 * <li>优先级取值复用 {@link Urgency} 字典，不另建一套 P0-P3 字符串；</li>
 * <li>删除带引用时降级为停用，避免历史工单的 {@code sla_policy_id} 指向不存在的策略；</li>
 * <li>写操作的日志记录操作人，便于追查是谁改了时限。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlaPolicyServiceImpl implements SlaPolicyService {

  /** {@code sla_policy.enabled} 的启用取值。 */
  private static final int ENABLED = 1;

  /** {@code sla_policy.enabled} 的停用取值。 */
  private static final int DISABLED = 0;

  /** SLA 策略 Mapper。 */
  private final SlaPolicyMapper slaPolicyMapper;

  /** 工单 Mapper，仅用于判定策略是否已被工单引用。 */
  private final TicketMapper ticketMapper;

  /**
   * 查询全部 SLA 策略，按优先级升序。
   *
   * <p>
   * 直接按 {@code priority} 字典序排列：P0 - P3 的字典序与业务优先级一致，不需要额外的排序权重列。
   * </p>
   *
   * @return 策略列表，无数据时为空列表
   */
  @Override
  public List<SlaPolicyVO> list() {
    return slaPolicyMapper.selectList(Wrappers.<SlaPolicy>lambdaQuery()
            .orderByAsc(SlaPolicy::getPriority))
        .stream()
        .map(this::toVO)
        .toList();
  }

  /**
   * 新增 SLA 策略。
   *
   * @param request 新增入参
   * @return 新建策略
   * @throws BusinessException 优先级不在字典内抛 400；解决时限小于响应时限抛 400；
   *                           该优先级已有策略抛 409
   */
  @Override
  @Transactional
  public SlaPolicyVO create(SlaPolicyCreateRequest request) {
    String name = request.getName().trim();
    String priority = Urgency.from(request.getPriority()).name();
    requireMinutesOrder(request.getResponseMinutes(), request.getResolveMinutes());

    if (slaPolicyMapper.exists(Wrappers.<SlaPolicy>lambdaQuery()
        .eq(SlaPolicy::getPriority, priority))) {
      log.warn("新增 SLA 策略失败：优先级已存在, priority={}, operatorId={}",
          priority, UserContext.getUserId());
      throw new BusinessException(409, "该优先级的策略已存在");
    }

    SlaPolicy policy = new SlaPolicy();
    policy.setName(name);
    policy.setPriority(priority);
    policy.setResponseMinutes(request.getResponseMinutes());
    policy.setResolveMinutes(request.getResolveMinutes());
    policy.setEnabled(request.getEnabled() == null ? ENABLED : toFlag(request.getEnabled()));

    try {
      slaPolicyMapper.insert(policy);
    } catch (DuplicateKeyException e) {
      // 先查后写之间被并发插入同一优先级，唯一索引兜底，仍然按 409 返回而不是 500
      log.warn("新增 SLA 策略失败：优先级并发冲突, priority={}, operatorId={}",
          priority, UserContext.getUserId());
      throw new BusinessException(409, "该优先级的策略已存在");
    }

    log.info("新增 SLA 策略成功, policyId={}, priority={}, responseMinutes={}, resolveMinutes={}, enabled={}, operatorId={}",
        policy.getId(), priority, policy.getResponseMinutes(), policy.getResolveMinutes(),
        policy.getEnabled(), UserContext.getUserId());
    return toVO(policy);
  }

  /**
   * 修改策略名、两个时限与启用状态。
   *
   * @param id      策略 ID
   * @param request 修改入参
   * @return 修改后的策略
   * @throws BusinessException 策略不存在抛 404；解决时限小于响应时限抛 400
   */
  @Override
  @Transactional
  public SlaPolicyVO update(Long id, SlaPolicyUpdateRequest request) {
    SlaPolicy policy = requirePolicy(id);
    requireMinutesOrder(request.getResponseMinutes(), request.getResolveMinutes());

    String beforeName = policy.getName();
    policy.setName(request.getName().trim());
    policy.setResponseMinutes(request.getResponseMinutes());
    policy.setResolveMinutes(request.getResolveMinutes());
    policy.setEnabled(toFlag(request.getEnabled()));
    slaPolicyMapper.updateById(policy);

    log.info("修改 SLA 策略成功, policyId={}, priority={}, name={} -> {}, responseMinutes={}, resolveMinutes={}, enabled={}, operatorId={}",
        id, policy.getPriority(), beforeName, policy.getName(), policy.getResponseMinutes(),
        policy.getResolveMinutes(), policy.getEnabled(), UserContext.getUserId());
    return toVO(policy);
  }

  /**
   * 删除或停用策略。
   *
   * @param id 策略 ID
   * @throws BusinessException 策略不存在抛 404
   */
  @Override
  @Transactional
  public void delete(Long id) {
    SlaPolicy policy = requirePolicy(id);

    boolean referenced = ticketMapper.exists(Wrappers.<Ticket>lambdaQuery()
        .eq(Ticket::getSlaPolicyId, id));
    if (referenced) {
      if (policy.getEnabled() != null && policy.getEnabled() == DISABLED) {
        log.info("SLA 策略已停用且仍有工单引用，幂等返回, policyId={}, operatorId={}",
            id, UserContext.getUserId());
        return;
      }
      policy.setEnabled(DISABLED);
      slaPolicyMapper.updateById(policy);
      log.info("SLA 策略被工单引用，改为停用, policyId={}, priority={}, operatorId={}",
          id, policy.getPriority(), UserContext.getUserId());
      return;
    }

    slaPolicyMapper.deleteById(id);
    log.info("SLA 策略无工单引用，物理删除, policyId={}, priority={}, operatorId={}",
        id, policy.getPriority(), UserContext.getUserId());
  }

  /**
   * 按 ID 查询策略，不存在时抛 404。
   *
   * @param id 策略 ID
   * @return 策略实体
   * @throws BusinessException 策略不存在时抛出 404
   */
  private SlaPolicy requirePolicy(Long id) {
    SlaPolicy policy = slaPolicyMapper.selectById(id);
    if (policy == null) {
      log.warn("SLA 策略不存在, policyId={}, operatorId={}", id, UserContext.getUserId());
      throw new BusinessException(404, "SLA 策略不存在");
    }
    return policy;
  }

  /**
   * 校验解决时限不早于响应时限。
   *
   * <p>
   * 接口文档 5.2 明确要求 {@code resolveMinutes >= responseMinutes}；5.3 只写了正数，
   * 这里按同一业务语义统一校验——解决早于响应没有可解释的含义。
   * </p>
   *
   * @param responseMinutes 响应时限（分钟）
   * @param resolveMinutes  解决时限（分钟）
   * @throws BusinessException 解决时限小于响应时限时抛出 400
   */
  private void requireMinutesOrder(int responseMinutes, int resolveMinutes) {
    if (resolveMinutes < responseMinutes) {
      log.warn("SLA 策略时限不合法：解决时限小于响应时限, responseMinutes={}, resolveMinutes={}, operatorId={}",
          responseMinutes, resolveMinutes, UserContext.getUserId());
      throw new BusinessException(400, "解决时限不能小于响应时限");
    }
  }

  /**
   * 布尔启用状态转数据库标记。
   *
   * @param enabled 是否启用
   * @return 1 启用 / 0 停用
   */
  private int toFlag(boolean enabled) {
    return enabled ? ENABLED : DISABLED;
  }

  /**
   * 实体转视图对象。
   *
   * @param policy 策略实体
   * @return 策略视图
   */
  private SlaPolicyVO toVO(SlaPolicy policy) {
    return SlaPolicyVO.builder()
        .id(policy.getId())
        .name(policy.getName())
        .priority(policy.getPriority())
        .responseMinutes(policy.getResponseMinutes())
        .resolveMinutes(policy.getResolveMinutes())
        .enabled(policy.getEnabled() != null && policy.getEnabled() == ENABLED)
        .build();
  }
}
