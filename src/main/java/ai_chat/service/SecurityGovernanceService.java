package ai_chat.service;

public interface SecurityGovernanceService {

    GovernanceDecision checkInboundRequest(String rawInput);

    GovernanceDecision checkOutboundResponse(String originalInput, String modelOutput);

    final class GovernanceDecision {
        private final boolean allowed;
        private final String safeInput;
        private final String userMessage;

        private GovernanceDecision(boolean allowed, String safeInput, String userMessage) {
            this.allowed = allowed;
            this.safeInput = safeInput;
            this.userMessage = userMessage;
        }

        public boolean allowed() {
            return allowed;
        }

        public String safeInput() {
            return safeInput;
        }

        public String userMessage() {
            return userMessage;
        }

        public static GovernanceDecision allow(String safeInput) {
            return new GovernanceDecision(true, safeInput, null);
        }

        public static GovernanceDecision block(String userMessage) {
            return new GovernanceDecision(false, null, userMessage);
        }
    }
}
