package com.shenchao.codereview.task.pipeline;

import com.shenchao.codereview.task.entity.ReviewTaskFile;
import com.shenchao.codereview.task.queue.ReviewProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 工作目录扫描。
 *
 * <p>三个必须做对的边界：
 * <ol>
 *   <li><b>剪枝</b>：{@code .git} 里是成百上千个对象文件，走进去等于自残。
 *       用 {@link SimpleFileVisitor} 在目录层面直接跳过，而不是先遍历再过滤</li>
 *   <li><b>上限</b>：单任务文件数与单文件体积都有上限。真实仓库里总有生成代码、
 *       压缩过的常量表，不设限会让一个任务跑上半小时</li>
 *   <li><b>确定性</b>：结果排序后再返回。否则同一份代码两次审查的文件顺序不同，
 *       报告对比时会对不上</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceScanner {

    /** 目录名黑名单：构建产物与依赖目录，进入无意义且极慢。 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".idea", ".gradle", ".mvn", "target", "build", "out", "bin", "obj",
            "node_modules", "dist", "vendor", ".next", "coverage", "logs");

    /** 源码文件后缀（当前仅 Java，多语言时由 StaticAnalyzer SPI 决定）。 */
    private static final Set<String> SOURCE_SUFFIXES = Set.of(".java");

    private final ReviewProperties properties;

    public ScanResult scan(Path workspace, Long taskId) throws IOException {
        List<ScannedFile> files = new ArrayList<>();
        List<ReviewTaskFile> skipped = new ArrayList<>();
        int[] counters = new int[]{0, 0};
        long maxBytes = (long) properties.maxFileSizeKb() * 1024;
        Path root = workspace.toAbsolutePath().normalize();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName().toString();
                if (SKIP_DIRS.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!properties.includeTests() && isTestDirectory(root, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || !isSourceFile(file)) {
                    return FileVisitResult.CONTINUE;
                }
                if (files.size() >= properties.maxFiles()) {
                    counters[1] = 1;
                    return FileVisitResult.TERMINATE;
                }
                if (attrs.size() > maxBytes) {
                    skipped.add(skipFile(taskId, root, file, "文件超过 " + properties.maxFileSizeKb() + "KB，已跳过"));
                    counters[0]++;
                    return FileVisitResult.CONTINUE;
                }
                files.add(new ScannedFile(file, root.relativize(file).toString().replace('\\', '/')));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 单个文件读不了（权限、符号链接失效）不应让整个任务失败
                log.warn("扫描文件失败，已跳过: file={}, cause={}", file, exc.getMessage());
                skipped.add(skipFile(taskId, root, file, "无法读取，已跳过"));
                return FileVisitResult.CONTINUE;
            }
        });

        files.sort(Comparator.comparing(ScannedFile::relativePath));
        boolean truncated = counters[1] == 1;
        log.info("工作目录扫描完成: root={}, 命中 {} 个文件, 跳过 {}, 因上限截断={}",
                root, files.size(), counters[0], truncated);
        return new ScanResult(files, skipped, truncated);
    }

    private boolean isSourceFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return SOURCE_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    /** 只认 {@code src/test} 这种结构性路径，不靠文件名里的 "Test" 猜。 */
    private boolean isTestDirectory(Path root, Path dir) {
        String relative = root.relativize(dir).toString().replace('\\', '/');
        return relative.contains("src/test") || relative.contains("/test/") || relative.startsWith("test/");
    }

    private ReviewTaskFile skipFile(Long taskId, Path root, Path file, String reason) {
        ReviewTaskFile row = new ReviewTaskFile();
        row.setTaskId(taskId);
        row.setFilePath(root.relativize(file).toString().replace('\\', '/'));
        row.setChangeType(ReviewTaskFile.CHANGE_SCANNED);
        row.setLanguage("JAVA");
        row.setAdditions(0);
        row.setDeletions(0);
        row.setAnalyzed(false);
        row.setSkipReason(reason);
        row.setDefectCount(0);
        return row;
    }

    /**
     * @param files     待分析文件（含绝对路径与仓库相对路径）
     * @param skipped   被跳过的文件（含跳过原因，供报告页展示）
     * @param truncated 是否因为文件数上限被截断
     */
    public record ScanResult(List<ScannedFile> files, List<ReviewTaskFile> skipped, boolean truncated) {

        /** 交给引擎的绝对路径列表。 */
        public List<Path> paths() {
            return files.stream().map(ScannedFile::path).toList();
        }
    }

    /**
     * 扫描命中的文件。
     *
     * @param path         绝对路径，供引擎读取
     * @param relativePath 仓库相对路径，供落库与前端展示
     */
    public record ScannedFile(Path path, String relativePath) {
    }
}
