package com.shenchao.codereview.engine.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek（OpenAI 兼容协议）调用客户端。
 *
 * <p>只做三件事：拼请求、发请求、把 {@code choices[0].message.content} 解成 JSON。
 * 业务语义（哪些文件该送、结果怎么归一）全在 {@link SemanticCodeAnalyzer}，
 * 这样换模型只改这个类，换策略只改那个类。
 *
 * <p><b>重试策略</b>：只重试"重试有意义"的错误——429 限流、5xx、网络超时。
 * 401/403 是密钥问题、400 是请求体问题，重试一万次也是同样的结果，
 * 反而会把一次秒级失败拖成分钟级卡顿。
 */
@Slf4j
@Component
public class DeepSeekClient {

    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DeepSeekClient(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.timeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 发起一次对话补全，返回模型输出的 JSON 根节点。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户内容
     * @return 解析后的 JSON；模型输出不是合法 JSON 时抛 {@link LlmException}
     */
    public JsonNode completeJson(String systemPrompt, String userPrompt) {
        String content = complete(systemPrompt, userPrompt);
        return parseJson(content);
    }

    /**
     * 发起一次对话补全，返回纯文本内容。
     *
     * @throws LlmException 密钥缺失、请求失败或重试耗尽
     */
    public String complete(String systemPrompt, String userPrompt) {
        if (!properties.usable()) {
            throw new LlmException("未配置 DEEPSEEK_API_KEY，语义审查不可用");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("temperature", properties.temperature());
        body.put("max_tokens", properties.maxTokens());
        body.put("stream", false);
        // 强制 JSON 输出：模型不按格式回时，解析层就不用做各种容错兜底了
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));

        int attempts = properties.retries() + 1;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String raw = restClient.post()
                        .uri("/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return extractContent(raw);
            } catch (HttpClientErrorException.TooManyRequests ex) {
                last = ex;
                log.warn("大模型限流（429），第 {}/{} 次重试", attempt, attempts);
            } catch (HttpClientErrorException ex) {
                // 4xx 除 429 外都是确定性错误，重试无意义
                throw new LlmException("大模型调用失败: HTTP " + ex.getStatusCode().value()
                        + " " + brief(ex.getResponseBodyAsString()), ex);
            } catch (RestClientException ex) {
                last = ex;
                log.warn("大模型调用异常，第 {}/{} 次重试: {}", attempt, attempts, ex.getMessage());
            }
            if (attempt < attempts) {
                sleepQuietly(Duration.ofMillis(500L * attempt * attempt));
            }
        }
        throw new LlmException("大模型调用重试 " + attempts + " 次仍失败: "
                + (last == null ? "未知原因" : last.getMessage()), last);
    }

    /** 从响应报文里取 content；报文结构异常时给出可读原因而不是 NPE。 */
    private String extractContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LlmException("大模型返回空响应");
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmException("大模型响应缺少 choices: " + brief(raw));
            }
            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new LlmException("大模型响应缺少 content: " + brief(raw));
            }
            return content;
        } catch (LlmException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LlmException("解析大模型响应失败: " + brief(raw), ex);
        }
    }

    /**
     * 解析模型输出的 JSON。
     *
     * <p>即使开了 {@code response_format}，模型偶尔仍会套 ```json 围栏或前后加解释文字，
     * 这里做最小限度的容错：先直接解析，失败再截取首尾花括号之间的内容。
     */
    JsonNode parseJson(String content) {
        String text = content == null ? "" : content.trim();
        try {
            return objectMapper.readTree(text);
        } catch (Exception ignored) {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return objectMapper.readTree(text.substring(start, end + 1));
                } catch (Exception ex) {
                    throw new LlmException("模型输出不是合法 JSON: " + brief(text), ex);
                }
            }
            throw new LlmException("模型输出不是合法 JSON: " + brief(text));
        }
    }

    private static String brief(String value) {
        if (value == null) {
            return "";
        }
        String single = value.replaceAll("\\s+", " ").trim();
        return single.length() > 200 ? single.substring(0, 200) + "…" : single;
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 语义引擎专用异常：区分"配置缺失"与"调用失败"，便于上层降级。 */
    public static class LlmException extends RuntimeException {
        public LlmException(String message) {
            super(message);
        }

        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
