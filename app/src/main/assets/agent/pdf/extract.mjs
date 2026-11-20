// Freedom Agent's PDF text extraction (#601): desktop's
// src/renderer/lib/pdf-processor.js `extractText`, in a WebView of its own
// that serves nothing but this page, pdf.js and the one PDF it's asked
// about (PdfText.kt). The PDF is untrusted: no scripting, no eval, no
// fonts loaded, no network. The answer goes back on `pdfResult`, the only
// channel this page has; Kotlin checks its shape again.
import * as pdfjsLib from './pdf.min.mjs';

const MAX_PAGES = 500;
const MAX_TEXT_PAGES = 4;
const MAX_TEXT_CHARS = 48 * 1024;

pdfjsLib.GlobalWorkerOptions.workerSrc = new URL('./pdf.worker.min.mjs', import.meta.url).href;

function post(value) {
  // eslint-disable-next-line no-undef
  pdfResult.postMessage(JSON.stringify(value));
}

function fail(code) {
  post({ ok: false, code });
}

function safeCode(error) {
  const name = typeof error?.name === 'string' ? error.name : '';
  if (error?.code === 'PDF_PASSWORD_REQUIRED' || name === 'PasswordException') return 'PDF_PASSWORD_REQUIRED';
  if (error?.code === 'PDF_PAGE_LIMIT') return 'PDF_PAGE_LIMIT';
  if (error?.code === 'PDF_PAGE_OUT_OF_RANGE') return 'PDF_PAGE_OUT_OF_RANGE';
  if (name === 'InvalidPDFException' || name === 'MissingPDFException') return 'PDF_INVALID';
  return 'PDF_PROCESSING_FAILED';
}

function pageText(items, limit) {
  let text = '';
  for (const item of items) {
    if (typeof item?.str !== 'string') continue;
    text += item.str;
    text += item.hasEOL ? '\n' : ' ';
    if (text.length >= limit) break;
  }
  return text.replace(/[ \t]+\n/g, '\n').replace(/[ \t]{2,}/g, ' ').trim();
}

async function run() {
  let job;
  try {
    job = JSON.parse(decodeURIComponent(location.hash.slice(1)));
  } catch {
    return fail('PDF_PROCESSING_FAILED');
  }
  const page = job?.page;
  const pageCount = job?.pageCount;
  if (typeof job?.token !== 'string' || !/^[a-f0-9]{32}$/.test(job.token) ||
      !Number.isSafeInteger(page) || page < 1 || page > MAX_PAGES ||
      !Number.isSafeInteger(pageCount) || pageCount < 1 || pageCount > MAX_TEXT_PAGES) {
    return fail('PDF_PROCESSING_FAILED');
  }
  const response = await fetch(`/job/${job.token}.pdf`);
  if (!response.ok) return fail('PDF_PROCESSING_FAILED');
  const data = new Uint8Array(await response.arrayBuffer());
  const loadingTask = pdfjsLib.getDocument({
    data,
    isEvalSupported: false,
    isOffscreenCanvasSupported: false,
    disableFontFace: true,
    useSystemFonts: false,
    useWasm: false,
    stopAtErrors: true,
    disableAutoFetch: true,
    disableRange: true,
    disableStream: true,
    enableXfa: false,
  });
  let rejectPassword;
  const passwordRequest = new Promise((_resolve, reject) => { rejectPassword = reject; });
  loadingTask.onPassword = () => {
    rejectPassword(Object.assign(new Error('password'), { code: 'PDF_PASSWORD_REQUIRED' }));
    void loadingTask.destroy();
  };
  const document = await Promise.race([loadingTask.promise, passwordRequest]);
  try {
    if (document.numPages > MAX_PAGES) throw Object.assign(new Error('pages'), { code: 'PDF_PAGE_LIMIT' });
    if (page > document.numPages) throw Object.assign(new Error('range'), { code: 'PDF_PAGE_OUT_OF_RANGE' });
    const lastPage = Math.min(document.numPages, page + pageCount - 1);
    const pages = [];
    let remaining = MAX_TEXT_CHARS;
    for (let n = page; n <= lastPage && remaining > 0; n += 1) {
      const p = await document.getPage(n);
      try {
        const content = await p.getTextContent({ disableNormalization: false });
        const text = pageText(content.items, remaining).slice(0, remaining);
        pages.push({ page: n, text });
        remaining -= text.length;
      } finally {
        p.cleanup(true);
      }
    }
    post({
      ok: true,
      pageCount: document.numPages,
      pages,
      truncated: remaining === 0,
    });
  } finally {
    // Its own failure mustn't take the place of the answer, or of the reason.
    try {
      await document.destroy();
    } catch {
      // Nothing left to do: the WebView goes after the job.
    }
  }
}

run().catch((error) => fail(safeCode(error)));
