// Package httpapi exposes the collector over HTTP. The endpoints are open by
// design: this is a debug-only dataset intake service deployed behind Traefik
// and Cloudflare, and requiring a token on the client blocked the keyboard
// uploads because the token defaulted to empty.
package httpapi

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"

	"gboard-collector/internal/store"
)

const (
	maxAudioBytes     = 10 << 20 // 10 MB of WAV payload
	multipartOverhead = 1 << 20  // room for boundaries and form fields
	maxUploadBytes    = maxAudioBytes + multipartOverhead
	// JSON escapes can expand bytes up to 6x ('<' -> \u003c). Keep raw meta
	// small enough that its marshaled form stays below the 2 MB record cap.
	maxMetaBytes = 256 << 10 // 256 KB
)

// New wires the HTTP routes onto the store.
func New(s *store.Store) http.Handler {
	h := &handler{store: s}
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", h.healthz)
	mux.HandleFunc("/v1/voice", h.voice)
	mux.HandleFunc("/v1/swipe", h.swipe)
	mux.HandleFunc("/v1/correction", h.correction)
	mux.HandleFunc("/v1/export", h.export)
	return mux
}

type handler struct {
	store *store.Store
}

func (h *handler) healthz(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = io.WriteString(w, "ok\n")
}

// voice accepts multipart form data with a JSON "meta" field and a WAV file
// in the "audio" field.
func (h *handler) voice(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "use POST")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxUploadBytes)
	if err := r.ParseMultipartForm(maxAudioBytes); err != nil {
		writeError(w, http.StatusBadRequest, "invalid multipart form: "+err.Error())
		return
	}
	metaRaw := r.FormValue("meta")
	if metaRaw == "" {
		writeError(w, http.StatusBadRequest, "missing meta field")
		return
	}
	if len(metaRaw) > maxMetaBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "meta field exceeds 256 KB")
		return
	}
	var meta store.Record
	if err := json.Unmarshal([]byte(metaRaw), &meta); err != nil {
		writeError(w, http.StatusBadRequest, "invalid meta JSON: "+err.Error())
		return
	}
	id, ok := meta["id"].(string)
	if !ok || id == "" {
		writeError(w, http.StatusBadRequest, "meta.id is required")
		return
	}
	file, _, err := r.FormFile("audio")
	if err != nil {
		writeError(w, http.StatusBadRequest, "missing audio field: "+err.Error())
		return
	}
	defer file.Close()
	wav, err := io.ReadAll(io.LimitReader(file, maxAudioBytes+1))
	if err != nil {
		writeError(w, http.StatusBadRequest, "cannot read audio: "+err.Error())
		return
	}
	if len(wav) > maxAudioBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "audio exceeds 10 MB")
		return
	}
	if err := h.store.SaveVoice(id, meta, wav); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	writeCreated(w, id)
}

// swipe accepts a JSON object with at least id, sessionId and ts.
func (h *handler) swipe(w http.ResponseWriter, r *http.Request) {
	h.jsonRecord(w, r, "swipe", []string{"id", "sessionId", "ts"})
}

// correction accepts a JSON object with at least id, sessionId, kind and ts.
func (h *handler) correction(w http.ResponseWriter, r *http.Request) {
	h.jsonRecord(w, r, "correction", []string{"id", "sessionId", "kind", "ts"})
}

func (h *handler) jsonRecord(w http.ResponseWriter, r *http.Request, kind string, required []string) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "use POST")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
	var rec store.Record
	dec := json.NewDecoder(r.Body)
	dec.UseNumber()
	if err := dec.Decode(&rec); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return
	}
	for _, key := range required {
		v, ok := rec[key]
		if !ok || v == nil || v == "" {
			writeError(w, http.StatusBadRequest, key+" is required")
			return
		}
	}
	id, _ := rec["id"].(string)
	if err := h.store.Append(kind, rec); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeCreated(w, id)
}

func (h *handler) export(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "use GET")
		return
	}
	data, err := h.store.Export()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	if err := json.NewEncoder(w).Encode(data); err != nil {
		fmt.Fprintf(w, "\n")
	}
}

func writeCreated(w http.ResponseWriter, id string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(store.Record{"id": id, "status": "ok"})
}

func writeError(w http.ResponseWriter, code int, message string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(store.Record{"error": message})
}
