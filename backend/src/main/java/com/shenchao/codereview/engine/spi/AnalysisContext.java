package com.shenchao.codereview.engine.spi;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 一次静态分析的输入。
 *
 * <p>刻意只包含"代码 + 配置"，不含任何 Spring、数据库或 HTTP 类型——
 * 这样对比实验（E1 三方案沙盘）可以脱离 Web 层直接构造上下文批量跑。
 *
 * @param projectId           项目 ID，仅用于日志定位
 * @param projectKey          项目编码（仓库目录名）
 * @param workspace           仓库工作目录绝对路径
 * @param files               待分析文件（绝对路径，已由切片阶段完成过滤与上限控制）
 * @param disabledRules       本次被关闭的规则编码，引擎侧做后置过滤
 * @param deadline            截止时间，引擎应在文件之间检查，超时即返回已得结果
 * @param maxViolationsPerFile 单文件缺陷上限，防止一条规则刷出上千条把结果表打爆
 */
public record AnalysisContext(
        Long projectId,
        String projectKey,
        Path workspace,
        List<Path> files,
        Set<String> disabledRules,
        Instant deadline,
        int maxViolationsPerFile
) {

    public AnalysisContext {
        files = files == null ? List.of() : List.copyOf(files);
        disabledRules = disabledRules == null ? Set.of() : Set.copyOf(disabledRules);
        deadline = deadline == null ? Instant.now().plusSeconds(300) : deadline;
        maxViolationsPerFile = maxViolationsPerFile <= 0 ? 200 : maxViolationsPerFile;
    }

    /** 绝对路径转仓库相对路径，统一成 {@code /} 分隔；不在 workspace 内时退化为文件名。 */
    public String relativePath(Path file) {
        try {
            Path root = workspace.toAbsolutePath().normalize();
            Path absolute = file.toAbsolutePath().normalize();
            if (absolute.startsWith(root)) {
                return root.relativize(absolute).toString().replace('\\', '/');
            }
        } catch (RuntimeException ignored) {
            // 不同磁盘卷时 relativize 会抛异常，退化为文件名即可，不影响分析
        }
        return file.getFileName().toString();
    }

    public boolean expired() {
        return Instant.now().isAfter(deadline);
    }
}
