package com.shenchao.codereview.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shenchao.codereview.common.api.ErrorCode;
import com.shenchao.codereview.common.api.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一响应体的序列化契约测试。
 *
 * <p>为什么值得单独测：前后端的全部交互都依赖这几个字段，
 * 一旦被 Jackson 自动补出额外属性（例如 {@code isSuccess()} 被当成 Bean 属性），
 * 前端类型定义就会与实际响应不一致，属于隐蔽但影响面极大的问题。
 */
class ResultSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("失败响应只应包含约定字段，不得出现 success 等派生属性")
    void shouldSerializeOnlyContractFields() throws Exception {
        String json = objectMapper.writeValueAsString(Result.fail(ErrorCode.UNAUTHORIZED));
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.has("code")).isTrue();
        assertThat(node.has("message")).isTrue();
        assertThat(node.has("data")).isTrue();
        assertThat(node.has("traceId")).isTrue();
        assertThat(node.has("timestamp")).isTrue();

        List<String> fieldNames = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactlyInAnyOrder("code", "message", "data", "traceId", "timestamp");
        assertThat(node.get("code").asInt()).isEqualTo(ErrorCode.UNAUTHORIZED.code());
        assertThat(node.get("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("成功响应应携带业务数据且 code 为 0")
    void shouldSerializeSuccessWithData() throws Exception {
        String json = objectMapper.writeValueAsString(Result.ok("pong"));
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("code").asInt()).isZero();
        assertThat(node.get("data").asText()).isEqualTo("pong");
    }
}
