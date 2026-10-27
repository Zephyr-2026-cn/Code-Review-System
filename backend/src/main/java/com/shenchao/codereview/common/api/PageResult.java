package com.shenchao.codereview.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页响应。
 *
 * <p>刻意不直接暴露 MyBatis-Plus 的 {@code IPage}：ORM 类型泄漏到接口层会导致前端契约随 ORM 版本漂移。
 *
 * @param records  当前页数据
 * @param total    总记录数
 * @param page     当前页码（从 1 开始）
 * @param pageSize 每页条数
 * @param pages    总页数
 */
@Schema(description = "分页结果")
public record PageResult<T>(
        @Schema(description = "当前页数据") List<T> records,
        @Schema(description = "总记录数") long total,
        @Schema(description = "当前页码") long page,
        @Schema(description = "每页条数") long pageSize,
        @Schema(description = "总页数") long pages
) {

    public static <T> PageResult<T> empty(long page, long pageSize) {
        return new PageResult<>(List.of(), 0L, page, pageSize, 0L);
    }

    public static <T> PageResult<T> of(List<T> records, long total, long page, long pageSize) {
        long pages = pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResult<>(records, total, page, pageSize, pages);
    }

    /** 实体页转 VO 页。 */
    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(records.stream().map(mapper).toList(), total, page, pageSize, pages);
    }
}
