# Dataset collector end-to-end test

Run at 2026-11-21 17:03:06 by tools/test-collector.ps1.
Real Go server on 127.0.0.1:18081, data under docs/evidence/collector-run/.

## Output

```
healthz -> ok  200
export without token -> 401 (expect 401)
POST /v1/voice -> 201 (expect 201)
POST /v1/swipe -> 201 (expect 201)
POST /v1/correction kind=asr_word -> 201 (expect 201)
POST /v1/correction kind=suggestion_selected -> 201 (expect 201)
POST /v1/correction kind=suggestion_ignored -> 201 (expect 201)
POST /v1/correction kind=suggestion_missed -> 201 (expect 201)
POST /v1/correction kind=fallback_generic -> 201 (expect 201)
POST /v1/correction kind=backspace_burst -> 201 (expect 201)
POST /v1/correction kind=swipe_failure -> 201 (expect 201)
export voice=1 swipe=1 correction=7
voice: raw='ola mundo' clean='ola mundo' corrected='olá mundo' inferMs=1234 audioPath=audio/voice-e2e-001.wav
swipe: trail='nao' decoded='não' points=2
correction kinds: asr_word,suggestion_selected,suggestion_ignored,suggestion_missed,fallback_generic,backspace_burst,swipe_failure
uploaded WAV bytes: 32044
RESULT: PASS
```
