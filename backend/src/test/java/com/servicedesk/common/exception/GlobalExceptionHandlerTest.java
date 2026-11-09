package com.servicedesk.common.exception;

import com.servicedesk.common.api.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理器与统一响应体的单元测试。
 *
 * <p>
 * 用 standalone MockMvc 装配探针 Controller，不启动 Spring 容器，
 * 覆盖四类出口：成功响应、业务异常、参数校验失败、未知异常。
 * </p>
 */
class GlobalExceptionHandlerTest {

  /** 被测入口，只加载探针 Controller 与全局异常处理器。 */
  private MockMvc mockMvc;

  /**
   * 每个用例前重建 MockMvc，显式装配 JSR-303 校验器，保证 {@code @Valid} 生效。
   */
  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .setValidator(new OptionalValidatorFactoryBean())
        .build();
  }

  /**
   * 成功路径：code=200、message=成功、data 原样返回。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("成功响应：code=200 且 data 原样返回")
  void shouldWrapSuccessResponse() throws Exception {
    mockMvc.perform(get("/probe/ok"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.message").value("成功"))
        .andExpect(jsonPath("$.data").value("hello"));
  }

  /**
   * 业务异常：HTTP 状态与响应体 code 一致，message 为抛出的具体文案。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("业务异常：404 且 message 为具体业务文案")
  void shouldMapBizExceptionToItsCode() throws Exception {
    mockMvc.perform(get("/probe/tickets/999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("工单不存在"))
        .andExpect(jsonPath("$.data").isEmpty());
  }

  /**
   * 请求体校验失败：返回 400，并提示到具体字段。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("参数校验失败：400 且提示具体字段")
  void shouldMapValidationFailureToBadRequest() throws Exception {
    mockMvc.perform(post("/probe/create")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("title: 标题不能为空"));
  }

  /**
   * 查询对象校验失败：走 BindException 分支，同样返回 400 并提示字段。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("查询对象校验失败：400 且提示具体字段")
  void shouldMapQueryObjectValidationToBadRequest() throws Exception {
    mockMvc.perform(get("/probe/page").param("pageSize", "999"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("pageSize: 每页条数不能超过 100"));
  }

  /**
   * 未知异常：返回 500，响应体不包含异常类型、堆栈或内部信息。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("未知异常：500 且不泄露内部信息")
  void shouldHideInternalDetailOnUnknownException() throws Exception {
    mockMvc.perform(get("/probe/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.message").value("服务端错误"));
  }

  /**
   * 路径参数类型不匹配：返回 400，而不是 500。
   *
   * @throws Exception MockMvc 调用异常
   */
  @Test
  @DisplayName("路径参数类型错误：400 且提示参数名")
  void shouldMapTypeMismatchToBadRequest() throws Exception {
    mockMvc.perform(get("/probe/ids/abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("参数格式不正确: id"));
  }

  /**
   * 探针接口，仅用于触发各类异常出口，不属于生产代码。
   */
  @RestController
  @RequestMapping("/probe")
  static class ProbeController {

    /**
     * 返回成功响应。
     *
     * @return 带数据的成功响应体
     */
    @GetMapping("/ok")
    Result<String> ok() {
      return Result.success("hello");
    }

    /**
     * 抛出 404 业务异常。
     *
     * @param id 工单 ID
     * @return 正常情况下不会返回
     */
    @GetMapping("/tickets/{id}")
    Result<Void> notFound(@PathVariable Long id) {
      throw new BusinessException(404, "工单不存在");
    }

    /**
     * 接收带校验的请求体。
     *
     * @param request 请求体，title 不能为空
     * @return 成功响应
     */
    @PostMapping("/create")
    Result<Void> create(@RequestBody @Valid ProbeRequest request) {
      return Result.success();
    }

    /**
     * 接收查询对象，用于触发查询参数校验失败（BindException）。
     *
     * @param query 分页参数，pageSize 上限 100
     * @return 成功响应
     */
    @GetMapping("/page")
    Result<Void> page(@Valid ProbeQuery query) {
      return Result.success();
    }

    /**
     * 抛出未被业务封装的异常。
     *
     * @return 正常情况下不会返回
     */
    @GetMapping("/boom")
    Result<Void> boom() {
      throw new IllegalStateException("connection refused: jdbc:mysql://localhost:3306");
    }

    /**
     * 声明 Long 型路径参数，用于触发类型不匹配。
     *
     * @param id 数字型 ID
     * @return 成功响应
     */
    @GetMapping("/ids/{id}")
    Result<Long> byId(@PathVariable Long id) {
      return Result.success(id);
    }
  }

  /**
   * 探针请求体，仅用于校验测试。
   */
  @Data
  static class ProbeRequest {

    /** 标题，不允许空白。 */
    @NotBlank(message = "标题不能为空")
    private String title;
  }

  /**
   * 探针查询对象，仅用于触发查询参数校验失败（BindException）。
   */
  @Data
  static class ProbeQuery {

    /** 每页条数，上限 100。 */
    @Max(value = 100, message = "每页条数不能超过 100")
    private Integer pageSize = 10;
  }
}
