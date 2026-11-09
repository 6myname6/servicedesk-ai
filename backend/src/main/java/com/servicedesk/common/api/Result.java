package com.servicedesk.common.api;

import lombok.Data;

/**
 * 统一响应体，所有接口返回 {@code {code, message, data}} 三段结构。
 *
 * <p>
 * 属于公共 API 层。{@code code} 与 HTTP 状态码一致：200 成功，其余为失败，
 * 前端只需判断 {@code code} 即可决定跳登录、提示无权限还是提示刷新重试。
 * </p>
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> {

    /** 成功状态码，与 HTTP 200 一致。 */
    private static final int SUCCESS_CODE = 200;

    /** 成功提示信息。 */
    private static final String SUCCESS_MESSAGE = "成功";

    /** 业务状态码，与 HTTP 状态码一致。 */
    private final int code;

    /** 提示信息，失败时为可直接展示给用户的中文文案。 */
    private final String message;

    /** 业务数据，无数据时为 null。 */
    private final T data;

    /**
     * 全量构造响应体，仅供本类静态工厂使用。
     *
     * @param code    业务状态码
     * @param message 提示信息
     * @param data    业务数据，可为 null
     */
    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 构造带数据的成功响应。
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return code=200 的响应体
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    /**
     * 构造无数据的成功响应，用于删除、登出等只关心成功与否的接口。
     *
     * @return data 为 null 的响应体
     */
    public static Result<Void> success() {
        return new Result<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    /**
     * 构造失败响应，由全局异常处理器调用。
     *
     * @param code    失败状态码，需与 HTTP 状态码一致
     * @param message 面向用户的提示
     * @param <T>     业务数据类型
     * @return 失败响应体
     */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
