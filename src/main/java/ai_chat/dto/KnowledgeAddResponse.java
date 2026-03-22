package ai_chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeAddResponse {

    private long totalKbDocuments;
    private int chunksAdded;
}
