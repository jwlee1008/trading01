package com.signallab.api.domain.advice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.signallab.api.domain.signal.dto.SignalResponse;
import com.signallab.api.global.config.SignalProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GeminiAdviceGenerator {

    private static final String DISCLAIMER = "정보·교육 목적의 기계적 설명이며 투자자문, 매매 권유 또는 수익 보장이 아닙니다.";
    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private final SignalProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public GeminiAdviceGenerator(SignalProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    GeminiAdviceGenerator(SignalProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public boolean configured() {
        return properties.getGeminiApiKey() != null && !properties.getGeminiApiKey().isBlank();
    }

    public GeneratedAdvice generate(SignalResponse signal) {
        try {
            String model = properties.getGeminiModel();
            if (model == null || !MODEL_NAME.matcher(model).matches()) {
                throw new IllegalStateException("Gemini 모델 이름이 올바르지 않습니다.");
            }
            ObjectNode payload = objectMapper.createObjectNode();
            String prompt = "당신은 한국 주식 조건 신호를 설명하는 교육용 도우미다. 제공된 사실만 사용한다. "
                + "매수·매도 지시, 목표가, 확률, 미래 가격, 수익 보장을 만들지 않는다. "
                + "데이터 지연 또는 근거 부족은 위험으로 명시한다. 모든 문장은 한국어로 간결하게 작성한다.\n\n신호 데이터:\n"
                + objectMapper.writeValueAsString(sanitizedInput(signal));
            payload.putArray("contents").addObject().putArray("parts").addObject().put("text", prompt);
            ObjectNode generationConfig = payload.putObject("generationConfig");
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.set("responseJsonSchema", adviceSchema());

            String baseUrl = properties.getGeminiBaseUrl().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1beta/models/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(30))
                .header("x-goog-api-key", properties.getGeminiApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Gemini 응답 상태: " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String outputText = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
            if (outputText.isBlank()) throw new IllegalStateException("Gemini 응답에 출력 텍스트가 없습니다.");
            JsonNode advice = objectMapper.readTree(outputText);
            return new GeneratedAdvice(
                requiredText(advice, "summary"), textList(advice, "evidence"), textList(advice, "risks"),
                textList(advice, "questionsToConsider"), "GEMINI", model
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 설명 생성이 중단되었습니다.", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("AI 설명을 생성할 수 없습니다.", exception);
        }
    }

    private ObjectNode adviceSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.putArray("required").add("summary").add("evidence").add("risks").add("questionsToConsider");
        schema.put("additionalProperties", false);
        ObjectNode fields = schema.putObject("properties");
        fields.putObject("summary").put("type", "string");
        for (String field : List.of("evidence", "risks", "questionsToConsider")) {
            fields.putObject(field).put("type", "array").put("maxItems", 5).putObject("items").put("type", "string");
        }
        return schema;
    }

    private ObjectNode sanitizedInput(SignalResponse signal) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("signalId", signal.id().toString());
        input.put("symbol", signal.symbol());
        input.put("name", signal.name());
        input.put("signalType", signal.type());
        input.put("candleClose", signal.candleClose().toString());
        input.put("closePrice", signal.closePrice());
        input.put("dataIsStale", signal.stale());
        ArrayNode reasons = input.putArray("reasons");
        signal.reasons().forEach(reason -> reasons.addObject().put("label", reason.label()).put("value", reason.value()));
        return input;
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalStateException("AI 응답 필드가 비어 있습니다: " + field);
        return value;
    }

    private List<String> textList(JsonNode node, String field) {
        if (!(node.path(field) instanceof ArrayNode array)) throw new IllegalStateException("AI 응답 배열이 없습니다: " + field);
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
            .map(JsonNode::asText).filter(value -> !value.isBlank()).limit(5).toList();
    }

    public record GeneratedAdvice(String summary, List<String> evidence, List<String> risks,
                                  List<String> questionsToConsider, String source, String model) {}

    public static String disclaimer() { return DISCLAIMER; }
}
