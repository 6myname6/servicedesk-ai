package com.servicedesk.controller;

import com.servicedesk.common.api.Result;
import com.servicedesk.service.FileAttachmentService;
import com.servicedesk.vo.AttachmentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件接口，对应接口文档第 4 章（F-05）。
 *
 * <p>
 * 属于接口层，只做参数接收与响应包装。两个接口都只要求登录，不限制角色；
 * 删除的「上传人 / 管理员」判断在业务层完成，越权统一 404。
 * </p>
 *
 * <p>
 * 路径不含 {@code /api}：{@code server.servlet.context-path} 在进入 DispatcherServlet
 * 前已被剥离。
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/files")
public class FileController {

  /** 附件服务。 */
  private final FileAttachmentService fileAttachmentService;

  /**
   * 上传文件，写入 OSS 并返回可访问 URL。
   *
   * @param file    文件，multipart 字段名 {@code file}
   * @param bizType 业务类型 {@code TICKET}（默认）/ {@code KB}
   * @return 附件信息，见接口文档附录 A.11
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Result<AttachmentVO> upload(
      @RequestPart("file") MultipartFile file,
      @RequestParam(value = "bizType", required = false) String bizType) {
    log.debug("收到上传文件请求, fileName={}, size={}, bizType={}",
        file.getOriginalFilename(), file.getSize(), bizType);
    return Result.success(fileAttachmentService.upload(file, bizType));
  }

  /**
   * 删除附件（记录 + OSS 对象）。
   *
   * @param id 附件 ID
   * @return 无数据成功响应
   */
  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    log.debug("收到删除附件请求, attachmentId={}", id);
    fileAttachmentService.delete(id);
    return Result.success();
  }
}
