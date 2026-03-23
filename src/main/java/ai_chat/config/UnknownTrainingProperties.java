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
    /** Cap on draft count per scheduled run in semi-auto mode. */
    private int maxDraftsPerRun = 20;
    /** Cap on KB imports per scheduled run in full-auto mode. */
    private int maxImportsPerRun = 20;
    /** Minimum proposed answer length (characters) to consider for draft creation/import. */
    private int minAnswerChars = 80;
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

    public int getMaxDraftsPerRun() {
        return maxDraftsPerRun;
    }

    public void setMaxDraftsPerRun(int maxDraftsPerRun) {
        this.maxDraftsPerRun = maxDraftsPerRun;
    }

    public int getMaxImportsPerRun() {
        return maxImportsPerRun;
    }

    public void setMaxImportsPerRun(int maxImportsPerRun) {
        this.maxImportsPerRun = maxImportsPerRun;
    }

    public int getMinAnswerChars() {
        return minAnswerChars;
    }

    public void setMinAnswerChars(int minAnswerChars) {
        this.minAnswerChars = minAnswerChars;
    }

    public boolean isDeleteProcessedUnknowns() {
        return deleteProcessedUnknowns;
    }

    public void setDeleteProcessedUnknowns(boolean deleteProcessedUnknowns) {
        this.deleteProcessedUnknowns = deleteProcessedUnknowns;
    }
}
