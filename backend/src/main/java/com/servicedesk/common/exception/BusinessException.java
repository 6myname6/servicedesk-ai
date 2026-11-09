package com.servicedesk.common.exception;

import lombok.Getter;

/**
 * 业务异常：业务代码直接指定状态码与提示语抛出，由 {@link GlobalExceptionHandler} 转成统一响应体。
 *
 * <p>
 * 用法：{@code throw new BusinessException(404, "工单不存在")}。
 * 状态码与 HTTP 状态码保持一致，取值见 {@code docs/接口文档.md} 的错误码表。
 * </p>
 *
 * <p>
 * 约定：资源不存在与越权统一用 404，不区分 403/404，避免调用方枚举探测资源是否存在。
 * </p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务状态码，同时作为 HTTP 状态码返回。 */
    private final int code;

    /**
     * 构造业务异常。
     *
     * @param code    业务状态码，需与 HTTP 状态码一致，例如 400/401/403/404/409/429/503
     * @param message 面向用户的提示，不允许出现堆栈、SQL 或内部标识
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
