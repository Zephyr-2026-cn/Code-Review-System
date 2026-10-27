package com.shenchao.codereview.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 分页查询基类。
 *
 * <p>边界处理：页码与页大小都在 setter 侧强制收敛到安全区间，
 * 避免前端传 {@code pageSize=100000} 导致一次查询拖垮数据库。
 */
@Schema(description = "分页查询参数")
public class PageQuery {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    @Schema(description = "页码，从 1 开始", defaultValue = "1")
    @Min(value = 1, message = "页码不能小于 1")
    private Integer page = 1;

    @Schema(description = "每页条数，最大 100", defaultValue = "20")
    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = MAX_PAGE_SIZE, message = "每页条数不能超过 " + MAX_PAGE_SIZE)
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    @Schema(description = "排序字段（白名单校验由各 Service 负责）")
    private String sortBy;

    @Schema(description = "是否降序", defaultValue = "true")
    private Boolean desc = Boolean.TRUE;

    public Integer getPage() {
        return page == null || page < 1 ? 1 : page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getPageSize() {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public String getSortBy() {
        return sortBy;
    }

    public void setSortBy(String sortBy) {
        this.sortBy = sortBy;
    }

    public Boolean getDesc() {
        return desc == null || desc;
    }

    public void setDesc(Boolean desc) {
        this.desc = desc;
    }

    /** 偏移量，供手写 SQL 使用。 */
    public long offset() {
        return (long) (getPage() - 1) * getPageSize();
    }
}
