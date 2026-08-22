package com.signallab.api.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.signallab.api.domain.order.service.SellRuleService;
import com.signallab.api.domain.order.dto.SellRuleRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class SellRuleServiceTest {

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final SellRuleService service = new SellRuleService(jdbcTemplate, new ObjectMapper());

    @Test
    void rejectsRequestsThatChooseNeitherManualNorAutomaticRules() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
            service.save(UUID.randomUUID(), UUID.randomUUID(), new SellRuleRequest(null, null, null, null, null, List.of(), false))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsMoreThanThreeTechnicalRulesBeforeAccessingTheDatabase() {
        var node = new ObjectMapper().createObjectNode().put("kind", "CLOSE");
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
            service.save(UUID.randomUUID(), UUID.randomUUID(), new SellRuleRequest(null, null, null, null, "ANY", List.of(node, node, node, node), false))
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(jdbcTemplate);
    }
}
