package ai_chat.service.impl;

import ai_chat.service.SecurityGovernanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSecurityGovernanceServiceTest {

    private RequestSecurityGovernanceService service;

    @BeforeEach
    void setUp() {
        service = new RequestSecurityGovernanceService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxInputChars", 40);
        ReflectionTestUtils.setField(service, "blockedPatternsCsv", "password,api key");
        ReflectionTestUtils.invokeMethod(service, "init");
    }

    @Test
    void allowsNormalRequest() {
        SecurityGovernanceService.GovernanceDecision result = service.checkInboundRequest("How to register a vessel?");
        assertTrue(result.allowed());
        assertEquals("How to register a vessel?", result.safeInput());
    }

    @Test
    void blocksEmptyRequest() {
        SecurityGovernanceService.GovernanceDecision result = service.checkInboundRequest("   ");
        assertFalse(result.allowed());
    }

    @Test
    void blocksTooLongRequest() {
        SecurityGovernanceService.GovernanceDecision result = service.checkInboundRequest("a".repeat(41));
        assertFalse(result.allowed());
    }

    @Test
    void blocksSensitivePatternCaseInsensitive() {
        SecurityGovernanceService.GovernanceDecision result = service.checkInboundRequest("My PASSWORD is 1234");
        assertFalse(result.allowed());
    }

    @Test
    void allowsOutboundWhenCustomerIdMatches() {
        SecurityGovernanceService.GovernanceDecision result =
                service.checkOutboundResponse("Generate summary for customer id 123", "Summary for customer id 123 is ready");
        assertTrue(result.allowed());
    }

    @Test
    void blocksOutboundWhenCustomerIdChanges() {
        SecurityGovernanceService.GovernanceDecision result =
                service.checkOutboundResponse("Generate summary for customer id 123", "Summary for customer id wqe is ready");
        assertFalse(result.allowed());
    }
}
