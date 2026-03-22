package ai_chat.service;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * In-memory short-term dialogue per {@code conversationId}. Not shared across JVM instances; use Redis/Mongo if you scale out.
 */
@Service
public class ConversationHistoryService {

    private static final int MAX_CONVERSATION_ID_LENGTH = 128;

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    private final int maxMessagesPerSession;
    private final long sessionTtlMillis;

    public ConversationHistoryService(
            @Value("${conf.chat.history-max-messages:20}") int maxMessagesPerSession,
            @Value("${conf.chat.history-session-ttl-hours:24}") long sessionTtlHours) {
        this.maxMessagesPerSession = Math.max(2, maxMessagesPerSession);
        this.sessionTtlMillis = TimeUnit.HOURS.toMillis(Math.max(1, sessionTtlHours));
    }

    /**
     * Returns a new UUID string when {@code conversationId} is null or blank; otherwise returns trimmed id (max length enforced).
     */
    public String resolveOrCreateConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = conversationId.trim();
        if (trimmed.length() > MAX_CONVERSATION_ID_LENGTH) {
            throw new IllegalArgumentException("conversationId too long (max " + MAX_CONVERSATION_ID_LENGTH + " characters)");
        }
        return trimmed;
    }

    /** Immutable copy of prior turns for the given session (never includes the current user message). */
    public List<Message> snapshot(String conversationId) {
        evictExpired();
        Session s = sessions.get(conversationId);
        if (s == null) {
            return List.of();
        }
        s.touch();
        synchronized (s.messages) {
            return new ArrayList<>(s.messages);
        }
    }

    /** Records this user turn and assistant reply after a successful call. */
    public void append(String conversationId, String userText, String assistantText) {
        Session s = sessions.computeIfAbsent(conversationId, k -> new Session());
        s.touch();
        synchronized (s.messages) {
            s.messages.add(new UserMessage(userText));
            s.messages.add(new AssistantMessage(assistantText));
            while (s.messages.size() > maxMessagesPerSession) {
                s.messages.remove(0);
            }
        }
        evictExpired();
    }

    private void evictExpired() {
        long cutoff = System.currentTimeMillis() - sessionTtlMillis;
        sessions.entrySet().removeIf(e -> e.getValue().lastAccessMillis < cutoff);
    }

    private static final class Session {
        final List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        volatile long lastAccessMillis = System.currentTimeMillis();

        void touch() {
            lastAccessMillis = System.currentTimeMillis();
        }
    }
}
