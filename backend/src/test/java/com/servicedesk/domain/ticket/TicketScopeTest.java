package com.servicedesk.domain.ticket;

import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.entity.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单数据范围的单元测试。
 *
 * <p>
 * 属于领域层测试，不启动 Spring 容器、不访问数据库。重点守住三件事：
 * 四角色分发正确；缺失必要身份信息时必须立刻报错而不是构造出"查不到任何行"的静默空条件；
 * 以及范围对象对外完全不可变，避免调用方事后改集合扩大可见范围。
 * </p>
 */
class TicketScopeTest {

  /** 业务状态码：登录态相关异常。 */
  private static final int UNAUTHORIZED = 401;

  /** 测试用用户 ID。 */
  private static final Long USER_ID = 2L;

  /** 测试用团队 ID。 */
  private static final Long TEAM_ID = 1L;

  /**
   * 管理员不携带任何身份条件，也不应因为其余参数为空而失败。
   */
  @Test
  @DisplayName("分发：ADMIN 返回 All，且不要求 userId 与 teamIds")
  void shouldDispatchAdminWithoutIdentity() {
    TicketScope scope = TicketScope.of(Role.ADMIN, null, null);

    assertThat(scope).isInstanceOf(TicketScope.All.class);
  }

  /**
   * 报修人只关心"谁报的"，不需要团队集合。
   */
  @Test
  @DisplayName("分发：REPORTER 携带 reporter 身份")
  void shouldDispatchReporter() {
    TicketScope scope = TicketScope.of(Role.REPORTER, USER_ID, List.of());

    assertThat(scope).isInstanceOf(TicketScope.Reporter.class);
    assertThat(((TicketScope.Reporter) scope).userId()).isEqualTo(USER_ID);
  }

  /**
   * 工程师只关心"派给谁"，不需要团队集合。
   */
  @Test
  @DisplayName("分发：ENGINEER 携带 assignee 身份")
  void shouldDispatchEngineer() {
    TicketScope scope = TicketScope.of(Role.ENGINEER, USER_ID, List.of());

    assertThat(scope).isInstanceOf(TicketScope.Engineer.class);
    assertThat(((TicketScope.Engineer) scope).userId()).isEqualTo(USER_ID);
  }

  /**
   * 主管按团队集合过滤。
   */
  @Test
  @DisplayName("分发：SUPERVISOR 携带团队集合")
  void shouldDispatchSupervisor() {
    TicketScope scope = TicketScope.of(Role.SUPERVISOR, USER_ID, List.of(TEAM_ID));

    assertThat(scope).isInstanceOf(TicketScope.Supervisor.class);
    assertThat(((TicketScope.Supervisor) scope).teamIds()).containsExactly(TEAM_ID);
  }

  /**
   * 报修人拿不到 userId 时必须报错。
   *
   * <p>
   * 若构造出 {@code Reporter(null)}，将来拼出的条件是 {@code reporter_id = null}，
   * 数据库不报错、只返回 0 行，表现为"我的工单全都不见了"，排查成本极高。
   * </p>
   */
  @Test
  @DisplayName("防御：REPORTER 缺 userId 抛 401")
  void shouldRejectReporterWithoutUserId() {
    assertThatThrownBy(() -> TicketScope.of(Role.REPORTER, null, List.of()))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(UNAUTHORIZED);
  }

  /**
   * 工程师拿不到 userId 时同样必须报错，理由与报修人一致。
   */
  @Test
  @DisplayName("防御：ENGINEER 缺 userId 抛 401")
  void shouldRejectEngineerWithoutUserId() {
    assertThatThrownBy(() -> TicketScope.of(Role.ENGINEER, null, List.of()))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(UNAUTHORIZED);
  }

  /**
   * 主管的团队集合为 null 说明调用方漏传，属于程序缺陷，必须报错。
   *
   * <p>
   * 与空集合要严格区分：null 是 bug，空集合是"这个主管确实没管任何团队"的合法状态。
   * 这里刻意不抛业务异常：它不是用户能自愈的问题，重登也没用，
   * 应当以 500 暴露并进错误日志，让开发者在联调阶段就发现。
   * </p>
   */
  @Test
  @DisplayName("防御：SUPERVISOR 的 teamIds 为 null 抛非业务异常")
  void shouldRejectSupervisorWithoutTeamCollection() {
    assertThatThrownBy(() -> TicketScope.of(Role.SUPERVISOR, USER_ID, null))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(BusinessException.class);
  }

  /**
   * 空团队集合必须能构造成功，语义是"什么都看不到"，不能退化成不限条件。
   */
  @Test
  @DisplayName("边界：SUPERVISOR 空团队集合合法，且不等于全量")
  void shouldAllowEmptyTeamCollection() {
    TicketScope scope = TicketScope.of(Role.SUPERVISOR, USER_ID, List.of());

    assertThat(scope).isInstanceOf(TicketScope.Supervisor.class);
    assertThat(((TicketScope.Supervisor) scope).teamIds()).isEmpty();
  }

  /**
   * 范围对象必须持有集合的不可变副本。
   *
   * <p>
   * 若直接持有调用方传入的集合引用，调用方事后 add 一个团队 ID 就能悄悄扩大可见范围，
   * 这类越权没有任何编译期或运行期提示，极难复现。
   * </p>
   */
  @Test
  @DisplayName("不可变：外部修改原集合不影响已构造的范围")
  void shouldCopyTeamIdsDefensively() {
    List<Long> mutableTeamIds = new ArrayList<>(List.of(TEAM_ID));
    TicketScope scope = TicketScope.of(Role.SUPERVISOR, USER_ID, mutableTeamIds);

    mutableTeamIds.add(999L);

    assertThat(((TicketScope.Supervisor) scope).teamIds()).containsExactly(TEAM_ID);
  }

  /**
   * 重复团队 ID 必须去重，避免生成冗余的 IN 条件。
   */
  @Test
  @DisplayName("去重：重复团队 ID 只保留一份")
  void shouldDeduplicateTeamIds() {
    TicketScope scope = TicketScope.of(Role.SUPERVISOR, USER_ID, List.of(TEAM_ID, TEAM_ID, 2L));

    assertThat(((TicketScope.Supervisor) scope).teamIds()).isEqualTo(Set.of(TEAM_ID, 2L));
  }

  /**
   * 管理员对任意一行都可见，包括三个字段全为 null 的脏数据。
   */
  @Test
  @DisplayName("判定：ADMIN 对任意行（含三字段全 null）可见")
  void shouldAllowEverythingForAdmin() {
    TicketScope scope = TicketScope.of(Role.ADMIN, null, null);

    assertThat(scope.allows(3L, 2L, 1L)).isTrue();
    assertThat(scope.allows(null, null, null)).isTrue();
  }

  /**
   * 报修人只按 reporterId 判定，即使自己是该单的处理人也不算命中。
   */
  @Test
  @DisplayName("判定：REPORTER 只认 reporterId")
  void shouldJudgeReporterByReporterIdOnly() {
    TicketScope scope = TicketScope.of(Role.REPORTER, USER_ID, List.of());

    assertThat(scope.allows(USER_ID, 999L, 1L)).isTrue();
    assertThat(scope.allows(999L, USER_ID, 1L)).isFalse();
    assertThat(scope.allows(null, null, null)).isFalse();
  }

  /**
   * 工程师只按 assigneeId 判定：同一客户报的单只要没派给他就不可见，这是责任边界。
   */
  @Test
  @DisplayName("判定：ENGINEER 只认 assigneeId")
  void shouldJudgeEngineerByAssigneeIdOnly() {
    TicketScope scope = TicketScope.of(Role.ENGINEER, USER_ID, List.of());

    assertThat(scope.allows(999L, USER_ID, 1L)).isTrue();
    assertThat(scope.allows(USER_ID, 999L, 1L)).isFalse();
    assertThat(scope.allows(null, null, null)).isFalse();
  }

  /**
   * 主管按工单归属团队判定。
   */
  @Test
  @DisplayName("判定：SUPERVISOR 在本团队内可见，跨团队不可见")
  void shouldJudgeSupervisorByTeamId() {
    TicketScope scope = TicketScope.of(Role.SUPERVISOR, USER_ID, List.of(TEAM_ID));

    assertThat(scope.allows(3L, 2L, TEAM_ID)).isTrue();
    assertThat(scope.allows(3L, 2L, 2L)).isFalse();
  }

  /**
   * 未派单工单的 team_id 为 null，判定必须安全地返回不可见。
   *
   * <p>
   * {@code Set.copyOf} 返回的不可变集合查询 null 会直接抛 NPE，
   * 因此实现里必须先判 {@code teamId != null}；漏掉就会让主管打开未派单工单时 500。
   * </p>
   */
  @Test
  @DisplayName("健壮性：SUPERVISOR 遇到 teamId 为 null 返回 false 且不抛 NPE")
  void shouldRejectNullTeamIdWithoutThrowing() {
    TicketScope scope = TicketScope.of(Role.SUPERVISOR, USER_ID, List.of(TEAM_ID));

    assertThat(scope.allows(3L, null, null)).isFalse();
  }

  /**
   * 主管没有管理任何团队时，任何工单都不可见——空集合不等于不限条件。
   */
  @Test
  @DisplayName("判定：SUPERVISOR 空团队集合全部不可见")
  void shouldDenyEverythingForSupervisorWithoutTeams() {
    TicketScope scope = TicketScope.of(Role.SUPERVISOR, USER_ID, List.of());

    assertThat(scope.allows(3L, 2L, TEAM_ID)).isFalse();
    assertThat(scope.allows(3L, 2L, null)).isFalse();
  }

  /**
   * 用户 ID 超出 {@code Long} 缓存范围（-128~127）时判定必须仍然正确。
   *
   * <p>
   * 用 {@code ==} 比较包装类型在此处会返回 false：两个 1000L 是不同的对象。
   * 这个缺陷在开发环境用小 ID 测试永远发现不了，上线后用户 ID 过 128 才集体越权。
   * </p>
   */
  @Test
  @DisplayName("相等判定：超出 Long 缓存范围的 ID 必须正确匹配")
  void shouldCompareIdsBeyondCacheRange() {
    Long storedUserId = Long.valueOf(1000L);
    Long candidateUserId = Long.valueOf(1000L);
    TicketScope scope = TicketScope.of(Role.REPORTER, storedUserId, List.of());

    assertThat(candidateUserId).isNotSameAs(storedUserId);
    assertThat(scope.allows(candidateUserId, null, null)).isTrue();
  }

  /**
   * 列表过滤用的可见性描述必须与 {@code allows} 同一口径，且字段互斥。
   */
  @Test
  @DisplayName("可见性：四角色下推条件与判定口径一致")
  void shouldExposeVisibilityForEachRole() {
    TicketScope.Visibility adminView = TicketScope.of(Role.ADMIN, null, null).visibility();
    assertThat(adminView.unrestricted()).isTrue();

    TicketScope.Visibility reporterView = TicketScope.of(Role.REPORTER, USER_ID, List.of()).visibility();
    assertThat(reporterView.unrestricted()).isFalse();
    assertThat(reporterView.reporterId()).isEqualTo(USER_ID);
    assertThat(reporterView.assigneeId()).isNull();

    TicketScope.Visibility engineerView = TicketScope.of(Role.ENGINEER, USER_ID, List.of()).visibility();
    assertThat(engineerView.assigneeId()).isEqualTo(USER_ID);
    assertThat(engineerView.reporterId()).isNull();

    TicketScope.Visibility supervisorView = TicketScope.of(Role.SUPERVISOR, USER_ID, List.of(TEAM_ID))
        .visibility();
    assertThat(supervisorView.reporterId()).isNull();
    assertThat(supervisorView.assigneeId()).isNull();
    assertThat(supervisorView.teamIds()).containsExactly(TEAM_ID);
  }

  /**
   * 主管没有团队时可见性描述是空集合，持久化层据此拼恒假条件；不能退化成「不过滤」。
   */
  @Test
  @DisplayName("可见性：主管空团队集合保持空集合")
  void shouldExposeEmptyTeamVisibility() {
    TicketScope.Visibility view = TicketScope.of(Role.SUPERVISOR, USER_ID, List.of()).visibility();

    assertThat(view.unrestricted()).isFalse();
    assertThat(view.teamIds()).isEmpty();
  }
}
