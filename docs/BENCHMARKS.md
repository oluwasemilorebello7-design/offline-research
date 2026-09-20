# Benchmarks (fill in from a real device before submitting)

Device: ______ (SoC, RAM, storage type)   OS: ______ (Android / GrapheneOS version)   llama.cpp tag: ______   cpuArch: ______

| Model | File size | KB size | Prefill tok/s | Decode tok/s | Time to answer (typical query) | Peak RSS |
|---|---|---|---|---|---|---|
| Qwen3-4B-Instruct-2507 Q4_K_M | | | | | | |
| Qwen3-30B-A3B-Instruct-2507 Q4_K_M | | | | | | |

Method: airplane mode on, fresh app start, 3 runs of each demo query (docs/DEMO.md), report the median. The app prints
prefill/decode tok/s under every answer. Peak RSS from `adb shell dumpsys meminfo org.offlineresearch.app`.
Total footprint: `adb shell du -sh /sdcard/Android/data/org.offlineresearch.app/files`.
