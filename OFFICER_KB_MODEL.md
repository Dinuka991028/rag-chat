# Officer KB Model (Review Draft)

This document captures the proposed separate model for the new `src/main/resources/kb/officer_kb.json` dataset.

The intent is to keep officer job summary data in a separate document model/collection (not mixed into `kb_documents` vector chunks), while still supporting VectorStore-style retrieval for the internal office chat agent.

```java
public class CompletedJobSummaryDTO {
    @Id
    private String id;
    private long jobId;
    private String taskId;
    private String taskName;
    private String taskDefinition;
    private int serviceId;
    private String serviceName;
    private String shipNumber;
    private String shipName;
    private String vesselType;
    private String customerId;
    private String customerName;
    private String customerMobileNumber;
    private String serialNumber;
    private String userRole;
    private String submitDate;
    private String completedDate;
    private String taskStatus;
    private String key;
    private String processInstanceId;
    private Object workflowDTO;
    private Object executeTaskDTO;
    private Object camundaDBSavePropDto;
    private String startDateTime;
    private String taskAction;

    /** Embedding vector; set when rows are ingested via vector indexing flow. */
    private List<Float> embedding;

    /** Embedding model tag used to create this vector (for compatibility checks). */
    private String embeddingModel;

    /** Application-level embedding schema/version (for safe migration controls). */
    private String embeddingVersion;
}
```

Implementation changes should proceed only after this model is approved.
