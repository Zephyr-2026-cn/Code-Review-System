package com.shenchao.codereview.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;

/**
 * MyBatis-Plus 配置。
 *
 * <p>三个拦截器的必要性：
 * <ul>
 *   <li>{@link PaginationInnerInterceptor}：分页 + {@code maxLimit} 兜底，防止恶意大页查询</li>
 *   <li>{@link BlockAttackInnerInterceptor}：拦截无 where 条件的 update/delete，
 *       避免一次误操作清空整张表（这是生产事故高发点）</li>
 * </ul>
 */
@Slf4j
@Configuration
public class MybatisPlusConfig {

    /** 单页最大条数，与 {@code PageQuery.MAX_PAGE_SIZE} 保持一致。 */
    private static final long MAX_PAGE_LIMIT = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(MAX_PAGE_LIMIT);
        // 页码越界时回到第一页，而不是返回空列表——前端翻页会更友好
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }

    /** 审计字段自动填充，避免每个 Service 手写 createdAt/updatedAt。 */
    @Bean
    public MetaObjectHandler auditFieldMetaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                OffsetDateTime now = OffsetDateTime.now();
                strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
                strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
            }
        };
    }
}
