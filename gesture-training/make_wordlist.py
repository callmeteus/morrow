import json, os
from collections import Counter

work = r"C:\Users\Gio\Documents\Projetos\gboard-local-voice\gesture-training"
files = [os.path.join(work, "pt_gestures.jsonl"), os.path.join(work, "futo_dev.jsonl")]
counts = Counter()
for f in files:
    if not os.path.exists(f):
        print("skip", f)
        continue
    with open(f, encoding="utf-8") as fh:
        for line in fh:
            try:
                w = json.loads(line)["word"].strip().lower()
                if w and all(c.isalpha() or c in "-'" for c in w):
                    counts[w] += 1
            except Exception:
                pass

out = os.path.join(work, "wordlist.tsv")
with open(out, "w", encoding="utf-8") as fo:
    for w, c in counts.most_common():
        # frequency 0..255, log-scaled
        freq = min(255, max(1, int(40 + 30 * __import__("math").log2(c + 1))))
        fo.write(f"{w}\t{freq}\n")
print("words:", len(counts), "->", out, os.path.getsize(out), "bytes")
