package com.shenchao.codereview.engine.fusion;

import com.shenchao.codereview.engine.model.Defect;
import com.shenchao.codereview.engine.model.DefectCategory;
import com.shenchao.codereview.engine.model.DefectSource;
import com.shenchao.codereview.engine.model.Severity;
import com.shenchao.codereview.engine.spi.RawDefect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("双路缺陷融合")
class DefectFuserTest {

    private static Defect defect(DefectSource source,
                                 String file,
                                 int line,
                                 DefectCategory category,
                                 Severity severity,
                                 String ruleId,
                                 double confidence) {
        return Defect.fromRaw(new RawDefect(file, line, line, ruleId, category, severity,
                "标题-" + source, "描述-" + source, Map.of("k", "v"), confidence), source, confidence);
    }

    @Test
    @DisplayName("同文件同类别且行距在容差内判为交叉验证，置信度按概率并集提升")
    void crossValidatedWhenBothEnginesAgree() {
        Defect statics = defect(DefectSource.STATIC, "src/A.java", 40,
                DefectCategory.NULL_POINTER, Severity.MAJOR, "NullAssignment", 0.8);
        Defect semantic = defect(DefectSource.LLM, "src/A.java", 43,
                DefectCategory.NULL_POINTER, Severity.CRITICAL, "llm/semantic-v1", 0.9);

        DefectFuser.FuseResult result = DefectFuser.fuse(List.of(statics, semantic));

        assertThat(result.items()).hasSize(1);
        assertThat(result.crossValidated()).isEqualTo(1);
        DefectFuser.FusedDefect item = result.items().get(0);
        assertThat(item.flag()).isEqualTo(DefectFuser.FLAG_CROSS_VALIDATED);
        assertThat(item.defect().source()).isEqualTo(DefectSource.FUSED);
        // 1-(1-0.8)(1-0.9)=0.98
        assertThat(item.defect().confidence()).isBetween(0.97, 0.99);
        // 严重度取更高的一侧
        assertThat(item.defect().severity()).isEqualTo(Severity.CRITICAL);
        // 位置取静态（AST 行号更准），规则保留可回溯
        assertThat(item.defect().startLine()).isEqualTo(40);
        assertThat(item.defect().ruleId()).isEqualTo("NullAssignment");
        assertThat(item.defect().evidence()).containsEntry("staticRule", "NullAssignment")
                .containsEntry("llmRule", "llm/semantic-v1")
                .containsEntry("fusion", DefectFuser.FLAG_CROSS_VALIDATED);
    }

    @Test
    @DisplayName("类别不同不融合，两条各自保留")
    void notMatchedWhenCategoryDiffers() {
        Defect statics = defect(DefectSource.STATIC, "A.java", 10,
                DefectCategory.PERFORMANCE, Severity.MINOR, "R1", 0.7);
        Defect semantic = defect(DefectSource.LLM, "A.java", 10,
                DefectCategory.NULL_POINTER, Severity.MAJOR, "R2", 0.8);

        DefectFuser.FuseResult result = DefectFuser.fuse(List.of(statics, semantic));

        assertThat(result.items()).hasSize(2);
        assertThat(result.crossValidated()).isZero();
        assertThat(result.staticOnly()).isEqualTo(1);
        assertThat(result.llmOnly()).isEqualTo(1);
    }

    @Test
    @DisplayName("行距超出容差不融合")
    void notMatchedWhenTooFarApart() {
        Defect statics = defect(DefectSource.STATIC, "A.java", 10,
                DefectCategory.LOGIC_ERROR, Severity.MAJOR, "R1", 0.7);
        Defect semantic = defect(DefectSource.LLM, "A.java", 60,
                DefectCategory.LOGIC_ERROR, Severity.MAJOR, "R2", 0.8);

        DefectFuser.FuseResult result = DefectFuser.fuse(List.of(statics, semantic), 5);

        assertThat(result.crossValidated()).isZero();
        assertThat(result.items()).hasSize(2);
    }

    @Test
    @DisplayName("路径分隔符与大小写差异不影响匹配")
    void pathNormalizationWorks() {
        Defect statics = defect(DefectSource.STATIC, "src\\main\\A.java", 10,
                DefectCategory.LOGIC_ERROR, Severity.MAJOR, "R1", 0.7);
        Defect semantic = defect(DefectSource.LLM, "src/main/a.java", 11,
                DefectCategory.LOGIC_ERROR, Severity.MAJOR, "R2", 0.8);

        assertThat(DefectFuser.fuse(List.of(statics, semantic)).crossValidated()).isEqualTo(1);
    }

    @Test
    @DisplayName("仅单侧命中时按来源降权，并写明原因")
    void singleSideIsDownweighted() {
        Defect statics = defect(DefectSource.STATIC, "A.java", 10,
                DefectCategory.CODE_STYLE, Severity.INFO, "R1", 0.8);
        Defect semantic = defect(DefectSource.LLM, "B.java", 10,
                DefectCategory.LOGIC_ERROR, Severity.MAJOR, "R2", 0.8);

        DefectFuser.FuseResult result = DefectFuser.fuse(List.of(statics, semantic));

        Defect staticOnly = result.items().stream()
                .map(DefectFuser.FusedDefect::defect)
                .filter(d -> d.filePath().equals("A.java")).findFirst().orElseThrow();
        Defect llmOnly = result.items().stream()
                .map(DefectFuser.FusedDefect::defect)
                .filter(d -> d.filePath().equals("B.java")).findFirst().orElseThrow();

        assertThat(staticOnly.confidence()).isCloseTo(0.68, org.assertj.core.data.Offset.offset(0.001));
        assertThat(llmOnly.confidence()).isCloseTo(0.72, org.assertj.core.data.Offset.offset(0.001));
        assertThat(staticOnly.evidence()).containsEntry("fusion", DefectFuser.FLAG_STATIC_ONLY);
        assertThat(llmOnly.evidence()).containsEntry("fusion", DefectFuser.FLAG_LLM_ONLY);
    }

    @Test
    @DisplayName("一个静态缺陷只消费一个语义缺陷，多余的保留为 LLM_ONLY")
    void oneToOneMatching() {
        Defect statics = defect(DefectSource.STATIC, "A.java", 10,
                DefectCategory.LOGIC_ERROR, Severity.MAJOR, "R1", 0.7);
        Defect llm1 = defect(DefectSource.LLM, "A.java", 11,
                DefectCategory.LOGIC_ERROR, Severity.MAJOR, "R2", 0.8);
        Defect llm2 = defect(DefectSource.LLM, "A.java", 12,
                DefectCategory.LOGIC_ERROR, Severity.MAJOR, "R2", 0.8);
        // 指纹相同才会被管道去重，这里用不同行号保证是两条
        assertThat(llm1.fingerprint()).isNotEqualTo(llm2.fingerprint());

        DefectFuser.FuseResult result = DefectFuser.fuse(List.of(statics, llm1, llm2));

        assertThat(result.crossValidated()).isEqualTo(1);
        assertThat(result.llmOnly()).isEqualTo(1);
        assertThat(result.items()).hasSize(2);
    }

    @Test
    @DisplayName("同一条缺陷重复出现时指纹不变，便于跨任务对比")
    void fingerprintStableAfterFusion() {
        Defect statics = defect(DefectSource.STATIC, "A.java", 10,
                DefectCategory.LOGIC_ERROR, Severity.MAJOR, "R1", 0.7);
        Defect semantic = defect(DefectSource.LLM, "A.java", 10,
                DefectCategory.LOGIC_ERROR, Severity.MAJOR, "R2", 0.8);

        assertThat(DefectFuser.fuse(List.of(statics, semantic)).items().get(0).defect().fingerprint())
                .isEqualTo(statics.fingerprint());
    }

    @Test
    @DisplayName("空输入返回空结果，不抛异常")
    void emptyInput() {
        assertThat(DefectFuser.fuse(List.of()).items()).isEmpty();
        assertThat(DefectFuser.fuse(null).items()).isEmpty();
    }
}
