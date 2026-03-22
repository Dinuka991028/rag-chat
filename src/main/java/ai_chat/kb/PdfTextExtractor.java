package ai_chat.kb;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;

/** Extracts plain text from a PDF (text-based PDFs only; scanned pages need OCR). */
public final class PdfTextExtractor {

    private PdfTextExtractor() {}

    public static String extractText(InputStream pdfStream) throws IOException {
        try (PDDocument document = PDDocument.load(pdfStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }
}
