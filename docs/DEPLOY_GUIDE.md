# Deploy Guide: from a fresh laptop to a working offline app

Written for Windows (most common). Mac/Linux differences are noted. Follow the phases in order and **do not skip Phase 7**
(the small test) - it proves the whole pipeline before you spend hours on big downloads.

## Do I only need the laptop once?

**Mostly, but plan for two sessions, not one.**

* The phone works fully on its own once the app, model and knowledge base are installed. No laptop, no internet, ever again.
* You need the laptop for: (1) the first setup, (2) fixing build errors (very likely, because this code was never compiled
  before), (3) any later change: new code, different model, bigger/smaller knowledge base.
* Realistic plan: **Session 1** = build + smoke test (a few hours, mostly waiting). **Session 2** = big Wikipedia index + big model + benchmarks + demo.
* Laptop-free options exist (GitHub builds the APK for you; Google Colab can build the index), but getting multi-GB files *into* the app
  is now possible too: the app has an in-app importer, see `docs/LAPTOP_FREE.md`.

---

## Phase 0 - What you need

| Item | Minimum | Notes |
|---|---|---|
| Laptop | 8 GB RAM (16 GB better), SSD, 100 GB free disk, 64-bit Windows 10/11 | Android Studio + SDK/NDK ~15 GB, Wikipedia download ~20 GB, index output several GB, models 2.5-19 GB |
| Phone | arm64 Android 8+ (GrapheneOS OK), 8 GB+ RAM ideal | Free space: ~10 GB for the 4B tier, ~35 GB for the 30B-A3B tier |
| USB cable | must be a **data** cable | many cheap cables are charge-only |
| Internet for the laptop | ~5 GB for setup + ~3 GB model; +~20 GB for full Wikipedia | Do the big downloads on unmetered Wi-Fi; start with the small test set (Phase 7) |
| Accounts | GitHub (free) | Hugging Face account not required for these public files |

## Phase 1 - Install tools on the laptop (~1 hour, mostly downloads)

1. **Git for Windows** (git-scm.com). Accept defaults. It also gives you **Git Bash**, which you'll use for the `.sh` script.
2. **Python 3.11+** (python.org). Tick **"Add python.exe to PATH"** in the installer.
3. **Android Studio** (developer.android.com/studio). Default install. It bundles JDK 17.
4. Open Android Studio -> **More Actions -> SDK Manager**:
   * *SDK Platforms* tab: tick **Android 15 (API 35)**.
   * *SDK Tools* tab: tick **NDK (Side by side)** (pick 27.x), **CMake** (pick **3.22.1** via "Show Package Details"), **Android SDK Platform-Tools**. Click Apply.
5. Add `adb` to PATH: it lives in `C:\Users\<you>\AppData\Local\Android\Sdk\platform-tools`. Add that folder to the Windows PATH
   (Start -> "Edit the system environment variables" -> Environment Variables -> Path -> New). Reopen terminals, then run `adb version`.

## Phase 2 - Prepare the phone

1. Settings -> About phone -> tap **Build number** 7 times -> Developer options unlocked.
2. Developer options -> turn on **USB debugging**. (GrapheneOS: same path; keep the phone unlocked while connecting.)
3. Plug into the laptop. On the phone, tap **Allow** on the "Allow USB debugging?" prompt (tick "always allow").
4. In a terminal: `adb devices` -> must show your device as `device` (not `unauthorized` or empty).
   * Empty on Windows = missing driver. Install your phone maker's USB driver (Google USB Driver for Pixel; Samsung/Tecno/Infinix have their own),
     or use **Wireless debugging** (Developer options -> Wireless debugging -> "Pair device with pairing code" -> `adb pair <ip:port>` then `adb connect <ip:port>`).
5. Check the phone is compatible - run each and note the result:
   ```
   adb shell getprop ro.product.cpu.abi         # must print arm64-v8a
   adb shell "grep -m1 MemTotal /proc/meminfo"  # RAM
   adb shell df -h /sdcard                      # free storage
   adb shell "grep -m1 Features /proc/cpuinfo"  # look for asimddp (required) and i8mm (optional, faster)
   ```
   If `asimddp` is missing, tell me before building. If `i8mm` is present you may use `-PcpuArch=armv8.6-a+i8mm+dotprod` later for faster prompt processing.

## Phase 3 - Put the code on GitHub

1. Create a **public** repo on github.com (e.g. `offline-research`), empty (no README).
2. Unzip `offline-research.zip`, open **Git Bash** inside the folder, then:
   ```bash
   git init && git branch -M main
   git submodule add https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
   ```
3. Pin llama.cpp to a release tag: open github.com/ggml-org/llama.cpp/releases, copy the newest tag (looks like `b1234`), then:
   ```bash
   git -C app/src/main/cpp/llama.cpp checkout <that-tag>
   git add . && git commit -m "Initial MVP"
   git remote add origin https://github.com/<you>/offline-research.git
   git push -u origin main
   ```
4. Write the tag you used in `docs/BENCHMARKS.md` - the bounty requires reproducibility.

## Phase 4 - Build and install the app (first build: 10-30 min)

1. Android Studio -> **Open** -> select the `offline-research` folder. Let Gradle sync finish (bottom bar). If it asks to install missing SDK/NDK pieces, accept.
2. Select your phone in the device dropdown at the top, press the green **Run** button.
3. **If the build fails** (likely on the first try): open the **Build** tab, scroll to the **first** error, copy ~20 lines and send them to me. Common ones:

| Error text | Meaning / fix |
|---|---|
| `llama.h: No such file` / empty `llama.cpp` folder | submodule not fetched: `git submodule update --init --recursive` |
| `use of undeclared identifier llama_...` / `no matching function` in `llama_jni.cpp` | llama.cpp API renamed since this code was written. Send me the error; it's a small edit in that one file |
| `NDK not configured` / `CMake ... not found` | Phase 1 step 4: install NDK + CMake 3.22.1 |
| `Unresolved reference` in `KnowledgeBase.kt` | the androidx.sqlite API differs; send me the line |
| `Out of memory` / `GC overhead` | raise `org.gradle.jvmargs=-Xmx4g` to `-Xmx6g` in `gradle.properties` |
| unsupported `-march` flag | set `cpuArch=armv8.2-a+dotprod` in `gradle.properties` |

4. Success = the app opens on the phone and shows **"Almost there - add your offline assets"**. That screen is correct at this stage.

**No-Android-Studio alternative:** after pushing to GitHub, the included workflow builds an APK on GitHub's servers (Actions tab -> latest run ->
Artifacts -> download -> `adb install app-debug.apk`). Slower iteration but needs almost nothing on the laptop.

## Phase 5 - Get a model (do the small one first)

```bash
pip install -U "huggingface_hub[cli]"
# newer versions call the command `hf`; older ones `huggingface-cli`. Use whichever works.
hf download unsloth/Qwen3-4B-Instruct-2507-GGUF Qwen3-4B-Instruct-2507-Q4_K_M.gguf --local-dir data/models
```
About 2.5 GB. If the exact file name has changed, open the model page on huggingface.co and copy the Q4_K_M file name. If a download stops, rerun the same command; it resumes.

## Phase 6 - Build the knowledge base

**6a - Small test set (recommended first, ~minutes):** Simple English Wikipedia.
```bash
pip install pyarrow
hf download wikimedia/wikipedia --repo-type dataset --include "20231101.simple/*" --local-dir data/wiki
python scripts/build_index.py --source parquet --input "data/wiki/20231101.simple/*.parquet" \
    --out data/knowledge.sqlite --name "Simple Wikipedia" --min-chars 800 --max-chunks 6
```
(Check the dataset page on Hugging Face for the exact folder name if the include pattern matches nothing.)

**6b - Full English Wikipedia (Session 2, hours, keep the laptop plugged in):**
```bash
hf download wikimedia/wikipedia --repo-type dataset --include "20231101.en/*" --local-dir data/wiki
python scripts/build_index.py --source parquet --input "data/wiki/20231101.en/*.parquet" \
    --out data/knowledge.sqlite --name "Wikipedia EN 2023-11" --min-chars 2500 --max-chunks 6 --skip-lists
```
The script prints running size. Total on the phone (model + knowledge.sqlite) must stay under 50 GB; shrink with a bigger `--min-chars` or smaller `--max-chunks`.

## Phase 7 - Push to the phone and test (the moment of truth)

1. Make sure the app has been opened once (so Android creates its folder).
2. In **Git Bash**:
   ```bash
   scripts/push_assets.sh data/models/Qwen3-4B-Instruct-2507-Q4_K_M.gguf data/knowledge.sqlite
   ```
   (Manual equivalent: `adb push <file> /sdcard/Android/data/org.offlineresearch.app/files/models/` and `.../files/kb/knowledge.sqlite`.)
3. Open the app -> tap **"I've copied the files - reload"**. Header should show the model name and `KB: Simple Wikipedia · N chunks`.
4. **Turn on airplane mode.** Ask: *"Compare the Roman Republic and the Athenian democracy: how did each choose its leaders?"*
   You should see: "Planning searches…" -> "Searching…" -> streamed answer with [1][2] citations -> tok/s line -> **Sources** panel.
5. If that works, the MVP is proven. Everything after is scale and polish.

### Troubleshooting

| Symptom | Fix |
|---|---|
| App crashes on launch | `adb logcat -s OfflineLLM AndroidRuntime` right after launch; send me the last 30 lines |
| Crash with `SIGILL` | your CPU lacks the flag used: set `cpuArch=armv8.2-a+dotprod`, rebuild |
| "Could not load <model>" | file incomplete or unsupported: compare file size with the Hugging Face page; check logcat |
| Still "add your offline assets" after push | files not visible to the app. Confirm the path with `adb shell ls -l /sdcard/Android/data/org.offlineresearch.app/files/models`. If `adb push` was refused, tell me - the fix is an in-app importer |
| "KB failed to open" | file truncated or index not finished: rebuild, watch for the "done:" line |
| Very slow (<2 tok/s on the 4B model) | make sure you installed a build with native Release flags (default), close other apps, try `AppConfig.THREADS` = 3 or 5 |
| Answers ignore sources | check Sources panel is non-empty; if empty, the KB has no matching text (normal for the tiny test set) |

## Phase 8 - The big model (MoE tier) and benchmarks (Session 2)

1. Confirm free phone storage (`adb shell df -h /sdcard`) is > 25 GB.
2. ```bash
   hf download unsloth/Qwen3-30B-A3B-Instruct-2507-GGUF Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf --local-dir data/models
   scripts/push_assets.sh data/models/Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf
   ```
   (~18.6 GB; the push takes a while.) A second chip appears in the app; tap it to switch.
3. First questions will be slow (cold page cache) and speed improves as hot experts stay cached. Run each demo query 3 times, record the median
   tok/s into `docs/BENCHMARKS.md`. Get RAM with `adb shell dumpsys meminfo org.offlineresearch.app` and total size with
   `adb shell du -sh /sdcard/Android/data/org.offlineresearch.app/files`.
4. Whichever tier is the better speed/quality trade-off becomes your headline demo. Be honest in the post about which one you used.

## Phase 9 - Publish and claim

1. Make the APK downloadable: GitHub repo -> Releases -> Draft new release -> attach the APK from the Actions artifact (or `app/build/outputs/apk/debug/`). This helps the "runs within minutes" requirement.
2. Fill in the placeholders in README (repo URL, tag, benchmark numbers) and `docs/BENCHMARKS.md`. Verify every claim in the checklist is true.
3. Record the demo (`docs/DEMO.md`): airplane mode visible, the "no INTERNET permission" badge, 4-5 queries, Sources panel, tok/s.
4. Post on X or Farcaster with the repo link and a short explanation. Take a screenshot.
5. Claim on poidh with the screenshot, the post link and the repo link. **The repo must contain the working version at claim time**, so push your final fixes first.

## Quick command cheat-sheet

```bash
adb devices                                   # is the phone connected?
adb logcat -s OfflineLLM AndroidRuntime       # crash/debug logs
adb shell ls -l /sdcard/Android/data/org.offlineresearch.app/files/models
adb uninstall org.offlineresearch.app         # full reset (also deletes pushed files)
```
