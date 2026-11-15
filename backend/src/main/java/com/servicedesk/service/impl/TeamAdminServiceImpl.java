package com.servicedesk.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.dto.request.admin.TeamCreateRequest;
import com.servicedesk.dto.request.admin.TeamUpdateRequest;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Team;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
import com.servicedesk.service.TeamAdminService;
import com.servicedesk.vo.TeamVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 团队管理服务实现。
 *
 * <p>
 * 属于业务实现层。三条约定：
 * </p>
 *
 * <ul>
 * <li>团队名唯一性在插入/更新前显式检查，返回 409，不让唯一索引抛出 500；</li>
 * <li>主管只校验存在性，不限制角色：角色调整与团队归属是两件事，强制角色会让
 * 「先建团队再调角色」的顺序受限；</li>
 * <li>成员数与主管姓名批量回填，避免逐条查询造成 N+1。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamAdminServiceImpl implements TeamAdminService {

  /** 团队 Mapper。 */
  private final TeamMapper teamMapper;

  /** 用户 Mapper，用于统计成员数与回填主管姓名。 */
  private final SysUserMapper sysUserMapper;

  /**
   * 查询全部团队，回填主管姓名与成员数。
   *
   * <p>
   * 成员数用一次全表 {@code team_id} 查询后在内存聚合：团队十级、账号百级，
   * 比引入 group by 映射（依赖列别名与 Map 键大小写）更省心，也不会随团队数产生 N 次查询。
   * </p>
   *
   * @return 团队列表，按 ID 升序，无数据时为空列表
   */
  @Override
  public List<TeamVO> list() {
    List<Team> teams = teamMapper.selectList(Wrappers.<Team>lambdaQuery().orderByAsc(Team::getId));
    if (teams.isEmpty()) {
      return List.of();
    }
    Map<Long, String> supervisorNames = loadSupervisorNames(teams);
    Map<Long, Long> memberCounts = loadMemberCounts();

    return teams.stream()
        .map(team -> toVO(team, supervisorNames.get(team.getSupervisorId()),
            memberCounts.getOrDefault(team.getId(), 0L)))
        .toList();
  }

  /**
   * 新建团队。
   *
   * @param request 新增入参
   * @return 新建团队，成员数为 0
   * @throws BusinessException 团队名重复抛 409；主管不存在抛 400
   */
  @Override
  public TeamVO create(TeamCreateRequest request) {
    String name = request.getName().trim();
    if (existsByName(name, null)) {
      log.warn("新建团队失败：团队名已存在, name={}, operatorId={}", name, UserContext.getUserId());
      throw new BusinessException(409, "团队名已存在");
    }

    SysUser supervisor = requireSupervisor(request.getSupervisorId());

    Team team = new Team();
    team.setName(name);
    team.setSupervisorId(supervisor == null ? null : supervisor.getId());
    team.setDescription(trimToNull(request.getDescription()));
    teamMapper.insert(team);

    log.info("新建团队成功, teamId={}, name={}, supervisorId={}, operatorId={}",
        team.getId(), name, team.getSupervisorId(), UserContext.getUserId());
    return toVO(team, supervisor == null ? null : supervisor.getDisplayName(), 0L);
  }

  /**
   * 修改团队名、主管与描述。
   *
   * <p>
   * 重名校验排除自身：只改描述时团队名不变，不能因此返回 409。
   * </p>
   *
   * @param id      团队 ID
   * @param request 修改入参
   * @return 修改后的团队
   * @throws BusinessException 团队不存在抛 404；团队名与其他团队重复抛 409；主管不存在抛 400
   */
  @Override
  public TeamVO update(Long id, TeamUpdateRequest request) {
    Team team = requireTeam(id);

    String name = request.getName().trim();
    if (existsByName(name, id)) {
      log.warn("修改团队失败：团队名已存在, teamId={}, name={}, operatorId={}", id, name, UserContext.getUserId());
      throw new BusinessException(409, "团队名已存在");
    }

    SysUser supervisor = requireSupervisor(request.getSupervisorId());

    String beforeName = team.getName();
    team.setName(name);
    team.setSupervisorId(supervisor == null ? null : supervisor.getId());
    team.setDescription(trimToNull(request.getDescription()));
    teamMapper.updateById(team);

    log.info("修改团队成功, teamId={}, name={} -> {}, supervisorId={}, operatorId={}",
        id, beforeName, name, team.getSupervisorId(), UserContext.getUserId());
    return toVO(team, supervisor == null ? null : supervisor.getDisplayName(), countMembers(id));
  }

  /**
   * 按 ID 查询团队，不存在时抛 404。
   *
   * @param id 团队 ID
   * @return 团队实体
   * @throws BusinessException 团队不存在时抛出 404
   */
  private Team requireTeam(Long id) {
    Team team = teamMapper.selectById(id);
    if (team == null) {
      log.warn("团队不存在, teamId={}, operatorId={}", id, UserContext.getUserId());
      throw new BusinessException(404, "团队不存在");
    }
    return team;
  }

  /**
   * 校验主管存在性并返回主管账号。
   *
   * <p>
   * 主管是逻辑外键，数据库不会拦截不存在的 ID，因此必须在这里显式校验，
   * 否则会留下「团队挂在一个不存在的用户上」的脏数据。
   * </p>
   *
   * @param supervisorId 主管用户 ID，可为 null
   * @return 主管账号；supervisorId 为 null 时返回 null
   * @throws BusinessException 主管不存在时抛出 400
   */
  private SysUser requireSupervisor(Long supervisorId) {
    if (supervisorId == null) {
      return null;
    }
    SysUser supervisor = sysUserMapper.selectById(supervisorId);
    if (supervisor == null) {
      log.warn("主管不存在, supervisorId={}, operatorId={}", supervisorId, UserContext.getUserId());
      throw new BusinessException(400, "主管不存在");
    }
    return supervisor;
  }

  /**
   * 判断团队名是否已被占用。
   *
   * @param name      团队名（已去首尾空白）
   * @param excludeId 需要排除的团队 ID（修改时排除自己），新建时传 null
   * @return true 表示已存在
   */
  private boolean existsByName(String name, Long excludeId) {
    return teamMapper.exists(Wrappers.<Team>lambdaQuery()
        .eq(Team::getName, name)
        .ne(excludeId != null, Team::getId, excludeId));
  }

  /**
   * 批量加载主管姓名。
   *
   * @param teams 团队列表
   * @return 主管 ID 到姓名的映射，未指定主管的团队不包含在内
   */
  private Map<Long, String> loadSupervisorNames(List<Team> teams) {
    Set<Long> supervisorIds = teams.stream()
        .map(Team::getSupervisorId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    if (supervisorIds.isEmpty()) {
      return Map.of();
    }
    Collection<SysUser> supervisors = sysUserMapper.selectByIds(supervisorIds);
    return supervisors.stream().collect(Collectors.toMap(SysUser::getId, SysUser::getDisplayName));
  }

  /**
   * 统计各团队成员数。
   *
   * @return 团队 ID 到成员数的映射，无成员的团队不包含在内
   */
  private Map<Long, Long> loadMemberCounts() {
    List<SysUser> members = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
        .select(SysUser::getTeamId)
        .isNotNull(SysUser::getTeamId));
    return members.stream()
        .collect(Collectors.groupingBy(SysUser::getTeamId, Collectors.counting()));
  }

  /**
   * 统计单个团队的成员数。
   *
   * @param teamId 团队 ID
   * @return 成员数，无成员时为 0
   */
  private long countMembers(Long teamId) {
    return sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getTeamId, teamId));
  }

  /**
   * 实体转视图对象。
   *
   * @param team           团队实体
   * @param supervisorName 主管姓名，可为 null
   * @param memberCount    成员数
   * @return 团队视图
   */
  private TeamVO toVO(Team team, String supervisorName, long memberCount) {
    return TeamVO.builder()
        .id(team.getId())
        .name(team.getName())
        .supervisorId(team.getSupervisorId())
        .supervisorName(supervisorName)
        .memberCount(memberCount)
        .description(team.getDescription())
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
