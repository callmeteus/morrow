// Command collector receives Android keyboard dataset uploads (voice WAVs,
// swipe gestures and corrector events) and stores them as JSONL on disk.
package main

import (
	"log"
	"net/http"
	"os"
	"time"

	"gboard-collector/internal/httpapi"
	"gboard-collector/internal/store"
)

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func main() {
	addr := env("COLLECTOR_ADDR", ":8080")
	dataDir := env("COLLECTOR_DATA_DIR", "/data")
	ttlRaw := env("COLLECTOR_TTL", "720h") // 30 days
	token := os.Getenv("COLLECTOR_TOKEN")
	if token == "" {
		log.Fatal("COLLECTOR_TOKEN is required")
	}
	ttl, err := time.ParseDuration(ttlRaw)
	if err != nil {
		log.Fatalf("invalid COLLECTOR_TTL %q: %v", ttlRaw, err)
	}

	st, err := store.New(dataDir, ttl)
	if err != nil {
		log.Fatalf("store init: %v", err)
	}
	st.StartJanitor(24 * time.Hour)

	srv := &http.Server{
		Addr:              addr,
		Handler:           httpapi.New(st, token),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("collector listening on %s (data=%s ttl=%s)", addr, dataDir, ttl)
	log.Fatal(srv.ListenAndServe())
}
