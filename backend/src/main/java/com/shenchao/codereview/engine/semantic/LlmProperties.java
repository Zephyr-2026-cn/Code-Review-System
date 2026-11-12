package com.shenchao.codereview.engine.semantic;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 语义审查引擎配置。
 *
 * <p>所有取值都按"成本可控"设计：大模型按 token 计费，
 * 一次全量审查如果无脑把 2000 个文件全丢进去，单次任务就能烧掉几十块。
 * 因此这里有三层预算——文件数、单文件字符数、单请求字符数。
 *
 * @param enabled             总开关；关闭后即使任务开了 llm 也不会有语义结果
 * @param baseUrl             OpenAI 兼容端点，默认 DeepSeek
 * @param apiKey              密钥，从环境变量 {@code DEEPSEEK_API_KEY} 注入；为空时引擎降级为错误上报
 * @param model               模型名，deepseek-chat 为通用对话模型，性价比高于 reasoner
 * @param temperature         温度；审查任务要稳定复现，取低值
 * @param maxTokens           单次响应上限，缺陷列表 JSON 一般在 1500 以内
 * @param timeout             单次请求超时
 * @param retries             失败重试次数（仅 429/5xx/网络异常重试，4xx 参数错误不重试）
 * @param concurrency         并发请求数；受服务端限流约束，不要超过 8
 * @param maxFilesPerTask     单任务最多送审文件数，超出部分记 warning
 * @param maxFilesPerRequest  单请求合并的文件数，太少浪费调用次数，太多稀释注意力
 * @param maxCharsPerRequest  单请求字符上限，超出则少放几个文件
 * @param maxFileChars        单文件送入模型的最大字符数，超出截断（取头尾）
 * @param maxFileBytes        单文件体积上限，超过直接跳过（压缩产物、生成代码）
 * @param minConfidence       低于该置信度的结果直接丢弃，减少噪声
 * @param maxIssuesPerFile    单文件最多保留的缺陷数
 */
@ConfigurationProperties(prefix = "app.engine.llm")
public record LlmProperties(
        Boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        Integer maxTokens,
        Duration timeout,
        Integer retries,
        Integer concurrency,
        Integer maxFilesPerTask,
        Integer maxFilesPerRequest,
        Integer maxCharsPerRequest,
        Integer maxFileChars,
        Integer maxFileBytes,
        Double minConfidence,
        Integer maxIssuesPerFile
) {

    public LlmProperties {
        enabled = enabled == null || enabled;
        baseUrl = blank(baseUrl) ? "https://api.deepseek.com" : trimTrailingSlash(baseUrl.trim());
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = blank(model) ? "deepseek-chat" : model.trim();
        temperature = temperature == null || temperature < 0 || temperature > 2 ? 0.2d : temperature;
        maxTokens = maxTokens == null || maxTokens < 256 ? 2048 : maxTokens;
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(90) : timeout;
        retries = retries == null || retries < 0 || retries > 5 ? 2 : retries;
        concurrency = concurrency == null || concurrency < 1 ? 4 : Math.min(concurrency, 8);
        maxFilesPerTask = maxFilesPerTask == null || maxFilesPerTask < 1 ? 40 : maxFilesPerTask;
        maxFilesPerRequest = maxFilesPerRequest == null || maxFilesPerRequest < 1 ? 4 : maxFilesPerRequest;
        maxCharsPerRequest = maxCharsPerRequest == null || maxCharsPerRequest < 2000 ? 14000 : maxCharsPerRequest;
        maxFileChars = maxFileChars == null || maxFileChars < 500 ? 6000 : maxFileChars;
        maxFileBytes = maxFileBytes == null || maxFileBytes < 4096 ? 256 * 1024 : maxFileBytes;
        minConfidence = minConfidence == null || minConfidence < 0 || minConfidence > 1 ? 0.5d : minConfidence;
        maxIssuesPerFile = maxIssuesPerFile == null || maxIssuesPerFile < 1 ? 8 : maxIssuesPerFile;
    }

    /** 是否具备调用条件：开关打开且密钥非空。 */
    public boolean usable() {
        return enabled && !apiKey.isBlank();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
