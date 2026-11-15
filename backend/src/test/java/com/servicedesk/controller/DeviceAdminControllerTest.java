package com.servicedesk.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.entity.Customer;
import com.servicedesk.entity.Device;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.enums.DeviceStatus;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.CustomerMapper;
import com.servicedesk.mapper.DeviceMapper;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备档案接口的集成测试（M-03 的设备部分）。
 *
 * <p>
 * 属于接口层测试，启动完整上下文并走真实拦截器与 MySQL；数据由用例自建，
 * 依靠 {@code @Transactional} 回滚，不污染开发库。
 * </p>
 *
 * <p>
 * 重点覆盖四类风险：越权（报修人 403）、序列号唯一（409）、逻辑外键（客户不存在 400）、
 * 以及保修状态计算口径（到期当天仍算保修内、未登记为 UNKNOWN）。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DeviceAdminControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 用例自建客户的名称，用于断言客户名回填。 */
  private static final String CUSTOMER_NAME = "九江精工机械";

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** JSON 解析与构造工具。 */
  @Autowired
  private ObjectMapper objectMapper;

  /** 用于直接签发指定角色的 token，绕开登录流程。 */
  @Autowired
  private JwtUtil jwtUtil;

  /** 设备 Mapper，用于造数与断言落库结果。 */
  @Autowired
  private DeviceMapper deviceMapper;

  /** 客户 Mapper，用于造客户档案。 */
  @Autowired
  private CustomerMapper customerMapper;

  /** 用户 Mapper，用于造账号数据。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** 密码编码器，保证造出的账号密码列符合 BCrypt 存储约定。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** 本次用例新建的管理员账号，作为设备档案的合法操作者。 */
  private SysUser admin;

  /** 本次用例新建的报修人账号，用于越权断言。 */
  private SysUser reporter;

  /** 本次用例新建的客户，作为设备的归属客户。 */
  private Customer customer;

  /**
   * 每个用例前准备管理员、报修人与一个客户。
   */
  @BeforeEach
  void setUp() {
    admin = insertUser(Role.ADMIN);
    reporter = insertUser(Role.REPORTER);
    customer = insertCustomer();
  }

  /**
   * 列表按客户过滤，并回填客户名与实时保修状态。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("列表：按客户过滤并计算保修状态")
  void shouldPageDevicesWithWarrantyStatus() throws Exception {
    LocalDate today = LocalDate.now();
    insertDevice("SN-EXPIRED-" + System.nanoTime(), today.minusDays(1), DeviceStatus.ACTIVE);
    insertDevice("SN-TODAY-" + System.nanoTime(), today, DeviceStatus.ACTIVE);
    Device unknown = insertDevice("SN-UNKNOWN-" + System.nanoTime(), null, DeviceStatus.ACTIVE);

    mockMvc.perform(get("/admin/devices")
            .param("customerId", String.valueOf(customer.getId()))
            .param("page", "1")
            .param("size", "10")
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.total").value(3))
        // 按 ID 倒序：最后插入的 UNKNOWN → 今天到期（保修内）→ 昨天到期（已过保）
        .andExpect(jsonPath("$.data.records[0].serialNo").value(unknown.getSerialNo()))
        .andExpect(jsonPath("$.data.records[0].warrantyStatus").value("UNKNOWN"))
        .andExpect(jsonPath("$.data.records[1].warrantyStatus").value("IN_WARRANTY"))
        .andExpect(jsonPath("$.data.records[2].warrantyStatus").value("EXPIRED"))
        .andExpect(jsonPath("$.data.records[0].customerName").value(CUSTOMER_NAME));
  }

  /**
   * 列表关键字匹配序列号。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("列表：关键字匹配序列号")
  void shouldPageDevicesBySerialNoKeyword() throws Exception {
    String serialNo = "SN-KEY-" + System.nanoTime();
    insertDevice(serialNo, null, DeviceStatus.ACTIVE);

    mockMvc.perform(get("/admin/devices")
            .param("keyword", serialNo)
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.records[0].serialNo").value(serialNo));
  }

  /**
   * 新增设备成功：状态默认 ACTIVE，客户名回填，保修状态按到期日计算。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：默认 ACTIVE 且保修内")
  void shouldCreateDevice() throws Exception {
    String serialNo = "SN-NEW-" + System.nanoTime();
    LocalDate today = LocalDate.now();

    mockMvc.perform(post("/admin/devices")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(serialNo, customer.getId(), "AC-2000",
                today.minusYears(1).toString(), today.plusYears(1).toString(), "一号车间", null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.serialNo").value(serialNo))
        .andExpect(jsonPath("$.data.customerName").value(CUSTOMER_NAME))
        .andExpect(jsonPath("$.data.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.warrantyStatus").value("IN_WARRANTY"))
        .andExpect(jsonPath("$.data.location").value("一号车间"));

    Device saved = findBySerialNo(serialNo);
    assertThat(saved).isNotNull();
    assertThat(saved.getStatus()).isEqualTo("ACTIVE");
    assertThat(saved.getCustomerId()).isEqualTo(customer.getId());
  }

  /**
   * 序列号重复返回 409，而不是让唯一索引抛 500。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：序列号重复返回 409")
  void shouldRejectDuplicateSerialNo() throws Exception {
    Device existing = insertDevice("SN-DUP-" + System.nanoTime(), null, DeviceStatus.ACTIVE);

    mockMvc.perform(post("/admin/devices")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(existing.getSerialNo(), customer.getId(), null, null, null, null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.message").value("序列号已存在"));
  }

  /**
   * 客户 ID 是逻辑外键，不存在时必须返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：客户不存在返回 400")
  void shouldRejectMissingCustomer() throws Exception {
    mockMvc.perform(post("/admin/devices")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("SN-NOCUS-" + System.nanoTime(), 999_999_999L, null, null, null, null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("客户不存在"));
  }

  /**
   * 保修到期日早于购买日期属于录入错误，返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：保修到期早于购买日期返回 400")
  void shouldRejectWarrantyBeforePurchase() throws Exception {
    LocalDate today = LocalDate.now();

    mockMvc.perform(post("/admin/devices")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("SN-BADDATE-" + System.nanoTime(), customer.getId(), null,
                today.toString(), today.minusDays(1).toString(), null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("保修到期日不能早于购买日期"));
  }

  /**
   * 序列号为空返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("新增：序列号为空返回 400")
  void shouldRejectBlankSerialNo() throws Exception {
    mockMvc.perform(post("/admin/devices")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("  ", customer.getId(), null, null, null, null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 修改设备：型号与保修日期整体覆盖，未传状态时保留原状态。
   *
   * <p>
   * 这条用例同时验证两件事：已报废设备不会被编辑弹窗漏传状态改回在用；
   * 清空保修到期日必须真的落成 NULL（按 updateById 会跳过 null 字段而失败）。
   * </p>
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：覆盖型号与保修日期，未传状态时保留 SCRAPPED")
  void shouldUpdateDeviceAndKeepStatus() throws Exception {
    Device device = insertDevice("SN-UPD-" + System.nanoTime(), LocalDate.now().plusYears(1),
        DeviceStatus.SCRAPPED);

    mockMvc.perform(put("/admin/devices/{id}", device.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(device.getSerialNo(), customer.getId(), "AC-3000", null, null, "二号车间", null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.model").value("AC-3000"))
        .andExpect(jsonPath("$.data.location").value("二号车间"))
        .andExpect(jsonPath("$.data.status").value("SCRAPPED"))
        .andExpect(jsonPath("$.data.warrantyStatus").value("UNKNOWN"));

    Device updated = deviceMapper.selectById(device.getId());
    assertThat(updated.getModel()).isEqualTo("AC-3000");
    assertThat(updated.getStatus()).isEqualTo("SCRAPPED");
    assertThat(updated.getWarrantyEnd()).isNull();
  }

  /**
   * 改成其他设备的序列号返回 409；保持自身序列号提交必须成功（唯一性校验要排除自身）。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：序列号与其他设备重复返回 409")
  void shouldRejectDuplicateSerialNoOnUpdate() throws Exception {
    Device device = insertDevice("SN-SELF-" + System.nanoTime(), null, DeviceStatus.ACTIVE);
    Device other = insertDevice("SN-OTHER-" + System.nanoTime(), null, DeviceStatus.ACTIVE);

    mockMvc.perform(put("/admin/devices/{id}", device.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(other.getSerialNo(), customer.getId(), null, null, null, null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(409));

    mockMvc.perform(put("/admin/devices/{id}", device.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(device.getSerialNo(), customer.getId(), "AC-1000", null, null, null, "ACTIVE"))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.model").value("AC-1000"));
  }

  /**
   * 修改不存在的设备返回 404。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("修改：设备不存在返回 404")
  void shouldReturn404WhenDeviceMissing() throws Exception {
    mockMvc.perform(put("/admin/devices/{id}", 999_999_999L)
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody("SN-404", customer.getId(), null, null, null, null, null))
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("设备不存在"));
  }

  /**
   * 报修人不能维护设备档案。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("权限：报修人访问设备接口返回 403")
  void shouldRejectReporter() throws Exception {
    mockMvc.perform(get("/admin/devices")
            .header("Authorization", bearerOf(reporter.getId(), Role.REPORTER)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  /**
   * 构建设备新增 / 修改的请求体，日期以 {@code yyyy-MM-dd} 字符串提交。
   *
   * @param serialNo     序列号
   * @param customerId   客户 ID
   * @param model        型号，可为 null
   * @param purchaseDate 购买日期字符串，可为 null
   * @param warrantyEnd  保修到期日字符串，可为 null
   * @param location     位置，可为 null
   * @param status       状态，可为 null
   * @return JSON 字符串
   * @throws Exception JSON 序列化异常
   */
  private String createBody(String serialNo, Long customerId, String model, String purchaseDate,
      String warrantyEnd, String location, String status) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("customerId", customerId);
    body.put("model", model);
    body.put("serialNo", serialNo);
    body.put("purchaseDate", purchaseDate);
    body.put("warrantyEnd", warrantyEnd);
    body.put("location", location);
    body.put("status", status);
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
    user.setUsername("ut_device_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "测试账号");
    user.setRole(role.name());
    user.setStatus(1);
    sysUserMapper.insert(user);
    return user;
  }

  /**
   * 直接插入一个客户，绕过接口，用于造数。
   *
   * @return 插入后的客户实体（含自增 ID）
   */
  private Customer insertCustomer() {
    Customer entity = new Customer();
    entity.setName(CUSTOMER_NAME);
    entity.setPhone("0792-" + System.nanoTime() % 1_000_000);
    entity.setLevel("NORMAL");
    customerMapper.insert(entity);
    return entity;
  }

  /**
   * 直接插入一台设备，绕过接口，用于造数。
   *
   * @param serialNo    序列号
   * @param warrantyEnd 保修到期日，可为 null
   * @param status      设备状态
   * @return 插入后的设备实体（含自增 ID）
   */
  private Device insertDevice(String serialNo, LocalDate warrantyEnd, DeviceStatus status) {
    Device device = new Device();
    device.setCustomerId(customer.getId());
    device.setModel("AC-2000");
    device.setSerialNo(serialNo);
    device.setWarrantyEnd(warrantyEnd);
    device.setStatus(status.name());
    deviceMapper.insert(device);
    return device;
  }

  /**
   * 按序列号查询设备。
   *
   * @param serialNo 序列号
   * @return 设备实体，不存在时为 null
   */
  private Device findBySerialNo(String serialNo) {
    return deviceMapper.selectOne(Wrappers.<Device>lambdaQuery().eq(Device::getSerialNo, serialNo));
  }
}
