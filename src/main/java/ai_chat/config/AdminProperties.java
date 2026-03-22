package ai_chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Admin endpoints: review unknown customer questions and add knowledge without redeploying.
 * Set {@code api-key} in production and send header {@code X-Admin-Key}.
 */
@Component
@ConfigurationProperties(prefix = "conf.admin")
public class AdminProperties {

    /** When false, {@code /admin/**} returns 404. */
    private boolean enabled = true;

    /** If non-blank, requests to {@code /admin/**} must send matching {@code X-Admin-Key}. */
    private String apiKey = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
