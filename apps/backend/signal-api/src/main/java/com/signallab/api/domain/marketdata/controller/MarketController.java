package com.signallab.api.domain.marketdata.controller;

import com.signallab.api.domain.marketdata.service.MarketQueryService;
import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.global.web.ApiEnvelope;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/market")
public class MarketController {
    private final MarketQueryService marketQueryService;
    private final DatabaseHealthService databaseHealthService;

    public MarketController(MarketQueryService marketQueryService, DatabaseHealthService databaseHealthService) {
        this.marketQueryService = marketQueryService;
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping("/kospi/top10")
    public Map<String, Object> top10(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
    ) {
        return ApiEnvelope.ok(marketQueryService.kospiTop10(asOf), databaseHealthService.isPostgres());
    }

    @GetMapping("/instruments/{symbol}/daily-prices")
    public Map<String, Object> dailyPrices(
        @PathVariable String symbol,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        if (!symbol.matches("\\d{6}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 종목 코드를 입력하세요.");
        if (from.isAfter(to) || from.plusDays(370).isBefore(to))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 기간은 최대 370일이며 시작일이 종료일보다 늦을 수 없습니다.");
        MarketQueryService.DailyPriceResponse response = marketQueryService.dailyPrices(symbol, from, to);
        if (response == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다.");
        return ApiEnvelope.ok(response, databaseHealthService.isPostgres());
    }
}
