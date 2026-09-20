# Free, laptop-free path (everything from your phone)

Everything below is free: GitHub (public repo -> unlimited Actions build minutes), Google Colab (free tier), Hugging Face downloads.
The only real cost is **mobile data** (model ~2.5 GB, knowledge base from ~0.3 GB upward) and **phone storage**.

**Honest expectation:** this code has never been compiled. The first GitHub build will probably fail once or twice (see step 4).
That is normal, and the loop below fixes it without a laptop. Nothing counts as "working" until the app answers a question on your phone in airplane mode.

## 0. Accounts (free)
GitHub account and Google account. Use your phone browser in **Desktop site** mode for GitHub settings and Colab.

## 1. Create the repo and a token
1. github.com -> **New repository** -> name `offline-research`, **Public**, do NOT add a README -> Create.
2. github.com -> Settings -> Developer settings -> Personal access tokens -> **Fine-grained tokens** -> Generate.
   Repository access: *Only select repositories* -> `offline-research`. Permissions -> **Contents: Read and write**. Copy the token somewhere private for the next step.

## 2. Push the project to GitHub using Colab
1. Download `offline-research.zip` and `push_to_github.ipynb` (both provided) to your phone.
2. colab.research.google.com -> **Upload** -> pick `push_to_github.ipynb`.
3. Edit `GITHUB_USER` / `REPO` in the first cell, then run the cells in order (Runtime -> Run all works too). When asked, upload the zip and paste your token into the hidden prompt.
4. Refresh your GitHub repo page: the files should be there.

## 3. GitHub builds the APK for you
1. Repo -> **Actions** tab -> *build-apk* runs automatically after the push (about 10-25 min the first time).
2. It downloads llama.cpp itself (latest release, or a tag you choose under *Run workflow*). The tag used is saved beside the APK.

## 4. If the build fails (expected at first)
1. Actions -> click the failed run -> click the red step -> scroll to the **first** `error:` line -> copy ~20 lines around it.
2. Send them to me. I reply with the corrected file(s).
3. On GitHub, open the file -> pencil icon -> replace the contents -> **Commit changes**. A new build starts automatically.
4. If the errors mention a `llama_...` function in `llama_jni.cpp`, you can also try an older llama.cpp: Actions -> *build-apk* -> **Run workflow** -> type an older release tag from github.com/ggml-org/llama.cpp/releases.

## 5. Install the APK
1. Finished run -> **Artifacts** -> `offline-research-debug-apk` (a zip). Download it, extract it with your Files app, tap `app-debug.apk`.
2. Android will ask to allow installs from that app (browser/Files): allow it. (GrapheneOS: Settings -> Apps -> that app -> *Install unknown apps*.)

## 6. Get a model onto the phone (in the phone browser)
Open the model's **Files and versions** page on huggingface.co (search `unsloth Qwen3-4B-Instruct-2507-GGUF`) and download the `Q4_K_M` `.gguf` file (~2.5 GB). Use Wi-Fi if you can.
Skip the 30B (~18 GB) model on this route: too heavy to download on a phone. Use the 4B tier for the first working demo.

## 7. Build the knowledge base in Colab
1. Upload `build_knowledge_base.ipynb` to Colab, edit `GITHUB_USER`, keep `CONFIG = '20231101.simple'` for the first run.
2. Run all cells; allow Google Drive access when asked. It saves `knowledge.sqlite` to your Drive.
3. Later, for a bigger KB: `CONFIG = '20231101.en'`, `MIN_CHARS = 2500`, and `MAX_ARTICLES = 300000` (raise/lower to fit your phone storage and data plan). Free Colab may disconnect on very long jobs; a smaller `MAX_ARTICLES` is the fix.
4. On the phone, open Google Drive -> `knowledge.sqlite` -> Download.

## 8. Import into the app and test
1. Open *Offline Research* -> **Import model (.gguf)** -> pick the downloaded model. Then **Import knowledge base (.sqlite)**. Progress shows on screen.
2. Delete the originals from Downloads afterwards (the app keeps its own copy, so you'd otherwise hold two).
3. Turn on **airplane mode**, ask: *Compare the Roman Republic and the Athenian democracy: how did each choose its leaders?*
   You should see planning -> searching -> a streamed answer with [1][2] -> a tok/s line -> a Sources list.

## 9. Publish
Use `docs/DEMO.md` (record with your phone's built-in screen recorder), attach the APK to a GitHub **Release**, fill the placeholders in `README.md`, post on X/Farcaster, claim on poidh. Without a laptop you can't run `adb`, so read speed from the app's tok/s line and storage from Settings -> Storage; say exactly that in `docs/BENCHMARKS.md`.

## Limits of this route
* Uncompiled code means at least one fix round-trip; budget a few days if you rely on replies.
* Free Colab has time/disk limits, so the knowledge base will be partial English Wikipedia unless a run happens to fit.
* GitHub Actions is free and unlimited only for **public** repos (which the bounty requires anyway).
