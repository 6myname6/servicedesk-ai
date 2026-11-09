package com.servicedesk.common.exception;

import com.servicedesk.common.api.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器，把异常统一转成 {@code {code, message, data}} 响应体。
 *
 * <p>
 * 属于公共异常层。处理原则：
 * </p>
 * <ul>
 * <li>业务异常按 {@link BusinessException#getCode()} 返回，HTTP 状态与 {@code code}
 * 一致；</li>
 * <li>参数类异常统一 400，提示精确到字段；</li>
 * <li>未知异常统一 500，堆栈只进日志，响应体不暴露内部细节。</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  /** 参数类错误统一状态码。 */
  private static final int BAD_REQUEST = 400;

  /** 未知异常状态码。 */
  private static final int INTERNAL_ERROR = 500;

  /**
   * 处理业务异常。
   *
   * @param ex 业务异常，携带状态码与提示语
   * @return 状态码与异常一致的响应体
   */
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<Result<Void>> handleBizException(BusinessException ex) {
    return buildResponse(ex.getCode(), ex.getMessage());
  }

  /**
   * 处理参数绑定与校验失败。
   *
   * <p>
   * 同时覆盖两种场景：{@code @RequestBody @Valid} 抛出的
   * {@code MethodArgumentNotValidException}，以及查询对象上 {@code @Valid} 抛出的
   * {@link BindException}。前者是后者的子类，因此这里只声明父类即可全部接住。
   * </p>
   *
   * @param ex 参数绑定异常，携带字段级错误
   * @return code=400 的响应体
   */
  @ExceptionHandler(BindException.class)
  public ResponseEntity<Result<Void>> handleBindException(BindException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(this::formatFieldError)
        .collect(Collectors.joining("；"));
    return buildResponse(BAD_REQUEST, message);
  }

  /**
   * 处理方法参数校验失败（{@code @Validated} 作用于查询参数或路径参数）。
   *
   * @param ex 约束违反异常
   * @return code=400 的响应体
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException ex) {
    return buildResponse(BAD_REQUEST, ex.getMessage());
  }

  /**
   * 处理缺少必填请求参数。
   *
   * @param ex 缺少参数异常
   * @return code=400 的响应体
   */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Result<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
    return buildResponse(BAD_REQUEST, "缺少必填参数: " + ex.getParameterName());
  }

  /**
   * 处理参数类型不匹配，例如把非数字传给 Long 型路径参数。
   *
   * @param ex 类型不匹配异常
   * @return code=400 的响应体
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return buildResponse(BAD_REQUEST, "参数格式不正确: " + ex.getName());
  }

  /**
   * 处理请求体无法解析，例如 JSON 语法错误或字段类型与 DTO 不符。
   *
   * @param ex 消息转换异常
   * @return code=400 的响应体
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Result<Void>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
    return buildResponse(BAD_REQUEST, "请求体格式不正确");
  }

  /**
   * 兜底处理未预期异常。
   *
   * <p>
   * 完整堆栈只写日志，响应体固定为「服务端错误」，避免泄露实现细节。
   * </p>
   *
   * @param ex 未捕获异常
   * @return code=500 的响应体
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Result<Void>> handleUnknownException(Exception ex) {
    log.error("未捕获异常", ex);
    return buildResponse(INTERNAL_ERROR, "服务端错误");
  }

  /**
   * 把字段校验错误格式化为「字段名: 提示」。
   *
   * @param fieldError 字段错误
   * @return 可读提示，例如 {@code title: 标题不能为空}
   */
  private String formatFieldError(FieldError fieldError) {
    return fieldError.getField() + ": " + fieldError.getDefaultMessage();
  }

  /**
   * 构造状态码与响应体一致的实体。
   *
   * @param code    业务状态码，同时作为 HTTP 状态码
   * @param message 提示信息
   * @return 响应实体
   */
  private ResponseEntity<Result<Void>> buildResponse(int code, String message) {
    return ResponseEntity.status(code).body(Result.error(code, message));
  }
}
