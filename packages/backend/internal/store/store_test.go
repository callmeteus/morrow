package store

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestSaveVoiceAndExport(t *testing.T) {
	dir := t.TempDir()
	st, err := New(dir, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UnixMilli()
	if err := st.SaveVoice("voice-1", Record{"sessionId": "s1", "ts": now}, []byte("RIFFfake")); err != nil {
		t.Fatal(err)
	}
	if err := st.Append("swipe", Record{"id": "sw-1", "sessionId": "s1", "ts": now}); err != nil {
		t.Fatal(err)
	}
	if err := st.Append("correction", Record{"id": "c-1", "sessionId": "s1", "kind": "asr_word", "ts": now}); err != nil {
		t.Fatal(err)
	}
	data, err := st.Export()
	if err != nil {
		t.Fatal(err)
	}
	for _, kind := range []string{"voice", "swipe", "correction"} {
		if len(data[kind]) != 1 {
			t.Fatalf("%s: expected 1 record, got %d", kind, len(data[kind]))
		}
	}
	if data["voice"][0]["audioPath"] != "audio/voice-1.wav" {
		t.Fatalf("unexpected audioPath: %v", data["voice"][0]["audioPath"])
	}
	if data["voice"][0]["expiresAt"] == nil {
		t.Fatal("missing expiresAt")
	}
	if _, err := os.Stat(filepath.Join(dir, "audio", "voice-1.wav")); err != nil {
		t.Fatalf("wav not written: %v", err)
	}
}

func TestSaveVoiceRejectsBadID(t *testing.T) {
	st, err := New(t.TempDir(), time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"", "../evil", "a/b", ".."} {
		if err := st.SaveVoice(id, Record{}, []byte("x")); err == nil {
			t.Fatalf("expected error for id %q", id)
		}
	}
}

// TestJanitorTTL proves retention with an injected server clock: no sleep, no
// flakiness, and a client ts far in the past cannot shorten the window.
func TestJanitorTTL(t *testing.T) {
	dir := t.TempDir()
	st, err := New(dir, 1*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	clock := time.Date(2026, 11, 21, 12, 0, 0, 0, time.UTC)
	st.now = func() time.Time { return clock }

	// A stale client ts must not make these records expire early.
	stale := time.Date(2023, 1, 1, 0, 0, 0, 0, time.UTC).UnixMilli()
	mustSave(t, st, "expired", Record{"sessionId": "s", "ts": stale}, []byte("RIFFold"))
	mustAppend(t, st, "swipe", Record{"id": "sw-exp", "sessionId": "s", "ts": stale})
	mustAppend(t, st, "correction", Record{"id": "c-exp", "sessionId": "s", "kind": "asr_word", "ts": stale})

	data, err := st.Export()
	if err != nil {
		t.Fatal(err)
	}
	for _, kind := range kinds {
		if len(data[kind]) != 1 {
			t.Fatalf("before expiry: %s has %d records", kind, len(data[kind]))
		}
	}
	// expiresAt is anchored to the server clock, not the stale client ts.
	for _, kind := range kinds {
		if got := toMillis(data[kind][0]["expiresAt"]); got != clock.Add(time.Second).UnixMilli() {
			t.Fatalf("%s expiresAt = %d, want %d", kind, got, clock.Add(time.Second).UnixMilli())
		}
	}

	clock = clock.Add(2 * time.Second)
	mustAppend(t, st, "correction", Record{"id": "c-fresh", "sessionId": "s", "kind": "suggestion_selected"})

	if err := st.Janitor(); err != nil {
		t.Fatal(err)
	}

	data, _ = st.Export()
	if len(data["voice"]) != 0 || len(data["swipe"]) != 0 {
		t.Fatalf("expired records survived: voice=%d swipe=%d", len(data["voice"]), len(data["swipe"]))
	}
	if len(data["correction"]) != 1 || data["correction"][0]["id"] != "c-fresh" {
		t.Fatalf("fresh record lost or stale kept: %+v", data["correction"])
	}
	if _, err := os.Stat(filepath.Join(dir, "audio", "expired.wav")); !os.IsNotExist(err) {
		t.Fatalf("expired wav not removed: %v", err)
	}
}

// SEC-2 regression: a >2 MB line in a JSONL file must not make Export fail
// for the whole dataset. The reader must skip the oversized line.
func TestOversizedLineDoesNotBreakExport(t *testing.T) {
	dir := t.TempDir()
	st, err := New(dir, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	// Write a valid record and an oversized one directly to voice.jsonl.
	mustSave(t, st, "good", Record{"sessionId": "s"}, []byte("RIFFok"))
	f, err := os.OpenFile(st.jsonlPath("voice"), os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	big := `{"id":"poison","blob":"` + strings.Repeat("x", 3<<20) + `"}` + "\n"
	if _, err := f.WriteString(big); err != nil {
		t.Fatal(err)
	}
	f.Close()

	data, err := st.Export()
	if err != nil {
		t.Fatalf("export must survive an oversized line: %v", err)
	}
	if len(data["voice"]) != 1 || data["voice"][0]["id"] != "good" {
		t.Fatalf("expected only the good record, got %+v", data["voice"])
	}
	if err := st.Janitor(); err != nil {
		t.Fatalf("janitor must survive an oversized line: %v", err)
	}
}

func mustSave(t *testing.T, st *Store, id string, rec Record, wav []byte) {
	t.Helper()
	if err := st.SaveVoice(id, rec, wav); err != nil {
		t.Fatal(err)
	}
}

func mustAppend(t *testing.T, st *Store, kind string, rec Record) {
	t.Helper()
	if err := st.Append(kind, rec); err != nil {
		t.Fatal(err)
	}
}
