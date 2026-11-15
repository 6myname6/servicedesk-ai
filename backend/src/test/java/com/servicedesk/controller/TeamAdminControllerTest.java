package com.servicedesk.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.Team;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TeamMapper;
import com.servicedesk.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 团队管理接口的集成测试（M-02）。
 *
 * <p>
 * 属于接口层测试，启动完整上下文并走真实拦截器与 MySQL；数据由用例自建，
 * 依靠 {@code @Transactional} 回滚，不污染开发库。
 * </p>
 *
 * <p>
 * 重点覆盖三类风险：越权（非管理员 403）、唯一性（团队名 409）、
 * 以及逻辑外键校验（主管 ID 不存在时返回 400 而不是写进脏数据）。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TeamAdminControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 用例自建主管账号的显示名，用于断言回填结果。 */
  private static final String SUPERVISOR_NAME = "王主管";

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** JSON 解析与构造工具。 */
  @Autowired
  private ObjectMapper objectMapper;

  /** 用于直接签发指定角色的 token，绕开登录流程。 */
  @Autowired
  private JwtUtil jwtUtil;

  /** 用户 Mapper，用于造账号数据。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** 团队 Mapper，用于造团队数据与断言落库结果。 */
  @Autowired
  private TeamMapper teamMapper;

  /** 密码编码器，保证造出的账号密码列符合 BCrypt 存储约定。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** 本次用例新建的管理员账号，作为请求发起者。 */
  private SysUser admin;

  /** 本次用例新建的主管账号，作为团队主管。 */
  private SysUser supervisor;

  /**
   * 每个用例前准备一个管理员与一个主管账号。
   */
  @BeforeEach
  void setUp() {
    admin = insertUser(Role.ADMIN, null, "管理员");
    supervisor = insertUser(Role.SUPERVISOR, null, SUPERVISOR_NAME);
  }

  /**
   * 列表接口返回主管姓名与实时成员数。
   *
   * <p>
   * 用 JSONPath 过滤到本用例新建的团队，避免开发库里已有团队影响断言。
   * </p>
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("列表：回填主管姓名与成员数")
  void shouldListTeamsWithSupervisorAndMemberCount() throws Exception {
    Team team = insertTeam("华东售后一组-" + System.nanoTime(), supervisor.getId());
    insertUser(Role.ENGINEER, team.getId(), "工程师甲");
    insertUser(Role.ENGINEER, team.getId(), "工程师乙");

    String filter = "$.data[?(@.id == " + team.getId() + ")]";
    mockMvc.perform(get("/admin/teams").header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath(filter + ".name").value(hasItem(team.getName())))
        .andExpect(jsonPath(filter + ".supervisorName").value(hasItem(SUPERVISOR_NAME)))
        .andExpect(jsonPath(filter + ".memberCount").value(hasItem(2)));
  }

  /**
   * 非管理员访问团队管理接口必须 403。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("权限：主管访问团队管理接口返回 403")
  void shouldRejectNonAdmin() throws Exception {
    mockMvc.perform(get("/admin/teams").header("Authorization", bearerOf(supervisor.getId(), Role.SUPERVISOR)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  /**
   * 未登录访问先被鉴权拦截器拦下，返回 401 而不是 403。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("权限：未登录访问返回 401")
  void shouldRejectWithoutToken() throws Exception {
    mockMvc.perform(get("/admin/teams"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  /**
   * 新建团队成功，成员数为 0，主管姓名回填。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新建：成功返回主管姓名且成员数为 0")
  void shouldCreateTeam() throws Exception {
    String name = "华南售后组-" + System.nanoTime();

    mockMvc.perform(post("/admin/teams")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(name, supervisor.getId(), "负责广东、广西"))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(name))
        .andExpect(jsonPath("$.data.supervisorId").value(supervisor.getId()))
        .andExpect(jsonPath("$.data.supervisorName").value(SUPERVISOR_NAME))
        .andExpect(jsonPath("$.data.memberCount").value(0))
        .andExpect(jsonPath("$.data.description").value("负责广东、广西"));

    Team saved = findByTeamName(name);
    assertThat(saved).isNotNull();
    assertThat(saved.getSupervisorId()).isEqualTo(supervisor.getId());
    assertThat(saved.getDescription()).isEqualTo("负责广东、广西");
  }

  /**
   * 团队名重复返回 409，而不是让唯一索引抛 500。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新建：团队名重复返回 409")
  void shouldRejectDuplicateTeamName() throws Exception {
    String name = "重复团队-" + System.nanoTime();
    insertTeam(name, null);

    mockMvc.perform(post("/admin/teams")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(name, null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("团队名已存在"));
  }

  /**
   * 主管 ID 是逻辑外键，不存在时必须返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新建：主管不存在返回 400")
  void shouldRejectMissingSupervisor() throws Exception {
    mockMvc.perform(post("/admin/teams")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("无主团队-" + System.nanoTime(), 999_999_999L, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("主管不存在"));
  }

  /**
   * 团队名为空返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新建：团队名为空返回 400")
  void shouldRejectBlankTeamName() throws Exception {
    mockMvc.perform(post("/admin/teams")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("   ", null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 修改团队成功：名称、主管、描述整体覆盖，并回填实时成员数。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：成功更新名称与主管并回填成员数")
  void shouldUpdateTeam() throws Exception {
    Team team = insertTeam("待更名团队-" + System.nanoTime(), null);
    insertUser(Role.ENGINEER, team.getId(), "工程师甲");
    String newName = "更名后团队-" + System.nanoTime();

    mockMvc.perform(put("/admin/teams/{id}", team.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(newName, supervisor.getId(), "负责福建"))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(newName))
        .andExpect(jsonPath("$.data.supervisorName").value(SUPERVISOR_NAME))
        .andExpect(jsonPath("$.data.memberCount").value(1))
        .andExpect(jsonPath("$.data.description").value("负责福建"));

    Team updated = teamMapper.selectById(team.getId());
    assertThat(updated.getName()).isEqualTo(newName);
    assertThat(updated.getSupervisorId()).isEqualTo(supervisor.getId());
  }

  /**
   * 修改不存在的团队返回 404。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：团队不存在返回 404")
  void shouldReturn404WhenTeamMissing() throws Exception {
    mockMvc.perform(put("/admin/teams/{id}", 999_999_999L)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("任意团队", null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("团队不存在"));
  }

  /**
   * 改成其他团队的名字返回 409，但保持原名提交必须成功（重名校验要排除自身）。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：与其他团队重名返回 409")
  void shouldRejectDuplicateNameOnUpdate() throws Exception {
    Team team = insertTeam("团队甲-" + System.nanoTime(), null);
    Team other = insertTeam("团队乙-" + System.nanoTime(), null);

    mockMvc.perform(put("/admin/teams/{id}", team.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(other.getName(), null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    mockMvc.perform(put("/admin/teams/{id}", team.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(team.getName(), null, "只改描述"))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.description").value("只改描述"));
  }

  /**
   * 构造团队新增 / 修改的请求体。
   *
   * @param name         团队名
   * @param supervisorId 主管 ID，可为 null
   * @param description  描述，可为 null
   * @return JSON 字符串
   * @throws Exception JSON 序列化异常
   */
  private String createBody(String name, Long supervisorId, String description) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("supervisorId", supervisorId);
    body.put("description", description);
    return objectMapper.writeValueAsString(body);
  }

  /**
   * 为指定用户与角色签发鉴权请求头。
   *
   * @param userId 用户 ID
   * @param role   角色
   * @return 形如 {@code Bearer xxx} 的请求头取值
   */
  private String bearerOf(Long userId, Role role) {
    LoginUser user = new LoginUser(userId, "ut_operator", "操作员", role.name(), null);
    return "Bearer " + jwtUtil.generate(user);
  }

  /**
   * 直接插入一条账号，绕过接口，用于造数。
   *
   * @param role   角色
   * @param teamId 所属团队 ID，可为 null
   * @param name   显示名
   * @return 插入后的账号实体（含自增 ID）
   */
  private SysUser insertUser(Role role, Long teamId, String name) {
    SysUser user = new SysUser();
    user.setUsername("ut_team_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(name);
    user.setRole(role.name());
    user.setTeamId(teamId);
    user.setStatus(1);
    sysUserMapper.insert(user);
    return user;
  }

  /**
   * 直接插入一个团队，绕过接口，用于造数。
   *
   * @param name         团队名
   * @param supervisorId 主管 ID，可为 null
   * @return 插入后的团队实体（含自增 ID）
   */
  private Team insertTeam(String name, Long supervisorId) {
    Team team = new Team();
    team.setName(name);
    team.setSupervisorId(supervisorId);
    teamMapper.insert(team);
    return team;
  }

  /**
   * 按团队名查询团队。
   *
   * @param name 团队名
   * @return 团队实体，不存在时为 null
   */
  private Team findByTeamName(String name) {
    return teamMapper.selectOne(Wrappers.<Team>lambdaQuery().eq(Team::getName, name));
  }
}
