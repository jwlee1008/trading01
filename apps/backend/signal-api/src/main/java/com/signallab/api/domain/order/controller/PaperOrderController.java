package com.signallab.api.domain.order.controller;

import com.signallab.api.global.health.service.DatabaseHealthService;
import com.signallab.api.domain.order.service.PaperOrderService;
import com.signallab.api.global.web.ApiEnvelope;
import com.signallab.api.global.web.CurrentUser;
import com.signallab.api.domain.order.dto.PaperOrderRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/paper-orders")
public class PaperOrderController {

    private final PaperOrderService paperOrderService;
    private final DatabaseHealthService databaseHealthService;

    public PaperOrderController(PaperOrderService paperOrderService, DatabaseHealthService databaseHealthService) {
        this.paperOrderService = paperOrderService;
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping
    public Map<String, Object> list(@CurrentUser String userId) {
        return ApiEnvelope.ok(paperOrderService.findByUserId(parseUserId(userId)), databaseHealthService.isPostgres());
    }

    @PostMapping
    public Map<String, Object> place(@CurrentUser String userId, @Valid @RequestBody PaperOrderRequest request) {
        return ApiEnvelope.ok(paperOrderService.place(parseUserId(userId), request), databaseHealthService.isPostgres());
    }

    @PostMapping("/{orderId}/cancel")
    public Map<String, Object> cancel(@CurrentUser String userId, @PathVariable UUID orderId) {
        return ApiEnvelope.ok(paperOrderService.cancel(parseUserId(userId), orderId), databaseHealthService.isPostgres());
    }

    private UUID parseUserId(String userId) {
        try { return UUID.fromString(userId); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("인증된 사용자 식별자가 UUID 형식이 아닙니다.", exception); }
    }
}
