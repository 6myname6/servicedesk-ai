package com.servicedesk.common.context;

import com.servicedesk.common.exception.BusinessException;

/**
 * 当前登录用户的线程上下文。
 *
 * <p>
 * 属于公共上下文层。由鉴权拦截器在请求进入时写入、请求结束时清理，业务代码通过
 * {@link #getRequired()} 读取，不感知身份来源。
 * </p>
 *
 * <p>
 * 必须清理：Tomcat 线程会被复用，若不在 {@code afterCompletion} 中调用 {@link #clear()}，
 * 上一个请求的用户身份会串到下一个请求上，属于严重越权缺陷。
 * </p>
 */
public final class UserContext {

    /** 当前线程的登录用户，未登录时为 null。 */
    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    /**
     * 工具类禁止实例化。
     */
    private UserContext() {
    }

    /**
     * 写入当前线程的登录用户。
     *
     * @param user 登录用户快照
     */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /**
     * 获取当前登录用户，可能为 null。
     *
     * @return 登录用户，未登录时为 null
     */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /**
     * 获取当前登录用户，未登录直接抛 401。
     *
     * <p>
     * 业务代码应优先使用本方法：既能拿到用户，又能在拦截器漏配时立刻暴露问题，
     * 而不是把 null 带进后续逻辑引发空指针。
     * </p>
     *
     * @return 登录用户，不会为 null
     * @throws BusinessException 未登录时抛出，状态码 401
     */
    public static LoginUser getRequired() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        return user;
    }

    /**
     * 获取当前登录用户 ID，未登录直接抛 401。
     *
     * @return 用户 ID
     * @throws BusinessException 未登录时抛出，状态码 401
     */
    public static Long getUserId() {
        return getRequired().id();
    }

    /**
     * 清理当前线程的登录用户，必须在请求结束时调用。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
