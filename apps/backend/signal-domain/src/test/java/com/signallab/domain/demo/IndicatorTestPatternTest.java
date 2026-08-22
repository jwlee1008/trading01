package com.signallab.domain.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class IndicatorTestPatternTest {
    @Test void createsEveryExactCombination() {
        for (String pattern : IndicatorTestPattern.supported()) {
            var result = IndicatorTestPattern.apply(Collections.nCopies(261, 50_000d), pattern);
            assertEquals(pattern.contains("RSI"), result.rsi(), pattern);
            assertEquals(pattern.contains("EMA"), result.ema(), pattern);
            assertEquals(pattern.contains("BOLLINGER"), result.bollinger(), pattern);
        }
    }
}
