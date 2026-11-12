package com.shenchao.codereview.engine.model;

import com.shenchao.codereview.engine.spi.RawDefect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 统一缺陷模型测试：指纹稳定性直接决定去重是否正确，必须有测试锁住。 */
class DefectTest {

    private RawDefect raw() {
        return new RawDefect("src\\main\\java\\A.java", 10, 12, "EmptyCatchBlock",
                DefectCategory.LOGIC_ERROR, Severity.MAJOR, "EmptyCatchBlock · 逻辑错误",
                "空的 catch 块", Map.of("engine", "pmd"));
    }

    @Test
    @DisplayName("路径统一为正斜杠并去掉开头的 ./")
    void normalizesPath() {
        assertThat(Defect.normalizePath("./src\\main\\A.java")).isEqualTo("src/main/A.java");
        assertThat(Defect.normalizePath(null)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("同一位置同一规则生成的指纹稳定，换行范围不影响")
    void fingerprintIsStable() {
        Defect first = Defect.fromRaw(raw(), DefectSource.STATIC, 0.9);
        Defect second = Defect.fromRaw(new RawDefect("src/main/java/A.java", 10, 99, "EmptyCatchBlock",
                DefectCategory.LOGIC_ERROR, Severity.MAJOR, "标题不同", "描述不同",
                Map.of()), DefectSource.STATIC, 0.5);

        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.fingerprint()).hasSize(64);
        // 去重后应保留先出现的置信度与描述
        assertThat(first.confidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("不同规则或不同行产生不同指纹")
    void fingerprintDiffersByRuleAndLine() {
        String base = Defect.fingerprintOf("A.java", "RuleA", 10, "LOGIC_ERROR");
        assertThat(Defect.fingerprintOf("A.java", "RuleB", 10, "LOGIC_ERROR")).isNotEqualTo(base);
        assertThat(Defect.fingerprintOf("A.java", "RuleA", 11, "LOGIC_ERROR")).isNotEqualTo(base);
        assertThat(Defect.fingerprintOf("B.java", "RuleA", 10, "LOGIC_ERROR")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("置信度被裁剪到 0~1，非法行号被纠正")
    void clampsValues() {
        Defect low = Defect.fromRaw(new RawDefect("A.java", 0, 0, "R",
                DefectCategory.CODE_STYLE, Severity.INFO, "t", "d", Map.of()), DefectSource.STATIC, -3);
        assertThat(low.confidence()).isZero();
        assertThat(low.startLine()).isEqualTo(1);
        assertThat(low.endLine()).isEqualTo(1);

        Defect high = Defect.fromRaw(raw(), DefectSource.STATIC, 9);
        assertThat(high.confidence()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("PMD 优先级映射：1 级最严重，但不直接升级为 BLOCKER")
    void mapsPmdPriority() {
        assertThat(Severity.fromPmdPriority(1)).isEqualTo(Severity.CRITICAL);
        assertThat(Severity.fromPmdPriority(2)).isEqualTo(Severity.MAJOR);
        assertThat(Severity.fromPmdPriority(3)).isEqualTo(Severity.MINOR);
        assertThat(Severity.fromPmdPriority(5)).isEqualTo(Severity.INFO);
        assertThat(Severity.BLOCKER.blocking()).isTrue();
        assertThat(Severity.MAJOR.blocking()).isFalse();
    }

    @Test
    @DisplayName("严重度取更严重者，供融合使用")
    void takesWorseSeverity() {
        assertThat(Severity.max(Severity.MINOR, Severity.CRITICAL)).isEqualTo(Severity.CRITICAL);
        assertThat(Severity.max(null, Severity.INFO)).isEqualTo(Severity.INFO);
        assertThat(Severity.max(Severity.BLOCKER, null)).isEqualTo(Severity.BLOCKER);
    }
}
