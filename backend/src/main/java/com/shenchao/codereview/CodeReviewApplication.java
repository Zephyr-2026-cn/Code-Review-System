package com.shenchao.codereview;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 智能代码审查系统启动类。
 *
 * <p>关键约束：
 * <ul>
 *   <li>数据库方言固定 PostgreSQL，JSONB 字段依赖 JDBC 参数 {@code stringtype=unspecified}</li>
 *   <li>异步线程池按用途隔离（审计、审查任务），禁止共用默认 SimpleAsyncTaskExecutor</li>
 * </ul>
 */
@EnableAsync
@EnableTransactionManagement
@ConfigurationPropertiesScan
@MapperScan("com.shenchao.codereview.**.mapper")
@SpringBootApplication
public class CodeReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeReviewApplication.class, args);
    }
}
