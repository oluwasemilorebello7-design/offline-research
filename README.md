# Offline Research (Android)

A casual lookup + research assistant that runs **100% on-device, offline**: a local LLM (llama.cpp, memory-mapped from flash)
plus a local encyclopedia (SQLite FTS5 over Wikipedia). It plans searches, retrieves passages, and writes a **cited** answer, so a
small or sparse model can handle comparison, explanation and synthesis questions that it can't answer from its weights alone.

Built for the community bounty *"Build the Best Offline AI Research App for Android"* (inspired by Vitalik Buterin's post).

> **Status: MVP.** Architecture and code are complete; benchmark numbers in the table below are **to be filled in from your device**
> before you post the demo. Nothing here has been measured yet - don't claim numbers you haven't recorded.

## How it works

```
question ──► PLAN (LLM, ~40 tokens, greedy)       "compare X and Y" -> ["X", "Y", ...]
         ──► RETRIEVE (SQLite FTS5 / BM25)         per query, AND then OR; reciprocal-rank fusion; <=2 chunks/article
         ──► ANSWER (LLM, streamed, cited [1][2])  grounded in ~2k tokens of passages
```

* **Inference**: llama.cpp via a ~200-line JNI bridge (`app/src/main/cpp/llama_jni.cpp`). Weights are `mmap`ed, so a model much larger
  than RAM works: the OS pages in only the experts that are actually used. That is what makes a ~30B-total / ~3B-active MoE feasible in 12 GB.
* **Knowledge**: one SQLite file (`knowledge.sqlite`) built on a PC by `scripts/build_index.py`. External-content FTS5 (Porter stemming,
  title boosted 5x). No embedding model needed for v0.1.
* **Offline by construction**: the manifest declares **no `INTERNET` permission**, so Android blocks every socket the app opens. The UI shows a
  badge that verifies this at runtime. No Google Play Services: SQLite is bundled (`androidx.sqlite:sqlite-bundled`), inference is our own native lib.

## Quick start

Prereqs: Android Studio (or JDK 17 + Android SDK + NDK r27+ + CMake 3.22), a phone with USB debugging (GrapheneOS works), `git`, `adb`.

```bash
# 1. clone + fetch llama.cpp (pin a recent release tag and record it in docs/BENCHMARKS.md)
git clone <this repo> offline-research && cd offline-research
git submodule add https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
git -C app/src/main/cpp/llama.cpp checkout <recent-release-tag>

# 2. build + install (arm64 only). Native code is always built in Release mode.
gradle wrapper --gradle-version 8.9      # once; or just open the folder in Android Studio
./gradlew installDebug                    # phone: USB debugging on
#   Pixel 8+/SD 8 Gen 1+ get faster prompt processing with:  ./gradlew installDebug -PcpuArch=armv8.6-a+i8mm+dotprod

# 3. get a model (on a PC) - pick ONE to start
pip install -U "huggingface_hub[cli]"
huggingface-cli download unsloth/Qwen3-4B-Instruct-2507-GGUF Qwen3-4B-Instruct-2507-Q4_K_M.gguf --local-dir data/models
# MoE tier (~18 GB, 3B active - the Vitalik-style bet; expect flash-bound speed):
huggingface-cli download unsloth/Qwen3-30B-A3B-Instruct-2507-GGUF Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf --local-dir data/models

# 4. build the knowledge base (on a PC, one time)
pip install pyarrow
huggingface-cli download wikimedia/wikipedia --repo-type dataset --include "20231101.en/*" --local-dir data/wiki
python scripts/build_index.py --source parquet --input "data/wiki/20231101.en/*.parquet" \
    --out data/knowledge.sqlite --name "Wikipedia EN 2023-11" --min-chars 2500 --max-chunks 6 --skip-lists
#   quick smoke test with no big download:  see docs/SMOKE_TEST.md

# 5. push to the phone, then open the app (works in airplane mode)
scripts/push_assets.sh data/models/Qwen3-4B-Instruct-2507-Q4_K_M.gguf data/knowledge.sqlite
```

No laptop? See `docs/LAPTOP_FREE.md` (GitHub Actions builds the APK, Colab builds the knowledge base, the app imports files itself). Full laptop walkthrough: `docs/DEPLOY_GUIDE.md`.

The model and knowledge base are **not** bundled in the APK (too large for GitHub); steps 3-5 are the documented download/install path.

## Bounty requirements checklist

| Requirement | How it's met |
|---|---|
| Android + GrapheneOS | arm64-v8a, no Play Services, no proprietary SDKs |
| <= 12 GB RAM | `mmap`ed weights (page cache, reclaimable), 4k context, small KV cache |
| <= 50 GB total | 4B tier ~2.5 GB + KB (tunable, see `--min-chars/--max-chunks`); 30B-A3B tier ~18.6 GB + KB. Check with `du -sh` before submitting |
| Works offline / no network | No INTERNET permission (OS-enforced), no network code compiled in (`LLAMA_CURL=OFF`) |
| No Play Services | Bundled SQLite + native llama.cpp |
| Beyond factual recall | Query planning + multi-query retrieval + cited synthesis (comparison / explanation / multi-step) |
| Usable speed | Record tok/s in `docs/BENCHMARKS.md` (the app prints prefill/decode tok/s under every answer) |
| Public repo, reproducible | This repo + `scripts/` + CI build (`.github/workflows/build.yml`) |
| Documented resources | Table below - keep it accurate |

## Resources used

| Resource | Role | License |
|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | inference engine | MIT |
| Qwen3-4B-Instruct-2507 (GGUF, Q4_K_M) | "fast" tier LLM | Apache-2.0 |
| Qwen3-30B-A3B-Instruct-2507 (GGUF, Q4_K_M) | "MoE" tier LLM (~3B active/token) | Apache-2.0 |
| Wikipedia via `wikimedia/wikipedia` (HF) | knowledge base text | CC BY-SA 4.0 |
| SQLite FTS5 via `androidx.sqlite:sqlite-bundled` | retrieval | Apache-2.0 / public domain |

Verify each license/model card yourself before publishing; update this table if you swap components. Wikipedia text is CC BY-SA:
the app shows source titles for every answer, which is part of attribution.

## Performance tuning

* **Threads**: `AppConfig.THREADS` (default 4). More than the number of big cores usually hurts.
* **CPU features**: `-PcpuArch=...` (see `gradle.properties`). Wrong flag for your CPU = crash on start (SIGILL); the default is safe.
* **MoE speed is storage-bound**: after a warm-up, hot experts stay in page cache. Free RAM = faster. Close other apps for benchmarks.
* **Use the 4B tier for snappy lookups**, the 30B-A3B tier for "deep" answers. Both are just files in `models/`; switch with the chips in the UI.

## Known limitations (v0.1)

* Retrieval is keyword/BM25 only. Questions phrased with no shared vocabulary ("that thing where ants follow each other in a circle") rely on the LLM planner to translate them.
* Single-pass plan -> retrieve -> answer; no iterative multi-hop loop yet.
* Plain-text rendering (no markdown), no chat history in the prompt (each question is independent).
* Assets load either via `adb` or the in-app importer (system file picker); the importer copies files, so you temporarily hold two copies.

## Roadmap (highest value first)

1. Dense retrieval: small embedding model (e.g. a 100M-class GGUF) + vector index, fused with BM25.
2. Iterative loop: after retrieval, let the LLM request follow-up searches (real multi-hop).
3. Speculative decoding with a tiny draft model for the MoE tier.
4. More corpora: Wikivoyage, Wikibooks, StackExchange, WikiHow-style first-aid/travel guides (`--source jsonl`).
5. On-device eval harness that logs accuracy + tok/s.
