package com.shenchao.codereview.engine.fusion;

import com.shenchao.codereview.engine.model.Defect;
import com.shenchao.codereview.engine.model.DefectCategory;
import com.shenchao.codereview.engine.model.DefectSource;
import com.shenchao.codereview.engine.model.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 双路缺陷融合（蓝皮书 6.5）。
 *
 * <p>静态引擎与语义引擎各报一份结果，简单相加会得到大量重复：
 * PMD 报"这里可能空指针"，大模型也报"这个对象未判空"，人看到的是两条。
 * 融合要做的是把它们对齐成一条，并据此调整可信度：
 *
 * <ul>
 *   <li><b>交叉验证</b>（CROSS_VALIDATED）：两个独立引擎在同一位置、同一类别上都报了，
 *       这条缺陷为真的概率远高于单一引擎。置信度按概率并集 {@code 1-(1-a)(1-b)} 合成。</li>
 *   <li><b>仅静态命中</b>（STATIC_ONLY）：规则确定但大模型没看出来，
 *       可能是规则误报，也可能是模型漏检，置信度小幅下调。</li>
 *   <li><b>仅语义命中</b>（LLM_ONLY）：静态规则覆盖不到的语义问题，
 *       这正是本系统相对传统工具的价值所在，但模型有幻觉风险，也做小幅下调。</li>
 * </ul>
 *
 * <p><b>为什么按"同文件 + 同类别 + 行号接近"匹配</b>：
 * 两个引擎对同一处问题的定位天然有偏差——PMD 指向表达式所在行，大模型可能指向方法签名行。
 * 要求行号完全相同会漏掉大量真实匹配；放得太宽又会把相邻的两条不同问题合成一条。
 * 实践经验是 5 行以内且类别一致时才认为说的是同一件事。
 */
public final class DefectFuser {

    /** 行号容差：两个引擎对同一问题的定位偏差通常不超过几行。 */
    public static final int DEFAULT_LINE_TOLERANCE = 5;

    /** 仅静态命中时的置信度折扣。 */
    public static final double STATIC_ONLY_FACTOR = 0.85d;

    /** 仅语义命中时的置信度折扣。 */
    public static final double LLM_ONLY_FACTOR = 0.90d;

    public static final String FLAG_CROSS_VALIDATED = "CROSS_VALIDATED";
    public static final String FLAG_STATIC_ONLY = "STATIC_ONLY";
    public static final String FLAG_LLM_ONLY = "LLM_ONLY";

    private DefectFuser() {
    }

    public static FuseResult fuse(List<Defect> defects) {
        return fuse(defects, DEFAULT_LINE_TOLERANCE);
    }

    /**
     * @param defects       各引擎产出的统一缺陷（可含重复）
     * @param lineTolerance 行号容差，小于 0 时按 0 处理
     */
    public static FuseResult fuse(List<Defect> defects, int lineTolerance) {
        if (defects == null || defects.isEmpty()) {
            return new FuseResult(List.of(), 0, 0, 0);
        }
        int tolerance = Math.max(0, lineTolerance);

        List<Defect> staticDefects = new ArrayList<>();
        List<Defect> semanticDefects = new ArrayList<>();
        List<FusedDefect> passthrough = new ArrayList<>();
        for (Defect defect : defects) {
            if (defect == null) {
                continue;
            }
            if (defect.source() == DefectSource.STATIC) {
                staticDefects.add(defect);
            } else if (defect.source() == DefectSource.LLM) {
                semanticDefects.add(defect);
            } else {
                // 已是融合结果（重跑融合）或其他来源，原样保留
                passthrough.add(new FusedDefect(defect, FLAG_CROSS_VALIDATED));
            }
        }

        boolean[] consumed = new boolean[semanticDefects.size()];
        List<FusedDefect> items = new ArrayList<>(defects.size());
        int crossValidated = 0;
        int staticOnly = 0;
        int llmOnly = 0;

        for (Defect staticDefect : staticDefects) {
            int match = findMatch(staticDefect, semanticDefects, consumed, tolerance);
            if (match >= 0) {
                consumed[match] = true;
                items.add(new FusedDefect(merge(staticDefect, semanticDefects.get(match)),
                        FLAG_CROSS_VALIDATED));
                crossValidated++;
            } else {
                items.add(new FusedDefect(adjust(staticDefect, STATIC_ONLY_FACTOR,
                        "只有静态引擎命中，可能为规则误报或模型漏检"), FLAG_STATIC_ONLY));
                staticOnly++;
            }
        }
        for (int i = 0; i < semanticDefects.size(); i++) {
            if (consumed[i]) {
                continue;
            }
            items.add(new FusedDefect(adjust(semanticDefects.get(i), LLM_ONLY_FACTOR,
                    "只有语义引擎命中，静态规则未覆盖"), FLAG_LLM_ONLY));
            llmOnly++;
        }
        items.addAll(passthrough);
        return new FuseResult(items, crossValidated, staticOnly, llmOnly);
    }

    /** 在所有未被占用的语义缺陷里，找行距最小的一条；类别必须一致。 */
    private static int findMatch(Defect staticDefect,
                                 List<Defect> semanticDefects,
                                 boolean[] consumed,
                                 int tolerance) {
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < semanticDefects.size(); i++) {
            if (consumed[i]) {
                continue;
            }
            Defect candidate = semanticDefects.get(i);
            if (!sameFile(staticDefect, candidate) || staticDefect.category() != candidate.category()) {
                continue;
            }
            int distance = Math.abs(staticDefect.startLine() - candidate.startLine());
            if (distance > tolerance || distance >= bestDistance) {
                continue;
            }
            best = i;
            bestDistance = distance;
        }
        return best;
    }

    private static boolean sameFile(Defect left, Defect right) {
        return Objects.equals(normalize(left.filePath()), normalize(right.filePath()));
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 合并同一处缺陷的两份描述。
     *
     * <p>取舍原则：位置取静态引擎的（PMD 的行号来自 AST，比模型数出来的准）；
     * 文案取语义引擎的（模型能说清"什么条件触发、会怎样"，规则文案往往只有一句话）；
     * 规则标识保留静态的（可回溯到具体规则），模型标识进证据。
     */
    private static Defect merge(Defect staticDefect, Defect semanticDefect) {
        double confidence = 1d - (1d - staticDefect.confidence()) * (1d - semanticDefect.confidence());
        confidence = Math.min(0.99d, Math.max(0.5d, confidence));

        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.putAll(staticDefect.evidence());
        evidence.put("fusion", FLAG_CROSS_VALIDATED);
        evidence.put("staticRule", staticDefect.ruleId());
        evidence.put("llmRule", semanticDefect.ruleId());
        evidence.put("staticConfidence", String.format(Locale.ROOT, "%.2f", staticDefect.confidence()));
        evidence.put("llmConfidence", String.format(Locale.ROOT, "%.2f", semanticDefect.confidence()));
        evidence.put("llmTitle", semanticDefect.title());
        // 两侧原始表述分开存：报告页要并排展示"规则怎么说 / 模型怎么说"，
        // 合并后的 description 是拼接文本，拆不开也无法判断哪句出自谁。
        evidence.put("staticTitle", nullToEmpty(staticDefect.title()));
        evidence.put("llmDescription", nullToEmpty(semanticDefect.description()));
        evidence.put("llmSuggestion", nullToEmpty(semanticDefect.suggestion()));
        evidence.put("staticDescription", nullToEmpty(staticDefect.description()));

        return new Defect(
                staticDefect.filePath(),
                staticDefect.startLine(),
                Math.max(staticDefect.endLine(), semanticDefect.endLine()),
                staticDefect.category(),
                Severity.max(staticDefect.severity(), semanticDefect.severity()),
                isBlank(semanticDefect.title()) ? staticDefect.title() : semanticDefect.title(),
                joinDescription(staticDefect, semanticDefect),
                isBlank(semanticDefect.suggestion()) ? staticDefect.suggestion() : semanticDefect.suggestion(),
                isBlank(semanticDefect.fixSnippet()) ? staticDefect.fixSnippet() : semanticDefect.fixSnippet(),
                DefectSource.FUSED,
                staticDefect.ruleId(),
                confidence,
                // 指纹沿用静态一侧：融合与否不改变"这是哪一处问题"，
                // 沿用后同一提交重跑时指纹稳定，历史对比才不会误判成新缺陷
                staticDefect.fingerprint(),
                evidence);
    }

    private static String joinDescription(Defect staticDefect, Defect semanticDefect) {
        String left = staticDefect.description();
        String right = semanticDefect.description();
        if (isBlank(right)) {
            return left;
        }
        if (isBlank(left) || left.equals(right)) {
            return right;
        }
        return left + "\n" + right;
    }

    /** 单一来源命中的缺陷：按折扣下调置信度，并在证据里写明原因。 */
    private static Defect adjust(Defect defect, double factor, String reason) {
        Map<String, String> evidence = new LinkedHashMap<>(defect.evidence());
        evidence.put("fusion", defect.source() == DefectSource.STATIC ? FLAG_STATIC_ONLY : FLAG_LLM_ONLY);
        evidence.put("fusionNote", reason);

        return new Defect(
                defect.filePath(),
                defect.startLine(),
                defect.endLine(),
                defect.category(),
                defect.severity(),
                defect.title(),
                defect.description(),
                defect.suggestion(),
                defect.fixSnippet(),
                defect.source(),
                defect.ruleId(),
                Math.max(0.1d, Math.min(1d, defect.confidence() * factor)),
                defect.fingerprint(),
                evidence);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 融合后的单条缺陷及其标记：flag 会落到 {@code review_issue.flag}。 */
    public record FusedDefect(Defect defect, String flag) {
    }

    /**
     * 融合结果。
     *
     * @param items          去重后的缺陷（含标记）
     * @param crossValidated 双引擎一致命中的条数——这是报告里最有说服力的指标
     * @param staticOnly     仅静态命中
     * @param llmOnly        仅语义命中
     */
    public record FuseResult(List<FusedDefect> items, int crossValidated, int staticOnly, int llmOnly) {

        public FuseResult {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
