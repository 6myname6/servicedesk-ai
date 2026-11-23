package com.servicedesk.domain.ticket;

import com.servicedesk.entity.enums.Role;
import com.servicedesk.entity.enums.TicketEventType;
import com.servicedesk.entity.enums.TicketStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态迁移表的单元测试（F-02）。
 *
 * <p>
 * 属于领域层测试：不启动 Spring、不连库，只守两件事——8×8 全组合里哪些是合法迁移，
 * 以及每条迁移的目标状态、事件类型、允许角色与开发文档 7.13 一致。
 * 断言值全部写字面量，不引用实现侧常量，避免测试跟着实现一起漂移。
 * </p>
 */
class TicketTransitionTest {

  /**
   * 合法的「来源-&gt;目标」组合，逐条对应开发文档 7.13 的迁移表。
   *
   * <p>
   * 派单（NEW -&gt; ASSIGNED）与取消（各状态 -&gt; CANCELLED）走独立接口，
   * 不属于本表，因此不在此列。
   * </p>
   */
  private static final Set<String> LEGAL_PAIRS = Set.of(
      "ASSIGNED->ACCEPTED",
      "ACCEPTED->IN_PROGRESS",
      "IN_PROGRESS->RESOLVED",
      "RESOLVED->CLOSED",
      "RESOLVED->REJECTED");

  /**
   * 生成 8×8 的全部状态组合，供状态矩阵用例遍历。
   *
   * @return 64 个 (来源, 目标) 参数对
   */
  static Stream<Arguments> allStatusPairs() {
    return Stream.of(TicketStatus.values())
        .flatMap(from -> Stream.of(TicketStatus.values()).map(to -> Arguments.of(from, to)));
  }

  /**
   * 状态矩阵：只有 7.13 列出的 5 个组合能解析出迁移，其余全部为空。
   *
   * @param from 来源状态
   * @param to   目标状态
   */
  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("allStatusPairs")
  @DisplayName("状态矩阵：8×8 全组合中仅 5 个合法迁移")
  void shouldResolveOnlyLegalPairs(TicketStatus from, TicketStatus to) {
    Optional<TicketTransition> resolved = TicketTransition.resolve(from, to);

    if (LEGAL_PAIRS.contains(from.name() + "->" + to.name())) {
      assertThat(resolved).isPresent();
      assertThat(resolved.orElseThrow().targetStatus()).isEqualTo(to);
    } else {
      assertThat(resolved).isEmpty();
    }
  }

  /**
   * 每条迁移的起点集合、目标状态、事件类型与允许角色必须与契约一致。
   */
  @Test
  @DisplayName("迁移定义：起点、目标、事件类型与允许角色符合 7.13")
  void shouldDeclareTransitionDefinitions() {
    assertThat(TicketTransition.values()).hasSize(5);

    assertTransition(TicketTransition.ACCEPT, TicketStatus.ACCEPTED, TicketEventType.ACCEPT,
        Set.of(Role.ENGINEER), TicketStatus.ASSIGNED);
    assertTransition(TicketTransition.START, TicketStatus.IN_PROGRESS, TicketEventType.START,
        Set.of(Role.ENGINEER), TicketStatus.ACCEPTED);
    assertTransition(TicketTransition.RESOLVE, TicketStatus.RESOLVED, TicketEventType.RESOLVE,
        Set.of(Role.ENGINEER), TicketStatus.IN_PROGRESS);
    assertTransition(TicketTransition.CLOSE, TicketStatus.CLOSED, TicketEventType.CLOSE,
        Set.of(Role.REPORTER, Role.SUPERVISOR, Role.ADMIN), TicketStatus.RESOLVED);
    assertTransition(TicketTransition.REJECT, TicketStatus.REJECTED, TicketEventType.REJECT,
        Set.of(Role.REPORTER, Role.SUPERVISOR, Role.ADMIN), TicketStatus.RESOLVED);
  }

  /**
   * 终态没有任何出边，任何目标状态都解析不出迁移。
   */
  @Test
  @DisplayName("终态：CLOSED / REJECTED / CANCELLED 不能迁往任何状态")
  void shouldRejectTransitionsFromTerminalStatuses() {
    for (TicketStatus target : TicketStatus.values()) {
      assertThat(TicketTransition.resolve(TicketStatus.CLOSED, target)).isEmpty();
      assertThat(TicketTransition.resolve(TicketStatus.REJECTED, target)).isEmpty();
      assertThat(TicketTransition.resolve(TicketStatus.CANCELLED, target)).isEmpty();
    }
  }

  /**
   * 入参为 null 时返回空，调用方无需自行判空。
   */
  @Test
  @DisplayName("解析：null 入参返回空而不抛异常")
  void shouldReturnEmptyForNullArguments() {
    assertThat(TicketTransition.resolve(null, TicketStatus.CLOSED)).isEmpty();
    assertThat(TicketTransition.resolve(TicketStatus.RESOLVED, null)).isEmpty();
    assertThat(TicketTransition.resolve(null, null)).isEmpty();
  }

  /**
   * 断言一条迁移的对外定义。
   *
   * @param transition 迁移
   * @param target     目标状态
   * @param eventType  事件类型
   * @param roles      允许角色
   * @param froms      允许的起始状态
   */
  private void assertTransition(TicketTransition transition, TicketStatus target,
      TicketEventType eventType, Set<Role> roles, TicketStatus... froms) {
    assertThat(transition.targetStatus()).isEqualTo(target);
    assertThat(transition.eventType()).isEqualTo(eventType);
    assertThat(transition.allowedRoles()).containsExactlyInAnyOrderElementsOf(roles);
    assertThat(transition.fromStatuses()).containsExactlyInAnyOrder(froms);
  }
}
