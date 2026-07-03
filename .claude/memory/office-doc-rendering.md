---
name: office-doc-rendering
description: How to build and visually QA .docx/.pptx files on this Windows machine
metadata: 
  node_type: memory
  type: reference
  originSessionId: 0b27a57d-c3b1-42a8-921b-0a832c485551
---

Producing and validating Office documents on this machine (win32):

- **No LibreOffice / no `pdftoppm`.** The docx/pptx skills' `soffice.py` wrapper assumes a Linux
  sandbox with `soffice` on PATH — it fails here. The Windows Store `python.exe` also **cannot
  read the skills-plugin dir** under `…\Roaming\Claude\…` (sandboxed), so those skill scripts
  error with "can't open file" even when `ls` sees them. It *can* read files under `C:\sandbox`.
- **Build:** `.docx` via the `docx` npm package; `.pptx` via `pptxgenjs`. Install into a writable
  dir (scratchpad or project root) and run with `node`.
- **Render / visual QA:** use native Office via **PowerShell COM**, not LibreOffice.
  - PowerPoint → PNGs: `New-Object -ComObject PowerPoint.Application`; `Presentations.Open(path,$true,$false,$false)`; `$pres.Export($dir,"PNG",1600,900)` (or `$pres.Slides.Item(n).Export(...)` per slide). Files come out `Slide1.PNG`…
  - Word page count: `New-Object -ComObject Word.Application`; `$doc.Repaginate(); $doc.ComputeStatistics(2)`.
  - Always `Quit()` the COM app in a finally/try.
- **Workflow that worked:** generate file → COM-export slides to PNG → fresh-eyes subagent reads
  the PNGs for overflow/overlap/contrast → fix the build script → re-export only changed slides.

**Why:** saved for next time so an Office-doc task here skips the LibreOffice/Store-python
dead-ends. Related: [[eoi-agent-project]] (stakeholder deck/doc built this way).
