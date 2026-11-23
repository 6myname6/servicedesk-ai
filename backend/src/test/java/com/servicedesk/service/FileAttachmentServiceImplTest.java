package com.servicedesk.service;

import com.servicedesk.common.context.LoginUser;
import com.servicedesk.common.context.UserContext;
import com.servicedesk.common.exception.BusinessException;
import com.servicedesk.entity.TicketAttachment;
import com.servicedesk.mapper.TicketAttachmentMapper;
import com.servicedesk.service.impl.FileAttachmentServiceImpl;
import com.servicedesk.service.support.TicketAttachEventWriter;
import com.servicedesk.storage.FileStorageService;
import com.servicedesk.storage.StoredFile;
import com.servicedesk.vo.AttachmentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 附件服务的单元测试。
 *
 * <p>
 * 属于业务层测试。覆盖集成测试难以触发的分支：写库失败后补偿删除 OSS 对象、
 * 先删对象再删记录的顺序约束、越权时不触碰存储层。时钟固定为
 * 2026-11-16 10:00（东八区），断言落库的上传时间。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class FileAttachmentServiceImplTest {

  /** 固定时钟：2026-11-16 10:00:00（Asia/Shanghai）。 */
  private static final Clock FIXED_CLOCK = Clock.fixed(
      Instant.parse("2026-11-16T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  /** 当前登录用户 ID，删除权限判断依赖它。 */
  private static final long CURRENT_USER_ID = 7L;

  /** 附件存储 URL。 */
  private static final String FILE_URL = "https://cdn.example.com/ticket/202611/abc.jpg";

  /** 附件对象键。 */
  private static final String OBJECT_KEY = "ticket/202611/abc.jpg";

  /** 附件 Mapper 替身。 */
  @Mock
  private TicketAttachmentMapper attachmentMapper;

  /** 存储层替身。 */
  @Mock
  private FileStorageService fileStorageService;

  /** 附件事件写入器替身；本类用例的附件都未关联工单，删除时不应被调用。 */
  @Mock
  private TicketAttachEventWriter ticketAttachEventWriter;

  /** 被测服务。 */
  private FileAttachmentServiceImpl service;

  /**
   * 构造被测服务并写入登录上下文。
   */
  @BeforeEach
  void setUp() {
    service = new FileAttachmentServiceImpl(attachmentMapper, fileStorageService, FIXED_CLOCK,
        ticketAttachEventWriter);
    UserContext.set(new LoginUser(CURRENT_USER_ID, "ut_reporter", "报修人", "REPORTER", null));
  }

  /**
   * 清理线程上下文，避免影响其他用例。
   */
  @AfterEach
  void tearDown() {
    UserContext.clear();
  }

  /**
   * 上传成功：扩展名与大小写归一、上传人为当前用户、上传时间取注入时钟、ticketId 为空。
   */
  @Test
  @DisplayName("上传：成功后返回附件信息并落库")
  void shouldUploadAndReturnAttachment() {
    byte[] content = "hello".getBytes();
    MockMultipartFile file = new MockMultipartFile("file", "photo.JPG", "image/jpeg", content);
    when(fileStorageService.upload("ticket", "photo.JPG", "image/jpeg", content))
        .thenReturn(new StoredFile(OBJECT_KEY, FILE_URL));
    doAnswer(invocation -> {
      ((TicketAttachment) invocation.getArgument(0)).setId(99L);
      return 1;
    }).when(attachmentMapper).insert(any(TicketAttachment.class));

    AttachmentVO vo = service.upload(file, null);

    assertThat(vo.getId()).isEqualTo(99L);
    assertThat(vo.getFileName()).isEqualTo("photo.JPG");
    assertThat(vo.getFileUrl()).isEqualTo(FILE_URL);
    assertThat(vo.getFileSize()).isEqualTo(5L);

    ArgumentCaptor<TicketAttachment> captor = ArgumentCaptor.forClass(TicketAttachment.class);
    verify(attachmentMapper).insert(captor.capture());
    TicketAttachment saved = captor.getValue();
    assertThat(saved.getUploaderId()).isEqualTo(CURRENT_USER_ID);
    assertThat(saved.getTicketId()).isNull();
    assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 11, 16, 10, 0));
  }

  /**
   * 写库失败必须补偿删除已上传对象，避免产生孤儿文件。
   */
  @Test
  @DisplayName("上传：写库失败补偿删除 OSS 对象并返回 500")
  void shouldCompensateWhenInsertFails() {
    MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[] { 1 });
    when(fileStorageService.upload(anyString(), anyString(), anyString(), any(byte[].class)))
        .thenReturn(new StoredFile(OBJECT_KEY, FILE_URL));
    when(attachmentMapper.insert(any(TicketAttachment.class)))
        .thenThrow(new IllegalStateException("db down"));

    assertThatThrownBy(() -> service.upload(file, "TICKET"))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo(500);
          assertThat(ex.getMessage()).isEqualTo("附件保存失败，请稍后重试");
        });

    verify(fileStorageService).deleteByUrl(FILE_URL);
  }

  /**
   * 删除顺序必须是「先删对象、再删记录」，否则中途失败会留下无法重试的孤儿记录。
   */
  @Test
  @DisplayName("删除：先删 OSS 对象再删记录")
  void shouldDeleteObjectBeforeRecord() {
    TicketAttachment attachment = attachment(5L, CURRENT_USER_ID);
    when(attachmentMapper.selectById(5L)).thenReturn(attachment);

    service.delete(5L);

    InOrder order = inOrder(fileStorageService, attachmentMapper);
    order.verify(fileStorageService).deleteByUrl(FILE_URL);
    order.verify(attachmentMapper).deleteById(5L);
  }

  /**
   * 非上传人且非管理员统一 404，且不触碰存储层，避免枚举探测。
   */
  @Test
  @DisplayName("删除：非上传人返回 404 且不删除对象")
  void shouldRejectDeleteByOtherUser() {
    when(attachmentMapper.selectById(5L)).thenReturn(attachment(5L, 8L));

    assertThatThrownBy(() -> service.delete(5L))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo(404);
          assertThat(ex.getMessage()).isEqualTo("附件不存在");
        });

    verify(fileStorageService, never()).deleteByUrl(anyString());
    verify(attachmentMapper, never()).deleteById(any(Long.class));
  }

  /**
   * 空文件在业务层直接 400，不调用存储层。
   */
  @Test
  @DisplayName("上传：空文件返回 400")
  void shouldRejectEmptyFile() {
    MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[0]);

    assertThatThrownBy(() -> service.upload(file, null))
        .isInstanceOfSatisfying(BusinessException.class, ex -> {
          assertThat(ex.getCode()).isEqualTo(400);
          assertThat(ex.getMessage()).isEqualTo("文件不能为空");
        });

    verify(fileStorageService, never()).upload(anyString(), anyString(), anyString(), any(byte[].class));
  }

  /**
   * 构造一条附件记录。
   *
   * @param id         附件 ID
   * @param uploaderId 上传人 ID
   * @return 附件实体
   */
  private TicketAttachment attachment(Long id, Long uploaderId) {
    TicketAttachment attachment = new TicketAttachment();
    attachment.setId(id);
    attachment.setUploaderId(uploaderId);
    attachment.setFileName("photo.jpg");
    attachment.setFileUrl(FILE_URL);
    attachment.setFileSize(1024L);
    attachment.setMimeType("image/jpeg");
    return attachment;
  }
}
