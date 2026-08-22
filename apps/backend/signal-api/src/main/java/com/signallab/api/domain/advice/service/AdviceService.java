package com.signallab.api.domain.advice.service;

import com.signallab.api.domain.advice.dto.AdviceResponse;
import com.signallab.api.domain.signal.dto.SignalResponse;
import com.signallab.api.domain.signal.service.SignalService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class AdviceService {

    private final SignalService signalService;
    private final GeminiAdviceGenerator generator;
    private final ConcurrentHashMap<String, AdviceResponse> cache = new ConcurrentHashMap<>();

    public AdviceService(SignalService signalService, GeminiAdviceGenerator generator) {
        this.signalService = signalService;
        this.generator = generator;
    }

    public AdviceResponse explain(UUID userId, UUID signalId) {
        SignalResponse signal = signalService.findById(userId, signalId);
        String cacheKey = signal.id() + ":" + signal.candleClose() + ":" + signal.reasons().hashCode();
        return cache.computeIfAbsent(cacheKey, ignored -> create(signal));
    }

    private AdviceResponse create(SignalResponse signal) {
        GeminiAdviceGenerator.GeneratedAdvice generated;
        if (generator.configured()) {
            generated = generator.generate(signal);
        } else {
            generated = localAdvice(signal);
        }
        return new AdviceResponse(
            signal.id(), generated.summary(), generated.evidence(), generated.risks(), generated.questionsToConsider(),
            GeminiAdviceGenerator.disclaimer(), generated.source(), generated.model(), signal.candleClose(), OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private GeminiAdviceGenerator.GeneratedAdvice localAdvice(SignalResponse signal) {
        List<String> evidence = signal.reasons().stream()
            .map(reason -> reason.label() + (reason.value().isBlank() ? "" : " " + reason.value()))
            .toList();
        List<String> risks = signal.stale()
            ? List.of("신호 데이터가 지연된 상태입니다. 기준 일봉과 생성 시각을 다시 확인하세요.", "조건 충족은 이후 가격 방향을 보장하지 않습니다.")
            : List.of("조건 충족은 이후 가격 방향을 보장하지 않습니다.", "거래 비용과 시장 변동성은 이 신호에 반영되지 않을 수 있습니다.");
        return new GeminiAdviceGenerator.GeneratedAdvice(
            signal.name() + "의 사용자 설정 조건이 완성 일봉 기준으로 충족되었습니다.",
            evidence.isEmpty() ? List.of("저장된 세부 근거가 없습니다.") : evidence,
            risks,
            List.of("감당 가능한 최대 손실 범위를 정했나요?", "현재 보유 종목과의 집중 위험을 확인했나요?"),
            "LOCAL", "rules-v1"
        );
    }
}
