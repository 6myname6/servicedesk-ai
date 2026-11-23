package com.servicedesk.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.entity.SysUser;
import com.servicedesk.entity.TicketAttachment;
import com.servicedesk.entity.enums.Role;
import com.servicedesk.mapper.SysUserMapper;
import com.servicedesk.mapper.TicketAttachmentMapper;
import com.servicedesk.security.JwtUtil;
import com.servicedesk.storage.FileStorageService;
import com.servicedesk.storage.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件接口的集成测试（F-05）。
 *
 * <p>
 * 属于接口层测试：启动完整上下文，走真实拦截器与 MySQL，存储层用
 * {@link MockitoBean} 替身，避免单元测试依赖真实 OSS 账号与网络。
 * 数据由用例自建，依赖 {@code @Transactional} 回滚。
 * </p>
 *
 * <p>
 * 覆盖四类风险：类型/大小校验（400/413）、OSS 故障降级（503 且不落库）、
 * 删除权限（上传人 / 管理员 / 其他人 404）、删除顺序（先删对象后删记录）。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FileControllerTest {

  /** 测试用明文密码，满足 sys_user 的存储要求，用例不校验密码。 */
  private static final String RAW_PASSWORD = "Passw0rd!";

  /** 附件上传后返回的固定 URL，用于断言落库值与删除入参。 */
  private static final String STORED_URL = "https://cdn.example.com/ticket/202611/abc.jpg";

  /** 附件对象键，仅用于断言日志/补偿入参，不落库。 */
  private static final String OBJECT_KEY = "ticket/202611/abc.jpg";

  /** MockMvc 入口。 */
  @Autowired
  private MockMvc mockMvc;

  /** 用于直接签发指定角色的 token，绕开登录流程。 */
  @Autowired
  private JwtUtil jwtUtil;

  /** 附件 Mapper，用于造数与断言落库结果。 */
  @Autowired
  private TicketAttachmentMapper attachmentMapper;

  /** 用户 Mapper，用于造账号数据。 */
  @Autowired
  private SysUserMapper sysUserMapper;

  /** 密码编码器，保证造出的账号密码列符合 BCrypt 存储约定。 */
  @Autowired
  private PasswordEncoder passwordEncoder;

  /** 存储层替身：单测不触网，统一返回固定 URL。 */
  @MockitoBean
  private FileStorageService fileStorageService;

  /** 本次用例的附件上传人。 */
  private SysUser uploader;

  /** 本次用例的另一个报修人，用于越权断言。 */
  private SysUser other;

  /** 本次用例的管理员，可删除任意附件。 */
  private SysUser admin;

  /**
   * 每个用例前准备三类账号，并给存储替身设置默认返回值。
   */
  @BeforeEach
  void setUp() {
    uploader = insertUser(Role.REPORTER);
    other = insertUser(Role.REPORTER);
    admin = insertUser(Role.ADMIN);
    when(fileStorageService.upload(anyString(), anyString(), anyString(), any(byte[].class)))
        .thenReturn(new StoredFile(OBJECT_KEY, STORED_URL));
  }

  /**
   * 上传成功：返回附件信息、落库上传人与大小，并调用存储层。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("上传：成功返回 OSS URL 并落库")
  void shouldUploadFile() throws Exception {
    byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", content);

    mockMvc.perform(multipart("/files")
            .file(file)
            .header("Authorization", bearerOf(uploader.getId(), Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.id").isNumber())
        .andExpect(jsonPath("$.data.fileName").value("photo.jpg"))
        .andExpect(jsonPath("$.data.fileUrl").value(STORED_URL))
        .andExpect(jsonPath("$.data.fileSize").value(5))
        .andExpect(jsonPath("$.data.mimeType").value("image/jpeg"))
        .andExpect(jsonPath("$.data.createdAt").isNotEmpty());

    List<TicketAttachment> saved = attachmentsOfUploader();
    assertThat(saved).hasSize(1);
    TicketAttachment record = saved.get(0);
    assertThat(record.getUploaderId()).isEqualTo(uploader.getId());
    assertThat(record.getTicketId()).isNull();
    assertThat(record.getFileName()).isEqualTo("photo.jpg");
    assertThat(record.getFileUrl()).isEqualTo(STORED_URL);
    assertThat(record.getFileSize()).isEqualTo(5L);
    verify(fileStorageService).upload(eq("ticket"), eq("photo.jpg"), eq("image/jpeg"), aryEq(content));
  }

  /**
   * 知识库文档允许 docx，但工单附件不允许 exe，白名单按业务类型区分。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("上传：扩展名不在白名单返回 400")
  void shouldRejectUnsupportedExtension() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "evil.exe", "application/octet-stream",
        new byte[] { 1 });

    mockMvc.perform(multipart("/files")
            .file(file)
            .header("Authorization", bearerOf(uploader.getId(), Role.REPORTER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("不支持的文件类型"));

    verifyNoInteractions(fileStorageService);
    assertThat(attachmentsOfUploader()).isEmpty();
  }

  /**
   * 知识库文档不接受图片，避免把工单截图混进知识库解析链路。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("上传：KB 类型不接受图片返回 400")
  void shouldRejectImageForKbType() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[] { 1 });

    mockMvc.perform(multipart("/files")
            .file(file)
            .param("bizType", "KB")
            .header("Authorization", bearerOf(uploader.getId(), Role.REPORTER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("不支持的文件类型"));
  }

  /**
   * 超过工单附件 10MB 上限返回 413，而不是 500。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("上传：超过大小上限返回 413")
  void shouldRejectOversizedFile() throws Exception {
    byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];
    MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", tooLarge);

    mockMvc.perform(multipart("/files")
            .file(file)
            .header("Authorization", bearerOf(uploader.getId(), Role.REPORTER)))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value(413));

    verifyNoInteractions(fileStorageService);
  }

  /**
   * 业务类型不在字典内返回 400，不静默回退为 TICKET。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("上传：非法 bizType 返回 400")
  void shouldRejectUnknownBizType() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[] { 1 });

    mockMvc.perform(multipart("/files")
            .file(file)
            .param("bizType", "OTHER")
            .header("Authorization", bearerOf(uploader.getId(), Role.REPORTER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 缺少 file 字段返回 400。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("上传：缺少 file 字段返回 400")
  void shouldRejectMissingFilePart() throws Exception {
    mockMvc.perform(multipart("/files")
            .param("bizType", "TICKET")
            .header("Authorization", bearerOf(uploader.getId(), Role.REPORTER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  /**
   * 存储不可用时返回 503，且不留下无法访问的附件记录。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("上传：存储不可用返回 503 且不落库")
  void shouldReturn503WhenStorageUnavailable() throws Exception {
    when(fileStorageService.upload(anyString(), anyString(), anyString(), any(byte[].class)))
        .thenThrow(new BusinessException(503, "文件存储服务不可用，请稍后重试"));
    MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[] { 1 });

    mockMvc.perform(multipart("/files")
            .file(file)
            .header("Authorization", bearerOf(uploader.getId(), Role.REPORTER)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503));

    assertThat(attachmentsOfUploader()).isEmpty();
  }

  /**
   * 上传人删除自己的附件：记录删除且对象一并清理。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("删除：上传人删除成功并清理 OSS 对象")
  void shouldDeleteOwnAttachment() throws Exception {
    TicketAttachment attachment = insertAttachment(uploader.getId());

    mockMvc.perform(delete("/files/{id}", attachment.getId())
            .header("Authorization", bearerOf(uploader.getId(), Role.REPORTER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    assertThat(attachmentMapper.selectById(attachment.getId())).isNull();
    verify(fileStorageService).deleteByUrl(STORED_URL);
  }

  /**
   * 非上传人且非管理员删除返回 404，且不能借接口探测附件是否存在。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("删除：非上传人返回 404 且记录保留")
  void shouldRejectDeleteByOtherUser() throws Exception {
    TicketAttachment attachment = insertAttachment(uploader.getId());

    mockMvc.perform(delete("/files/{id}", attachment.getId())
            .header("Authorization", bearerOf(other.getId(), Role.REPORTER)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("附件不存在"));

    assertThat(attachmentMapper.selectById(attachment.getId())).isNotNull();
    verify(fileStorageService, never()).deleteByUrl(anyString());
  }

  /**
   * 管理员可以删除他人上传的附件。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("删除：管理员可删除他人附件")
  void shouldAllowAdminDelete() throws Exception {
    TicketAttachment attachment = insertAttachment(uploader.getId());

    mockMvc.perform(delete("/files/{id}", attachment.getId())
            .header("Authorization", bearerOf(admin.getId(), Role.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));

    assertThat(attachmentMapper.selectById(attachment.getId())).isNull();
  }

  /**
   * 附件不存在返回 404。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("删除：附件不存在返回 404")
  void shouldReturn404WhenAttachmentMissing() throws Exception {
    mockMvc.perform(delete("/files/{id}", 999_999_999L)
            .header("Authorization", bearerOf(uploader.getId(), Role.REPORTER)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  /**
   * 删除时存储故障返回 503，且记录保留，便于用户重试。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("删除：存储故障返回 503 且记录保留")
  void shouldKeepRecordWhenStorageFailsOnDelete() throws Exception {
    TicketAttachment attachment = insertAttachment(uploader.getId());
    doThrow(new BusinessException(503, "文件存储服务不可用，请稍后重试"))
        .when(fileStorageService).deleteByUrl(anyString());

    mockMvc.perform(delete("/files/{id}", attachment.getId())
            .header("Authorization", bearerOf(uploader.getId(), Role.REPORTER)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503));

    assertThat(attachmentMapper.selectById(attachment.getId())).isNotNull();
  }

  /**
   * 为指定用户与角色签发鉴权请求头。
   *
   * @param userId 用户 ID
   * @param role   角色
   * @return 形如 {@code Bearer xxx} 的请求头取值
   */
  private String bearerOf(Long userId, Role role) {
    LoginUser user = new LoginUser(userId, "ut_file", "附件测试账号", role.name(), null);
    return "Bearer " + jwtUtil.generate(user);
  }

  /**
   * 直接插入一条附件记录，绕过接口，用于删除用例造数。
   *
   * @param uploaderId 上传人 ID
   * @return 插入后的附件实体（含自增 ID）
   */
  private TicketAttachment insertAttachment(Long uploaderId) {
    TicketAttachment attachment = new TicketAttachment();
    attachment.setUploaderId(uploaderId);
    attachment.setFileName("photo.jpg");
    attachment.setFileUrl(STORED_URL);
    attachment.setFileSize(1024L);
    attachment.setMimeType("image/jpeg");
    attachment.setCreatedAt(LocalDateTime.now());
    attachmentMapper.insert(attachment);
    return attachment;
  }

  /**
   * 查询本次用例上传人产生的附件记录。
   *
   * <p>
   * 不能直接用「全表为空」做断言：开发库里常有手工联调留下的未关联附件，
   * 全表断言会随环境漂移而随机失败。用例只对自己造的数据负责。
   * </p>
   *
   * @return 该上传人的附件列表，按主键正序
   */
  private List<TicketAttachment> attachmentsOfUploader() {
    return attachmentMapper.selectList(Wrappers.<TicketAttachment>lambdaQuery()
        .eq(TicketAttachment::getUploaderId, uploader.getId())
        .orderByAsc(TicketAttachment::getId));
  }

  /**
   * 直接插入一条账号，绕过接口，用于造数。
   *
   * @param role 角色
   * @return 插入后的账号实体（含自增 ID）
   */
  private SysUser insertUser(Role role) {
    SysUser user = new SysUser();
    user.setUsername("ut_file_" + System.nanoTime());
    user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
    user.setDisplayName(role.name() + "附件测试账号");
    user.setRole(role.name());
    user.setStatus(1);
    sysUserMapper.insert(user);
    return user;
  }
}
