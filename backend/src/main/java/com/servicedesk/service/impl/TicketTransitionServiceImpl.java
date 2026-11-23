package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.domain.ticket.TicketScope;
import com.servicedesk.domain.ticket.TicketTransition;
import com.servicedesk.dto.request.ticket.TicketStatusTransitionRequest;
import com.servicedesk.entity.SlaPolicy;
import com.servicedesk.entity.Ticket;
import com.servicedesk.entity.TicketEvent;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.entity.enums.TicketStatus;
import com.servicedesk.mapper.SlaPolicyMapper;
import com.servicedesk.mapper.TicketEventMapper;
import com.servicedesk.mapper.TicketMapper;
import com.servicedesk.service.TicketTransitionService;
import com.servicedesk.service.support.TicketAccessGuard;
import com.servicedesk.service.support.TicketAssembler;
import com.servicedesk.service.support.TicketScopeResolver;
import com.servicedesk.vo.TicketDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 工单状态迁移实现（F-02）：接单、开始处理、等客户、客户回复、解决、验收关闭、重开。
 *
 * <p>
 * 属于业务实现层。一次迁移固定四步：查可见性 → 查迁移表 → 判角色 → 改状态并写副作用与事件。
 * 「哪两个状态之间允许迁移、写哪种事件、哪些角色能做」全部来自 {@link TicketTransition}，
 * 本类不写 if-else 状态判断，新增动作只改迁移表与这里的副作用分支。
 * </p>
 *
 * <p>
 * 关键约束：
 * </p>
 * <ul>
 * <li>状态、时间戳与 {@code ticket_event} 必须在同一事务内，不能出现「状态变了但时间线没有」；</li>
 * <li>可空字段的清空必须走 {@code LambdaUpdateWrapper.set(..., null)}，{@code updateById} 会跳过 null；</li>
 * <li>行级范围（工程师仅本人单、主管仅本团队）由 {@link TicketScope} 折算成 404，本类不重复判 403。</li>
 * </ul>
 *
 * <p>
 * 依赖方向：本类 → {@link TicketAccessGuard} / {@link TicketAssembler} /
 * {@link TicketScopeResolver} → Mapper，不注入其它 Service，也不与 {@code TicketServiceImpl} 互相调用。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketTransitionServiceImpl implements TicketTransitionService {

  /** 角色无权发起该迁移的提示，单独抽出以便测试与文档逐字核对。 */
  public static final String MSG_ROLE_DENIED = "当前角色无权执行该状态迁移";

  /** 工单 Mapper，读当前状态、写状态与时间戳。 */
  private final TicketMapper ticketMapper;

  /** SLA 策略 Mapper，重开时按工单绑定的策略重算解决 deadline。 */
  private final SlaPolicyMapper slaPolicyMapper;

  /** 工单事件 Mapper，每次迁移追加一条时间线，只写不改。 */
  private final TicketEventMapper ticketEventMapper;

  /** 数据范围解析器，把登录身份翻译成工单可见范围。 */
  private final TicketScopeResolver ticketScopeResolver;

  /** 访问守卫，负责 404（可见性）与 409（版本冲突）的判定顺序。 */
  private final TicketAccessGuard ticketAccessGuard;

  /** 读模型装配器，迁移后返回与详情一致的 A.8 结构。 */
  private final TicketAssembler ticketAssembler;

  /** 业务时钟，迁移时刻与暂停补时都取它，禁止无参 {@code now()}。 */
  private final Clock clock;

  /**
   * 状态迁移：按迁移表推进工单状态，写时间戳、SLA 补时与时间线。
   *
   * <p>
   * 判定顺序固定：可见性 404 → 版本 409 → 目标状态字典 400 → 迁移表 409 → 角色 403。
   * 先判 404 再判 409，避免通过状态码差异确认「工单存在」；版本放在状态之前，
   * 与取消、派单、编辑保持同一口径。迁移表查不到时才返回 409 而不是把未知组合当默认放行，
   * 这样 CLOSED / CANCELLED 等终态无需单独判空——它们没有任何出边。
   * </p>
   *
   * <p>
   * 角色放在最后判：动作本身不合法时，无论谁来点都是 409，先暴露「这个流转不存在」
   * 比先暴露「你没权限」更贴近调用方的真实错误。
   * </p>
   *
   * @param id      工单 ID
   * @param request 迁转入参
   * @return 迁移后的工单详情
   * @throws BusinessException 不存在或越权抛 404；版本冲突或迁移非法抛 409；
   *                           目标状态取值非法抛 400；角色无权发起该动作抛 403；
   *                           重开时 SLA 策略缺失抛 503
   */
  @Override
  @Transactional(rollbackFor = Exception.class)
  public TicketDetailVO transitionStatus(Long id, TicketStatusTransitionRequest request) {
    // 1) 身份与数据范围：工程师只可见本人单、主管只可见本团队单，越权与不存在都由守卫折成 404，
    //    所以本方法后面不再判 403 级的「他单」问题
    LoginUser operator = UserContext.getRequired();
    TicketScope scope = ticketScopeResolver.resolve(operator);
    Ticket ticket = ticketAccessGuard.requireVisible(id, scope, operator);
    // 2) 乐观锁前置：版本过期先报 409，避免基于旧快照算时间戳并在后面覆盖掉别人的更新
    ticketAccessGuard.assertVersionMatches(ticket, request.getVersion(), operator);

    // 3) 字典翻译：库里存字符串，非法取值在 from() 里抛 400，不进迁移表；null 也在这里被挡住
    TicketStatus current = TicketStatus.from(ticket.getStatus());
    TicketStatus target = TicketStatus.from(request.getTargetStatus());
    // 4) 查迁移表：命中即同时拿到「写哪种事件 + 目标状态 + 允许角色」；查不到说明这条流转不存在，按 409 处理。
    //    惰性 lambda 保证只在失败路径打日志，命中时零开销
    TicketTransition transition = TicketTransition.resolve(current, target)
        .orElseThrow(() -> {
          log.warn("状态迁移被拒绝：迁移不合法, ticketId={}, from={}, to={}, operatorId={}",
              id, current, target, operator.id());
          return new BusinessException(409,
              "当前状态 " + current.name() + " 不可迁移到 " + target.name());
        });
    // 5) 角色鉴权放到最后：动作本身不合法时无论谁来点都是 409，先暴露「流转不存在」比先暴露「你没权限」更准确；
    //    行级归属（本单处理人 / 本团队）已在上一步折成 404，这里只判动作级角色
    assertTransitionAllowed(transition, ticket, operator);

    // 6) 迁前状态先留存：时间线要分别记录 from 与 to，实体状态一改就取不到原值
    String fromStatus = ticket.getStatus();
    // 7) 基准时刻只取一次并截到秒：状态、时间戳、事件三者必须同源，且与 DATETIME(0) 精度一致
    LocalDateTime now = LocalDateTime.now(clock).withNano(0);
    // 8) 只改内存实体：状态 + 该动作专属的时间戳 / SLA 副作用，落库统一由下一步一次 UPDATE 完成
    applyTransition(ticket, transition, now);
    // 9) 带版本号条件更新；影响行数为 0 说明并发期间已被他人改过，409 让整个事务回滚
    if (ticketMapper.updateById(ticket) == 0) {
      log.warn("状态迁移版本冲突, ticketId={}, operatorId={}, requestVersion={}",
          id, operator.id(), request.getVersion());
      throw new BusinessException(409, TicketAccessGuard.MSG_VERSION_CONFLICT);
    }
    // updateById 跳过 null 字段，暂停标记与解决时间必须显式 set 才能清空（编码规范 4.2）
    clearNullableColumns(ticket.getId(), transition);

    // 10) 追加一条时间线：只写不改，与状态变更同事务，避免「状态变了但时间线没有」
    String comment = StringUtils.hasText(request.getComment()) ? request.getComment().trim() : null;
    ticketEventMapper.insert(
        buildTransitionEvent(ticket.getId(), transition, fromStatus, comment, operator.id(), now));

    // 11) 成功日志：备注原文不进日志，可能带客户现场细节，只记录长度
    log.info("状态迁移成功, ticketId={}, transition={}, from={}, to={}, operatorId={}, commentLength={}",
        id, transition, fromStatus, transition.targetStatus(), operator.id(),
        comment == null ? 0 : comment.length());
    // 12) 重查一次再组装响应：拿数据库侧 version / updated_at，保证与详情接口口径一致
    return ticketAssembler.assembleDetail(ticketMapper.selectById(id), now);
  }

  /**
   * 校验当前角色能否发起该迁移。
   *
   * <p>
   * 只判角色。工程师必须是本单处理人、主管必须管本团队，这两条已经由数据范围在
   * {@link TicketAccessGuard#requireVisible} 中折算成 404：在这里再判一次会把 404 变成 403，
   * 反而确认了工单的存在（与 {@link TicketAccessGuard#assertEditable} 的处理一致）。
   * </p>
   *
   * @param transition 命中的迁移
   * @param ticket     工单实体，仅用于日志
   * @param operator   当前登录用户
   * @throws BusinessException 当前角色不在迁移允许的角色集合内时抛出 403
   */
  private void assertTransitionAllowed(TicketTransition transition, Ticket ticket, LoginUser operator) {
    if (!transition.allowedRoles().contains(Role.from(operator.role()))) {
      log.warn("状态迁移被拒绝：角色不符, ticketId={}, transition={}, role={}, operatorId={}",
          ticket.getId(), transition, operator.role(), operator.id());
      throw new BusinessException(403, MSG_ROLE_DENIED);
    }
  }

  /**
   * 应用迁移的状态与时间戳副作用。
   *
   * <p>
   * 只改内存中的实体，落库由调用方统一 {@code updateById} 完成，保证一次迁移只有一条 UPDATE。
   * 需要清空的可空字段不在这里处理：{@code updateById} 会跳过 null 字段，
   * 清空动作集中在 {@link #clearNullableColumns}。
   * </p>
   *
   * @param ticket     工单实体
   * @param transition 命中的迁移
   * @param now        本次迁移时刻
   * @throws BusinessException 重开时工单的 SLA 策略已不存在抛 503
   */
  private void applyTransition(Ticket ticket, TicketTransition transition, LocalDateTime now) {
    ticket.setStatus(transition.targetStatus().name());
    switch (transition) {
      // 接单与工程师首条处理记录都可能写首次响应时间，取最早：已有值就不覆盖
      case ACCEPT -> ticket.setFirstResponseAt(
          ticket.getFirstResponseAt() == null ? now : ticket.getFirstResponseAt());
      // 开始处理只有状态变化，没有时间戳与 SLA 副作用
      case START -> {
      }
      // 暂停起点落库，读路径改用 paused_at 作为倒计时基准，deadline 本身不移动
      case WAIT_CUSTOMER -> ticket.setPausedAt(now);
      case RESUME -> resumeSla(ticket, now);
      case RESOLVE -> ticket.setResolvedAt(now);
      case CLOSE -> ticket.setClosedAt(now);
      case REOPEN -> reopenSla(ticket, now);
    }
  }

  /**
   * 客户回复时补回暂停时长：累计暂停分钟数增加，两个 deadline 同步后移。
   *
   * <p>
   * {@code paused_at} 为空说明暂停已被系统自动恢复（S-02 的 7 天上限）并补过时，
   * 此时只把状态改回处理中，绝不能再补一次，否则 deadline 会被重复顺延。
   * </p>
   *
   * <p>
   * 时钟回拨可能算出负时长，按 0 处理；精度取整分钟，与 {@code paused_total_minutes}
   * 的字段类型一致，避免「deadline 后移了 119 分 59 秒、累计只有 119 分」的口径差异。
   * </p>
   *
   * @param ticket 工单实体
   * @param now    恢复时刻
   */
  private void resumeSla(Ticket ticket, LocalDateTime now) {
    LocalDateTime pausedAt = ticket.getPausedAt();
    if (pausedAt == null) {
      log.warn("恢复处理时 paused_at 为空，按无暂停处理, ticketId={}", ticket.getId());
      return;
    }
    long pausedMinutes = Math.max(0, Duration.between(pausedAt, now).toMinutes());
    int totalMinutes = ticket.getPausedTotalMinutes() == null ? 0 : ticket.getPausedTotalMinutes();
    ticket.setPausedTotalMinutes((int) (totalMinutes + pausedMinutes));
    ticket.setResponseDeadline(shiftDeadline(ticket.getResponseDeadline(), pausedMinutes));
    ticket.setResolveDeadline(shiftDeadline(ticket.getResolveDeadline(), pausedMinutes));
    ticket.setPausedAt(null);
    log.info("恢复处理补回暂停时长, ticketId={}, pausedMinutes={}, pausedTotalMinutes={}, pausedAt={}",
        ticket.getId(), pausedMinutes, ticket.getPausedTotalMinutes(), pausedAt);
  }

  /**
   * 重开时重算解决 SLA：清空解决时间与升级标记，解决 deadline 以本次时刻为基准重算。
   *
   * <p>
   * 只重算解决时钟，不动 {@code first_response_at} 与 {@code response_deadline}：
   * 重开不重置响应 SLA（开发文档 7.13 补充规则）。
   * </p>
   *
   * @param ticket 工单实体
   * @param now    重开时刻
   * @throws BusinessException 工单的 SLA 策略已不存在时抛出 503
   */
  private void reopenSla(Ticket ticket, LocalDateTime now) {
    SlaPolicy policy = requirePolicyById(ticket.getSlaPolicyId(), ticket.getId());
    ticket.setResolvedAt(null);
    ticket.setResolveDeadline(now.plusMinutes(policy.getResolveMinutes()));
    ticket.setResolveEscalatedAt(null);
    log.info("重开重算解决 SLA, ticketId={}, policyId={}, resolveDeadline={}",
        ticket.getId(), policy.getId(), ticket.getResolveDeadline());
  }

  /**
   * 把 deadline 后移指定分钟数。
   *
   * @param deadline      原 deadline，历史数据可能为 null
   * @param pausedMinutes 暂停分钟数，非负
   * @return 后移后的 deadline；入参为 null 时返回 null
   */
  private LocalDateTime shiftDeadline(LocalDateTime deadline, long pausedMinutes) {
    return deadline == null ? null : deadline.plusMinutes(pausedMinutes);
  }

  /**
   * 清空迁移需要置空的可空列。
   *
   * <p>
   * 必须走 {@code LambdaUpdateWrapper.set(..., null)}：{@code updateById} 会跳过 null 字段，
   * 不清空就会出现「已恢复仍显示暂停中」「已重开仍带解决时间」的脏读。
   * 这条 UPDATE 不参与乐观锁，版本号已在主更新里自增，此处不再重复判定。
   * </p>
   *
   * @param ticketId   工单 ID
   * @param transition 命中的迁移
   */
  private void clearNullableColumns(Long ticketId, TicketTransition transition) {
    switch (transition) {
      case RESUME -> ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
          .eq(Ticket::getId, ticketId)
          .set(Ticket::getPausedAt, null));
      case REOPEN -> ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
          .eq(Ticket::getId, ticketId)
          .set(Ticket::getResolvedAt, null)
          .set(Ticket::getResolveEscalatedAt, null));
      default -> {
      }
    }
  }

  /**
   * 构造状态迁移事件，用于时间线。
   *
   * <p>
   * 正文存备注原文；没有备注时留 null，由时间线按事件类型给出默认文案，
   * 不在这里拼「状态从 A 变到 B」这类可从 from/to 推出的冗余文本。
   * </p>
   *
   * @param ticketId   工单 ID
   * @param transition 命中的迁移
   * @param fromStatus 迁移前状态
   * @param comment    备注，可为 null
   * @param operatorId 操作人 ID
   * @param now        发生时间
   * @return 待插入的事件实体
   */
  private TicketEvent buildTransitionEvent(Long ticketId, TicketTransition transition, String fromStatus,
      String comment, Long operatorId, LocalDateTime now) {
    TicketEvent event = new TicketEvent();
    event.setTicketId(ticketId);
    event.setType(transition.eventType().name());
    event.setFromStatus(fromStatus);
    event.setToStatus(transition.targetStatus().name());
    event.setOperatorId(operatorId);
    event.setContent(comment);
    event.setCreatedAt(now);
    return event;
  }

  /**
   * 按 ID 取工单当前绑定的 SLA 策略，供重开重算解决 deadline 使用。
   *
   * <p>
   * 用 {@code ticket.sla_policy_id} 而不是按紧急度重新匹配：策略在暂停中被编辑过时，
   * 工单仍应沿用自己那份契约；{@code policy_id} 为空或策略行已被删除时无法重算，
   * 返回 503 让运维补配置，而不是用硬编码时长糊过去。
   * </p>
   *
   * @param policyId 工单的 SLA 策略 ID，可为 null
   * @param ticketId 工单 ID，仅用于日志
   * @return SLA 策略
   * @throws BusinessException 策略不存在时抛出 503
   */
  private SlaPolicy requirePolicyById(Long policyId, Long ticketId) {
    SlaPolicy policy = policyId == null ? null : slaPolicyMapper.selectById(policyId);
    if (policy == null) {
      log.error("重开时工单的 SLA 策略不存在, ticketId={}, policyId={}", ticketId, policyId);
      throw new BusinessException(503, "SLA 策略未配置，请联系管理员");
    }
    return policy;
  }
}
