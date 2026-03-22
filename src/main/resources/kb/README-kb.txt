SRS knowledge files (RAG)
-------------------------

1) ssrp-srs.pdf
   Official PDF. Keep as the formal/archival source.

2) ssrp-srs-llm.md  (recommended for the chatbot)
   LLM-friendly Markdown: clear # / ## headings, short paragraphs, lists.
   The app prefers this file when conf.kb.srs-use-markdown=true and the file exists.

   Generate it from the PDF (JDK 17+, from project root):

   mvn -q compile exec:java "-Dexec.args=src/main/resources/kb/ssrp-srs.pdf src/main/resources/kb/ssrp-srs-llm.md"

   Then edit ssrp-srs-llm.md: fix headings, merge split sentences, add FAQ-style bullets.
   Clear MongoDB kb_documents and restart the app to re-embed.

3) If ssrp-srs-llm.md is missing, the app falls back to chunking the PDF automatically.
