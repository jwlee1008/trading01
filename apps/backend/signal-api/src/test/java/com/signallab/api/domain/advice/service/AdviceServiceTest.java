package com.signallab.api.domain.advice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.signallab.api.domain.advice.dto.AdviceResponse;
import com.signallab.api.domain.signal.dto.SignalResponse;
import com.signallab.api.domain.signal.service.SignalService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdviceServiceTest {

    @Test
    void createsAndCachesSafeLocalAdviceWhenGeminiIsNotConfigured() {
        SignalService signalService = mock(SignalService.class);
        GeminiAdviceGenerator generator = mock(GeminiAdviceGenerator.class);
        UUID userId = UUID.randomUUID();
        UUID signalId = UUID.randomUUID();
        SignalResponse signal = new SignalResponse(
            signalId, userId, UUID.randomUUID(), "005930", "삼성전자", "BUY_CONDITION",
            OffsetDateTime.parse("2026-08-21T06:30:00Z"), "71000", "ACTIVE",
            List.of(new SignalResponse.Reason("RSI", "31.2")), true, true
        );
        when(signalService.findById(userId, signalId)).thenReturn(signal);
        when(generator.configured()).thenReturn(false);
        AdviceService service = new AdviceService(signalService, generator);

        AdviceResponse first = service.explain(userId, signalId);
        AdviceResponse second = service.explain(userId, signalId);

        assertThat(first.source()).isEqualTo("LOCAL");
        assertThat(first.evidence()).containsExactly("RSI 31.2");
        assertThat(first.risks()).anyMatch(item -> item.contains("지연"));
        assertThat(second).isSameAs(first);
        verify(signalService, org.mockito.Mockito.times(2)).findById(userId, signalId);
    }
}
