package com.servicedesk.service.support;

import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.domain.ticket.TicketScope;
import com.servicedesk.entity.Team;
import com.servicedesk.mapper.TeamMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工单数据范围解析器的单元测试。
 *
 * <p>
 * 属于业务支撑层测试，用 Mockito 替身隔离数据库，验证两件事：
 * 四类角色是否被翻译成正确的范围；以及除主管外任何角色都不会产生额外查询。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class TicketScopeResolverTest {

  /** 测试用普通用户 ID。 */
  private static final Long USER_ID = 2L;

  /** 测试用团队 ID：主管自己所属的团队。 */
  private static final Long SELF_TEAM_ID = 1L;

  /** 测试用团队 ID：主管管理的第一个团队。 */
  private static final Long SUPERVISED_TEAM_A = 2L;

  /** 测试用团队 ID：主管管理的第二个团队。 */
  private static final Long SUPERVISED_TEAM_B = 3L;

  /** 团队 Mapper 替身。 */
  @Mock
  private TeamMapper teamMapper;

  /** 被测解析器。 */
  @InjectMocks
  private TicketScopeResolver resolver;

  /**
   * 管理员不需要任何身份字段，也不需要查团队。
   */
  @Test
  @DisplayName("ADMIN：返回 All 且不查库")
  void shouldReturnAllForAdmin() {
    TicketScope scope = resolver.resolve(buildUser(1L, "ADMIN", null));

    assertThat(scope).isInstanceOf(TicketScope.All.class);
    verifyNoInteractions(teamMapper);
  }

  /**
   * 报修人只需自己的 ID，不涉及团队查询。
   */
  @Test
  @DisplayName("REPORTER：返回 Reporter 且不查库")
  void shouldReturnReporterWithoutQuery() {
    TicketScope scope = resolver.resolve(buildUser(USER_ID, "REPORTER", null));

    assertThat(scope).isInstanceOf(TicketScope.Reporter.class);
    assertThat(((TicketScope.Reporter) scope).userId()).isEqualTo(USER_ID);
    verifyNoInteractions(teamMapper);
  }

  /**
   * 工程师同理：只看指派给自己的工单，与团队无关。
   */
  @Test
  @DisplayName("ENGINEER：返回 Engineer 且不查库")
  void shouldReturnEngineerWithoutQuery() {
    TicketScope scope = resolver.resolve(buildUser(USER_ID, "ENGINEER", SELF_TEAM_ID));

    assertThat(scope).isInstanceOf(TicketScope.Engineer.class);
    assertThat(((TicketScope.Engineer) scope).userId()).isEqualTo(USER_ID);
    verifyNoInteractions(teamMapper);
  }

  /**
   * 主管范围来自 team 表查询结果，并且只查一次。
   *
   * <p>
   * 这里刻意不断言 SQL 片段的列名：MyBatis-Plus 的 Lambda 列解析依赖容器启动时生成的
   * TableInfo 缓存，纯单测里拿不到，强行断言只会测出「lambda cache 缺失」而不是业务缺陷。
   * 列名归属由后续的仓储层集成测试与代码评审保证。
   * </p>
   */
  @Test
  @DisplayName("SUPERVISOR：查询一次团队并得到多个团队")
  void shouldQueryTeamsOnce() {
    when(teamMapper.selectList(any())).thenReturn(List.of(
        buildTeam(SUPERVISED_TEAM_A), buildTeam(SUPERVISED_TEAM_B)));

    TicketScope scope = resolver.resolve(buildUser(USER_ID, "SUPERVISOR", SELF_TEAM_ID));

    assertThat(scope).isInstanceOf(TicketScope.Supervisor.class);
    assertThat(((TicketScope.Supervisor) scope).teamIds())
        .containsExactlyInAnyOrder(SUPERVISED_TEAM_A, SUPERVISED_TEAM_B);
    verify(teamMapper, times(1)).selectList(any());
  }

  /**
   * 主管的关键差异：可见范围取自「管理的团队」，而不是 token 里「自己所属的团队」。
   *
   * <p>
   * 这个用例专门盯一种常见简化：若实现里图省事用了 {@code user.teamId()}，
   * 其余用例都能过，只有这里会失败。
   * </p>
   */
  @Test
  @DisplayName("SUPERVISOR：忽略 token 中的自身 teamId，只用查出来的管理团队")
  void shouldIgnoreOwnTeamIdForSupervisor() {
    when(teamMapper.selectList(any())).thenReturn(List.of(
        buildTeam(SUPERVISED_TEAM_A), buildTeam(SUPERVISED_TEAM_B)));

    TicketScope scope = resolver.resolve(buildUser(USER_ID, "SUPERVISOR", SELF_TEAM_ID));

    assertThat(((TicketScope.Supervisor) scope).teamIds())
        .containsExactlyInAnyOrder(SUPERVISED_TEAM_A, SUPERVISED_TEAM_B)
        .doesNotContain(SELF_TEAM_ID);
  }

  /**
   * 主管未管理任何团队时，范围为空集合，任何工单都不可见。
   */
  @Test
  @DisplayName("SUPERVISOR：查不到团队时范围为空且全部不可见")
  void shouldReturnEmptyScopeWhenSupervisorHasNoTeam() {
    when(teamMapper.selectList(any())).thenReturn(List.of());

    TicketScope scope = resolver.resolve(buildUser(USER_ID, "SUPERVISOR", SELF_TEAM_ID));

    assertThat(scope).isInstanceOf(TicketScope.Supervisor.class);
    assertThat(((TicketScope.Supervisor) scope).teamIds()).isEmpty();
    assertThat(scope.allows(3L, 2L, SELF_TEAM_ID)).isFalse();
  }

  /**
   * 查询结果意外为 null 时降级为空集合，不能把 null 传进领域对象触发 500。
   */
  @Test
  @DisplayName("SUPERVISOR：Mapper 返回 null 时降级为空集合")
  void shouldTreatNullResultAsEmpty() {
    when(teamMapper.selectList(any())).thenReturn(null);

    TicketScope scope = resolver.resolve(buildUser(USER_ID, "SUPERVISOR", SELF_TEAM_ID));

    assertThat(((TicketScope.Supervisor) scope).teamIds()).isEmpty();
  }

  /**
   * 角色取值非法时抛 401，且不应产生任何查询。
   */
  @Test
  @DisplayName("非法角色：抛 401 且不查库")
  void shouldRejectInvalidRole() {
    assertThatThrownBy(() -> resolver.resolve(buildUser(USER_ID, "ROOT", null)))
        .isInstanceOf(BusinessException.class)
        .extracting("code")
        .isEqualTo(401);
    verifyNoInteractions(teamMapper);
  }

  /**
   * 构造测试用户。
   *
   * @param id     用户 ID
   * @param role   角色字符串，允许非法值用于异常用例
   * @param teamId 用户自身所属团队 ID，可为 null
   * @return 登录用户快照
   */
  private LoginUser buildUser(Long id, String role, Long teamId) {
    return new LoginUser(id, "user_" + id, "测试用户", role, teamId);
  }

  /**
   * 构造测试团队。
   *
   * @param id 团队 ID
   * @return 团队实体
   */
  private Team buildTeam(Long id) {
    Team team = new Team();
    team.setId(id);
    team.setName("测试团队-" + id);
    return team;
  }
}
