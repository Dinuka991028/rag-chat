package ai_chat.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmChatServiceRetrievalQueryTest {

    @Test
    void longLatestMessageUnchanged() {
        String longQ = "a".repeat(100);
        assertEquals(longQ, LlmChatService.buildRetrievalQuery(List.of(), longQ));
    }

    @Test
    void shortFollowUpCombinesWithPreviousUserLine() {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("What is the registration fee?"));
        assertEquals(
                "What is the registration fee? yes",
                LlmChatService.buildRetrievalQuery(history, "yes"));
    }

    @Test
    void greetingDoesNotCombineWithPreviousUserLine() {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("Show pending transfer requests"));
        assertEquals("hi", LlmChatService.buildRetrievalQuery(history, "hi"));
    }

    @Test
    void greetingDetectorAcceptsSimpleGreeting() {
        assertTrue(LlmChatService.isGreetingOnly("Hello!"));
    }
}
