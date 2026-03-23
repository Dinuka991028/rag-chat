package ai_chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for unknown-query training workflow.
 */
@Component
@ConfigurationProperties(prefix = "conf.unknown-training")
public class UnknownTrainingProperties {

    private boolean enabled = true;
    private String cron = "0 0 * * * *";
    private int batchSize = 50;
    private int minFrequency = 2;
    private boolean autoApprove = false;
    private boolean deleteProcessedUnknowns = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMinFrequency() {
        return minFrequency;
    }

    public void setMinFrequency(int minFrequency) {
        this.minFrequency = minFrequency;
    }

    public boolean isAutoApprove() {
        return autoApprove;
    }

    public void setAutoApprove(boolean autoApprove) {
        this.autoApprove = autoApprove;
    }

    public boolean isDeleteProcessedUnknowns() {
        return deleteProcessedUnknowns;
    }

    public void setDeleteProcessedUnknowns(boolean deleteProcessedUnknowns) {
        this.deleteProcessedUnknowns = deleteProcessedUnknowns;
    }
}
