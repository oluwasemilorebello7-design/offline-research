# Demo script + post template

Record the screen with **airplane mode visibly on** and the app's "No INTERNET permission" badge in frame.
Pick queries a 1B model fails on - they need retrieval, comparison or multi-step reasoning:

1. *Compare the Roman Republic and the Athenian democracy: how did each choose its leaders?*  (two entities, needs facts on both)
2. *Why did the Bronze Age collapse happen? Give competing theories.*  (synthesis of several hypotheses)
3. *How does CRISPR differ from older gene-editing tools like TALENs?*  (technical comparison)
4. *I'm hiking above 4000 m tomorrow. What is altitude sickness and how do I prevent it?*  (practical travel use case)
5. *Which is older, the Great Wall's Ming-era construction or the Taj Mahal, and by how many years?*  (multi-hop + arithmetic; check the answer yourself)

Also show: the Sources panel (verifiable citations), the tok/s line, and `adb shell du -sh` for total size.

## X / Farcaster post template
> Offline AI research app for Android - no INTERNET permission, no Play Services. Local LLM (llama.cpp, mmap'd <MODEL>) + offline Wikipedia (SQLite FTS5).
> It plans searches -> retrieves -> writes cited answers, so a small model can compare, explain and synthesize.
> <X> tok/s on <DEVICE>, <SIZE> GB total. Demo in airplane mode 👇  Repo: <GITHUB_URL>
> Approach: <2-3 sentences>. Built for the community bounty inspired by @VitalikButerin.
