package com.servicedesk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.enums.CustomerLevel;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.SysUserMapper;
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
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户档案接口的集成测试（M-03）。
 *
 * <p>
 * 属于接口层测试，启动完整上下文并走真实拦截器与 MySQL；数据由用例自建，
 * 依靠 {@code @Transactional} 回滚，不污染开发库。
 * </p>
 *
 * <p>
 * 重点覆盖三类风险：越权（报修人 403）、等级归一化（默认 NORMAL、大小写不敏感、
 * 非法值 400）、以及修改不存在客户的 404。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CustomerAdminControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** JSON 解析与构造工具。 */
  @Autowired
  private ObjectMapper objectMapper;

  /** 用于直接签发指定角色的 token，绕开登录流程。 */
  @Autowired
  private JwtUtil jwtUtil;

  /** 客户 Mapper，用于造数与断言落库结果。 */
  @Autowired
  private CustomerMapper customerMapper;

  /** 用户 Mapper，用于造账号数据。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** 密码编码器，保证造出的账号密码列符合 BCrypt 存储约定。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** 本次用例新建的主管账号，作为客户档案的合法操作者。 */
  private SysUser supervisor;

  /** 本次用例新建的管理员账号，作为客户档案的合法操作者。 */
  private SysUser admin;

  /** 本次用例新建的报修人账号，用于越权断言。 */
  private SysUser reporter;

  /**
   * 每个用例前准备主管、管理员、报修人三个账号。
   */
  @BeforeEach
  void setUp() {
    supervisor = insertUser(Role.SUPERVISOR);
    admin = insertUser(Role.ADMIN);
    reporter = insertUser(Role.REPORTER);
  }

  /**
   * 列表按客户名关键字过滤，并返回分页结构。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("列表：按客户名关键字分页过滤")
  void shouldPageCustomersByKeyword() throws Exception {
    String prefix = "ut客户" + System.nanoTime();
    insertCustomer(prefix + "-甲", "0792-1111222", CustomerLevel.NORMAL);
    insertCustomer(prefix + "-乙", "0792-3333444", CustomerLevel.KEY);

    mockMvc.perform(get("/admin/customers")
            .param("keyword", prefix)
            .param("page", "1")
            .param("size", "10")
            .header("Authorization", bearerOf(supervisor.getId(), Role.SUPERVISOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.total").value(2))
        .andExpect(jsonPath("$.data.records[0].name").value(startsWith(prefix)));
  }

  /**
   * 关键字同时匹配电话，方便坐席按来电号码定位客户。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("列表：关键字匹配电话")
  void shouldPageCustomersByPhone() throws Exception {
    String phone = "0792-8888999";
    insertCustomer("ut电话客户-" + System.nanoTime(), phone, CustomerLevel.NORMAL);

    mockMvc.perform(get("/admin/customers")
            .param("keyword", "8888999")
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.records[0].phone").value(phone));
  }

  /**
   * 新增客户未指定等级时落默认等级 NORMAL。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：未指定等级时默认 NORMAL")
  void shouldCreateCustomerWithDefaultLevel() throws Exception {
    String name = "精工机械-" + System.nanoTime();

    mockMvc.perform(post("/admin/customers")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(name, null, "刘经理", "0792-0000000", "九江市濂溪区", "年框客户"))
            .header("Authorization", bearerOf(supervisor.getId(), Role.SUPERVISOR)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(name))
        .andExpect(jsonPath("$.data.level").value("NORMAL"))
        .andExpect(jsonPath("$.data.contact").value("刘经理"));

    Customer saved = findByCustomerName(name);
    assertThat(saved).isNotNull();
    assertThat(saved.getLevel()).isEqualTo("NORMAL");
  }

  /**
   * 等级大小写不敏感，落库统一为大写枚举名。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：小写 key 归一化为 KEY")
  void shouldNormalizeLevelCase() throws Exception {
    String name = "重点客户-" + System.nanoTime();

    mockMvc.perform(post("/admin/customers")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(name, "key", null, null, null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.level").value("KEY"));

    assertThat(findByCustomerName(name).getLevel()).isEqualTo("KEY");
  }

  /**
   * 等级取值非法返回 400，而不是静默存成非法字符串。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：等级非法返回 400")
  void shouldRejectInvalidLevel() throws Exception {
    mockMvc.perform(post("/admin/customers")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("等级非法客户-" + System.nanoTime(), "VIP", null, null, null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("客户等级取值非法"));
  }

  /**
   * 客户名为空返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：客户名为空返回 400")
  void shouldRejectBlankCustomerName() throws Exception {
    mockMvc.perform(post("/admin/customers")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("  ", null, null, null, null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 报修人不能维护客户档案。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("权限：报修人访问客户接口返回 403")
  void shouldRejectReporter() throws Exception {
    mockMvc.perform(get("/admin/customers")
            .header("Authorization", bearerOf(reporter.getId(), Role.REPORTER)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  /**
   * 修改客户档案成功，可选字段整体覆盖。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：成功覆盖客户资料")
  void shouldUpdateCustomer() throws Exception {
    Customer customer = insertCustomer("待更名客户-" + System.nanoTime(), "0792-1234567", CustomerLevel.NORMAL);
    String newName = "更名后客户-" + System.nanoTime();

    mockMvc.perform(put("/admin/customers/{id}", customer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(newName, "KEY", "张经理", "0792-7654321", "九江市浔阳区", "升级为重点客户"))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(newName))
        .andExpect(jsonPath("$.data.level").value("KEY"))
        .andExpect(jsonPath("$.data.contact").value("张经理"))
        .andExpect(jsonPath("$.data.remark").value("升级为重点客户"));

    Customer updated = customerMapper.selectById(customer.getId());
    assertThat(updated.getName()).isEqualTo(newName);
    assertThat(updated.getLevel()).isEqualTo("KEY");
    assertThat(updated.getPhone()).isEqualTo("0792-7654321");
  }

  /**
   * 修改不存在的客户返回 404。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：客户不存在返回 404")
  void shouldReturn404WhenCustomerMissing() throws Exception {
    mockMvc.perform(put("/admin/customers/{id}", 999_999_999L)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("任意客户", null, null, null, null, null))
            .header("Authorization", bearerOf(supervisor.getId(), Role.SUPERVISOR)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("客户不存在"));
  }

  /**
   * 构造客户新增 / 修改的请求体。
   *
   * @param name    客户名
   * @param level   等级，可为 null
   * @param contact 联系人，可为 null
   * @param phone   电话，可为 null
   * @param address 地址，可为 null
   * @param remark  备注，可为 null
   * @return JSON 字符串
   * @throws Exception JSON 序列化异常
   */
  private String createBody(String name, String level, String contact, String phone, String address, String remark)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("level", level);
    body.put("contact", contact);
    body.put("phone", phone);
    body.put("address", address);
    body.put("remark", remark);
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
   * @param role 角色
   * @return 插入后的账号实体（含自增 ID）
   */
  private SysUser insertUser(Role role) {
    SysUser user = new SysUser();
    user.setUsername("ut_customer_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "测试账号");
    user.setRole(role.name());
    user.setStatus(1);
    sysUserMapper.insert(user);
    return user;
  }

  /**
   * 直接插入一条客户，绕过接口，用于造数。
   *
   * @param name  客户名
   * @param phone 电话
   * @param level 等级
   * @return 插入后的客户实体（含自增 ID）
   */
  private Customer insertCustomer(String name, String phone, CustomerLevel level) {
    Customer customer = new Customer();
    customer.setName(name);
    customer.setPhone(phone);
    customer.setLevel(level.name());
    customerMapper.insert(customer);
    return customer;
  }

  /**
   * 按客户名查询客户。
   *
   * @param name 客户名
   * @return 客户实体，不存在时为 null
   */
  private Customer findByCustomerName(String name) {
    return customerMapper.selectOne(
        com.baomidou.mybatisplus.core.toolkit.Wrappers.<Customer>lambdaQuery().eq(Customer::getName, name));
  }
}
