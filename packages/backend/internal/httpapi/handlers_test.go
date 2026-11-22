package httpapi

import (
	"bytes"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"gboard-collector/internal/store"
)

const token = "test-token"

func newTestHandler(t *testing.T) http.Handler {
	t.Helper()
	st, err := store.New(t.TempDir(), time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	return New(st)
}

func do(r http.Handler, method, path, body string, withToken bool) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if withToken {
		req.Header.Set("X-Collector-Token", token)
	}
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w
}

func TestHealthzIsOpen(t *testing.T) {
	h := newTestHandler(t)
	w := do(h, http.MethodGet, "/healthz", "", false)
	if w.Code != http.StatusOK || strings.TrimSpace(w.Body.String()) != "ok" {
		t.Fatalf("healthz: %d %q", w.Code, w.Body.String())
	}
}

func TestNoTokenRequired(t *testing.T) {
	h := newTestHandler(t)
	body := `{"id":"x","sessionId":"s","kind":"asr_word","ts":1}`
	// The collector is open by design; a debug keyboard with an empty token
	// must still be able to upload. The header is optional and ignored.
	if w := do(h, http.MethodPost, "/v1/correction", body, false); w.Code != http.StatusCreated {
		t.Fatalf("expected 201 without token, got %d: %s", w.Code, w.Body.String())
	}
	if w := do(h, http.MethodPost, "/v1/correction", body, true); w.Code != http.StatusCreated {
		t.Fatalf("expected 201 with token (ignored), got %d: %s", w.Code, w.Body.String())
	}
}

func TestCorrectionValidation(t *testing.T) {
	h := newTestHandler(t)
	for _, body := range []string{
		`{"sessionId":"s","kind":"asr_word","ts":1}`,
		`{"id":"x","kind":"asr_word","ts":1}`,
		`{"id":"x","sessionId":"s","ts":1}`,
		`{"id":"x","sessionId":"s","kind":"asr_word"}`,
		`not json`,
	} {
		if w := do(h, http.MethodPost, "/v1/correction", body, true); w.Code != http.StatusBadRequest {
			t.Fatalf("body %q: expected 400, got %d", body, w.Code)
		}
	}
}

func TestSwipeAndCorrectionAndExport(t *testing.T) {
	h := newTestHandler(t)
	swipe := `{"id":"sw-1","sessionId":"s","trail":"nao","candidates":["não"],"ts":1}`
	if w := do(h, http.MethodPost, "/v1/swipe", swipe, true); w.Code != http.StatusCreated {
		t.Fatalf("swipe: %d %s", w.Code, w.Body.String())
	}
	corr := `{"id":"c-1","sessionId":"s","kind":"suggestion_selected","index":0,"ts":2}`
	if w := do(h, http.MethodPost, "/v1/correction", corr, true); w.Code != http.StatusCreated {
		t.Fatalf("correction: %d %s", w.Code, w.Body.String())
	}
	w := do(h, http.MethodGet, "/v1/export", "", true)
	if w.Code != http.StatusOK {
		t.Fatalf("export: %d", w.Code)
	}
	var out map[string][]map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	if len(out["swipe"]) != 1 || len(out["correction"]) != 1 {
		t.Fatalf("unexpected export: %+v", out)
	}
}

func TestVoiceUpload(t *testing.T) {
	h := newTestHandler(t)
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	meta := `{"id":"voice-1","sessionId":"s","transcriptRaw":"ola","ts":1}`
	_ = mw.WriteField("meta", meta)
	part, err := mw.CreateFormFile("audio", "voice-1.wav")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = part.Write([]byte("RIFFfakewavdata"))
	_ = mw.Close()

	req := httptest.NewRequest(http.MethodPost, "/v1/voice", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("X-Collector-Token", token)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != http.StatusCreated {
		t.Fatalf("voice: %d %s", w.Code, w.Body.String())
	}

	ex := do(h, http.MethodGet, "/v1/export", "", true)
	var out map[string][]map[string]any
	_ = json.Unmarshal(ex.Body.Bytes(), &out)
	if len(out["voice"]) != 1 || out["voice"][0]["audioPath"] != "audio/voice-1.wav" {
		t.Fatalf("unexpected voice record: %+v", out["voice"])
	}
}

func TestAudioSizeLimit(t *testing.T) {
	h := newTestHandler(t)
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	_ = mw.WriteField("meta", `{"id":"big","sessionId":"s","ts":1}`)
	part, _ := mw.CreateFormFile("audio", "big.wav")
	_, _ = part.Write(bytes.Repeat([]byte("x"), (10<<20)+2))
	_ = mw.Close()
	req := httptest.NewRequest(http.MethodPost, "/v1/voice", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("X-Collector-Token", token)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("expected 413 for oversized audio, got %d", w.Code)
	}
}

// SEC-2 regression: a meta field that marshals past the JSONL reader's 2 MB
// line cap must be rejected before it can poison voice.jsonl.
func TestOversizedMetaRejected(t *testing.T) {
	h := newTestHandler(t)
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	meta := `{"id":"poison","transcriptRaw":"` + strings.Repeat("<", 400000) + `"}`
	_ = mw.WriteField("meta", meta)
	part, _ := mw.CreateFormFile("audio", "small.wav")
	_, _ = part.Write([]byte("RIFFfake"))
	_ = mw.Close()
	req := httptest.NewRequest(http.MethodPost, "/v1/voice", &buf)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("X-Collector-Token", token)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != http.StatusRequestEntityTooLarge && w.Code != http.StatusBadRequest {
		t.Fatalf("expected 4xx for oversized meta, got %d", w.Code)
	}
	// Export must still work after the rejected upload.
	ex := do(h, http.MethodGet, "/v1/export", "", true)
	if ex.Code != http.StatusOK {
		t.Fatalf("export after oversized meta: %d %s", ex.Code, ex.Body.String())
	}
}
