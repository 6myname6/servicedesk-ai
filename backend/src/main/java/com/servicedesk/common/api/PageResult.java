package com.servicedesk.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Getter;

import java.util.List;

/**
 * 分页响应结构，接口出参统一使用本类，不直接暴露 MyBatis-Plus 的 {@link IPage}。
 *
 * <p>
 * 属于公共 API 层。只保留前端需要的五个字段，避免把 {@code orders}、{@code optimizeCountSql}、
 * {@code searchCount} 等分页实现细节写进接口契约。
 * </p>
 *
 * @param <T> 记录类型
 */
@Getter
public class PageResult<T> {

    /** 当前页数据，可能为空列表，不会是 null。 */
    private final List<T> records;

    /** 满足条件的总记录数。 */
    private final long total;

    /** 当前页码，从 1 开始。 */
    private final long curPage;

    /** 每页条数。 */
    private final long pageSize;

    /** 总页数，由分页插件计算，{@code total=0} 时为 0。 */
    private final long pages;

    /**
     * 私有构造，统一由 {@link #of(IPage)} 创建，保证字段来源一致。
     *
     * @param records  当前页数据
     * @param total    总记录数
     * @param curPage  当前页码
     * @param pageSize 每页条数
     * @param pages    总页数
     */
    private PageResult(List<T> records, long total, long curPage, long pageSize, long pages) {
        this.records = records;
        this.total = total;
        this.curPage = curPage;
        this.pageSize = pageSize;
        this.pages = pages;
    }

    /**
     * 把 MyBatis-Plus 的分页对象转换为对外的分页响应。
     *
     * @param page 分页查询结果，不能为 null
     * @param <T>  记录类型
     * @return 只含五个字段的分页响应
     */
    public static <T> PageResult<T> from(IPage<T> page) {
        return new PageResult<>(
                page.getRecords(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize(),
                page.getPages());
    }
}
