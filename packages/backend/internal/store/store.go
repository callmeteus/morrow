// Package store persists the collected dataset as plain JSONL files plus WAV
// blobs on disk. No database: the JSONL files are the source of truth and can
// be exported directly for training.
package store

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// maxRecordBytes bounds one JSONL line. It matches the reader cap so a record
// accepted for storage is always readable back by export and retention.
const maxRecordBytes = 2 << 20 // 2 MB

// Record is one JSONL line. The schema is open on purpose so the Android app
// can add fields without requiring a backend change.
type Record map[string]any

var kinds = []string{"voice", "swipe", "correction"}

// Store owns the data directory and enforces a retention window.
type Store struct {
	mu      sync.Mutex
	dataDir string
	ttl     time.Duration
	now     func() time.Time // server clock; injected by tests
}

// New creates the store and the audio subdirectory.
func New(dataDir string, ttl time.Duration) (*Store, error) {
	if ttl <= 0 {
		return nil, fmt.Errorf("ttl must be positive")
	}
	if err := os.MkdirAll(filepath.Join(dataDir, "audio"), 0o755); err != nil {
		return nil, fmt.Errorf("create data dir: %w", err)
	}
	return &Store{dataDir: dataDir, ttl: ttl, now: time.Now}, nil
}

func (s *Store) jsonlPath(kind string) string {
	return filepath.Join(s.dataDir, kind+".jsonl")
}

// stamp ensures every record carries ts (epoch millis) and expiresAt. The
// retention window is anchored to the server clock, never to the client's ts:
// a device with a wrong clock must not control when its data is deleted.
func (s *Store) stamp(rec Record) {
	if _, ok := rec["ts"]; !ok {
		rec["ts"] = s.now().UnixMilli()
	}
	if _, ok := rec["expiresAt"]; !ok {
		rec["expiresAt"] = s.now().Add(s.ttl).UnixMilli()
	}
}

func toMillis(v any) int64 {
	switch n := v.(type) {
	case int64:
		return n
	case int:
		return int64(n)
	case float64:
		return int64(n)
	case json.Number:
		i, _ := n.Int64()
		return i
	default:
		return time.Now().UnixMilli()
	}
}

func expiry(rec Record) int64 {
	if v, ok := rec["expiresAt"]; ok {
		return toMillis(v)
	}
	return 0
}

// Append writes one JSONL record for the given kind.
func (s *Store) Append(kind string, rec Record) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.storeRecordLocked(kind, rec)
}

// storeRecordLocked stamps and appends a record. The caller holds s.mu.
func (s *Store) storeRecordLocked(kind string, rec Record) error {
	s.stamp(rec)
	return s.appendLocked(kind, rec)
}

func (s *Store) appendLocked(kind string, rec Record) error {
	line, err := json.Marshal(rec)
	if err != nil {
		return fmt.Errorf("marshal record: %w", err)
	}
	if len(line) > maxRecordBytes {
		return fmt.Errorf("record too large: %d bytes exceeds %d", len(line), maxRecordBytes)
	}
	f, err := os.OpenFile(s.jsonlPath(kind), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return fmt.Errorf("open %s: %w", kind, err)
	}
	defer f.Close()
	if _, err := f.Write(append(line, '\n')); err != nil {
		return fmt.Errorf("write %s: %w", kind, err)
	}
	return nil
}

// SaveVoice stores the WAV blob under audio/<id>.wav and appends its metadata
// to voice.jsonl. The id is used as a filename so it must not contain path
// separators.
func (s *Store) SaveVoice(id string, meta Record, wavBytes []byte) error {
	if id == "" || strings.ContainsAny(id, `/\\`) || id == "." || id == ".." {
		return fmt.Errorf("invalid voice id %q", id)
	}
	if len(wavBytes) == 0 {
		return fmt.Errorf("empty wav payload")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	// Store a logical forward-slash path in JSONL so exports are portable.
	audioPath := "audio/" + id + ".wav"
	if err := os.WriteFile(filepath.Join(s.dataDir, "audio", id+".wav"), wavBytes, 0o644); err != nil {
		return fmt.Errorf("write wav: %w", err)
	}
	if meta == nil {
		meta = Record{}
	}
	meta["id"] = id
	meta["audioPath"] = audioPath
	return s.storeRecordLocked("voice", meta)
}

// Export returns every live JSONL collection, used by GET /v1/export.
func (s *Store) Export() (map[string][]Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make(map[string][]Record, len(kinds))
	for _, kind := range kinds {
		recs, err := s.readLocked(kind)
		if err != nil {
			return nil, err
		}
		out[kind] = recs
	}
	return out, nil
}

func (s *Store) readLocked(kind string) ([]Record, error) {
	f, err := os.Open(s.jsonlPath(kind))
	if err != nil {
		if os.IsNotExist(err) {
			return []Record{}, nil
		}
		return nil, fmt.Errorf("open %s: %w", kind, err)
	}
	defer f.Close()
	recs := []Record{}
	reader := bufio.NewReader(f)
	var line []byte
	for {
		chunk, err := reader.ReadBytes('\n')
		if err != nil && err != io.EOF {
			return nil, fmt.Errorf("read %s: %w", kind, err)
		}
		line = append(line, chunk...)
		if err == io.EOF && len(chunk) == 0 && len(line) == 0 {
			break
		}
		if err == io.EOF || chunk[len(chunk)-1] == '\n' {
			// One oversized or corrupt line must not fail the whole export or
			// stop retention; skip it with a warning to stderr.
			if len(line) > maxRecordBytes {
				fmt.Fprintf(os.Stderr, "skipping oversized record in %s: %d bytes\n", kind, len(line))
			} else {
				trimmed := bytes.TrimSpace(line)
				if len(trimmed) > 0 {
					var rec Record
					if json.Unmarshal(trimmed, &rec) == nil {
						recs = append(recs, rec)
					}
				}
			}
			line = line[:0]
			if err == io.EOF {
				break
			}
		}
	}
	return recs, nil
}

// Janitor removes expired records and their WAV blobs. It is safe to call
// repeatedly and is also invoked once at startup.
func (s *Store) Janitor() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now().UnixMilli()

	voice, err := s.readLocked("voice")
	if err != nil {
		return err
	}
	liveIDs := map[string]bool{}
	keptVoice := make([]Record, 0, len(voice))
	for _, rec := range voice {
		if expiry(rec) > now {
			if id, ok := rec["id"].(string); ok && id != "" {
				liveIDs[id] = true
			}
			keptVoice = append(keptVoice, rec)
		}
	}

	// Delete WAVs that are expired or have no live metadata record (orphans).
	entries, err := os.ReadDir(filepath.Join(s.dataDir, "audio"))
	if err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("list audio: %w", err)
	}
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".wav") {
			continue
		}
		id := strings.TrimSuffix(entry.Name(), ".wav")
		if !liveIDs[id] {
			_ = os.Remove(filepath.Join(s.dataDir, "audio", entry.Name()))
		}
	}

	if err := s.rewriteLocked("voice", keptVoice); err != nil {
		return err
	}
	for _, kind := range kinds[1:] { // voice already handled above
		recs, err := s.readLocked(kind)
		if err != nil {
			return err
		}
		kept := make([]Record, 0, len(recs))
		for _, rec := range recs {
			if expiry(rec) > now {
				kept = append(kept, rec)
			}
		}
		if err := s.rewriteLocked(kind, kept); err != nil {
			return err
		}
	}
	return nil
}

func (s *Store) rewriteLocked(kind string, recs []Record) error {
	tmp := s.jsonlPath(kind) + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return fmt.Errorf("create tmp %s: %w", kind, err)
	}
	for _, rec := range recs {
		line, err := json.Marshal(rec)
		if err != nil {
			f.Close()
			os.Remove(tmp)
			return fmt.Errorf("marshal %s: %w", kind, err)
		}
		if _, err := f.Write(append(line, '\n')); err != nil {
			f.Close()
			os.Remove(tmp)
			return fmt.Errorf("write tmp %s: %w", kind, err)
		}
	}
	if err := f.Close(); err != nil {
		os.Remove(tmp)
		return fmt.Errorf("close tmp %s: %w", kind, err)
	}
	if err := os.Rename(tmp, s.jsonlPath(kind)); err != nil {
		os.Remove(tmp)
		return fmt.Errorf("rename %s: %w", kind, err)
	}
	return nil
}

// StartJanitor runs the cleanup immediately and then on the given interval.
func (s *Store) StartJanitor(interval time.Duration) {
	go func() {
		if err := s.Janitor(); err != nil {
			fmt.Fprintf(os.Stderr, "janitor startup: %v\n", err)
		}
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for range ticker.C {
			if err := s.Janitor(); err != nil {
				fmt.Fprintf(os.Stderr, "janitor tick: %v\n", err)
			}
		}
	}()
}
