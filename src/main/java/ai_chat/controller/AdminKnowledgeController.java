package ai_chat.controller;

import ai_chat.dto.AddKnowledgeRequest;
import ai_chat.dto.KnowledgeAddResponse;
import ai_chat.domain.KbTrainingDraft;
import ai_chat.domain.UnknownQuery;
import ai_chat.repository.KbTrainingDraftRepository;
import ai_chat.repository.KnowledgeDocumentRepository;
import ai_chat.service.KnowledgeAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Operational API: add knowledge from customer support / content admins, and triage unknown questions.
 * Secured via {@link ai_chat.config.AdminGateFilter} and {@code conf.admin.*}.
 */
@RestController
@RequestMapping("/admin")
@Tag(name = "Admin — Knowledge", description = "Feed KB from unknown-question review or PDF imports")
public class AdminKnowledgeController {

    private final KnowledgeAdminService knowledgeAdminService;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KbTrainingDraftRepository kbTrainingDraftRepository;

    @Autowired
    public AdminKnowledgeController(
            KnowledgeAdminService knowledgeAdminService,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            KbTrainingDraftRepository kbTrainingDraftRepository) {
        this.knowledgeAdminService = knowledgeAdminService;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.kbTrainingDraftRepository = kbTrainingDraftRepository;
    }

    @PostMapping(value = "/knowledge", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Add one text article to the knowledge base (embedded + MongoDB)")
    public ResponseEntity<KnowledgeAddResponse> addKnowledge(@RequestBody AddKnowledgeRequest body) {
        long total = knowledgeAdminService.addTextEntry(body);
        return ResponseEntity.ok(new KnowledgeAddResponse(total, 1));
    }

    @PostMapping(value = "/knowledge/import-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import a PDF: extract text, chunk like SRS seed, embed all chunks")
    public ResponseEntity<KnowledgeAddResponse> importPdf(
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Stored on each chunk, e.g. Policy or SRS")
            @RequestParam(value = "category", required = false) String category,
            @Parameter(description = "Prefix for source id; filename is appended")
            @RequestParam(value = "sourcePrefix", required = false) String sourcePrefix)
            throws IOException {
        int chunks = knowledgeAdminService.importPdf(file, category, sourcePrefix);
        long total = knowledgeDocumentRepository.count();
        return ResponseEntity.ok(new KnowledgeAddResponse(total, chunks));
    }

    @GetMapping("/unknown-queries")
    @Operation(summary = "List customer questions that RAG could not answer (newest first)")
    public Page<UnknownQuery> listUnknown(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable p = PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)));
        return knowledgeAdminService.listUnknownQueries(p);
    }

    @DeleteMapping("/unknown-queries/{id}")
    @Operation(summary = "Remove an unknown-query row after you have added KB coverage or dismissed it")
    public ResponseEntity<Map<String, String>> deleteUnknown(@PathVariable String id) {
        knowledgeAdminService.deleteUnknownQuery(id);
        return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
    }

    @GetMapping(value = "/unknown-training/drafts")
    @Operation(summary = "List unknown-training drafts by status (newest first)")
    public org.springframework.data.domain.Page<KbTrainingDraft> listDrafts(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        KbTrainingDraft.Status st;
        try {
            st = KbTrainingDraft.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown draft status: " + status);
        }
        var p = PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)));
        return kbTrainingDraftRepository.findByStatusOrderByCreatedAtDesc(st, p);
    }

    @PostMapping(value = "/unknown-training/drafts/{id}/approve")
    @Operation(summary = "Approve a draft and import it into the KB")
    public ResponseEntity<Map<String, Object>> approveDraft(@PathVariable String id) {
        KbTrainingDraft draft = kbTrainingDraftRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Draft not found"));

        if (draft.getStatus() != KbTrainingDraft.Status.PENDING) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Draft is not in PENDING status");
        }
        if (draft.getProposedAnswer() == null || draft.getProposedAnswer().isBlank()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Draft has empty proposedAnswer");
        }

        AddKnowledgeRequest req = new AddKnowledgeRequest();
        req.setTitle(draft.getQuestion());
        req.setContent(draft.getProposedAnswer());
        req.setCategory("UnknownTraining");
        req.setSource("unknown-training");

        long total = knowledgeAdminService.addTextEntry(req);
        draft.setStatus(KbTrainingDraft.Status.IMPORTED);
        draft.setUpdatedAt(new Date());
        kbTrainingDraftRepository.save(draft);

        return ResponseEntity.ok(Map.of("status", "imported", "id", id, "totalKbDocuments", total));
    }

    @PostMapping(value = "/unknown-training/drafts/{id}/reject")
    @Operation(summary = "Reject a draft (it will never be imported)")
    public ResponseEntity<Map<String, String>> rejectDraft(@PathVariable String id) {
        KbTrainingDraft draft = kbTrainingDraftRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Draft not found"));

        if (draft.getStatus() != KbTrainingDraft.Status.PENDING) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Draft is not in PENDING status");
        }
        draft.setStatus(KbTrainingDraft.Status.REJECTED);
        draft.setUpdatedAt(new Date());
        kbTrainingDraftRepository.save(draft);

        return ResponseEntity.ok(Map.of("status", "rejected", "id", id));
    }

    @PostMapping(value = "/unknown-training/drafts/{id}/import")
    @Operation(summary = "Import a draft into the KB (same as approve, kept for convenience)")
    public ResponseEntity<Map<String, Object>> importDraft(@PathVariable String id) {
        return approveDraft(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "bad request"));
    }
}
