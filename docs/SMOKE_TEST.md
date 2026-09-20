# Smoke test without downloading Wikipedia

```bash
cat > /tmp/s.jsonl <<'JSON'
{"title":"Altitude sickness","text":"Altitude sickness is caused by rapid exposure to low oxygen at high elevation, above roughly 2500 metres.\nPrevention: ascend gradually, sleep low, stay hydrated; acetazolamide can reduce symptoms. Descend if symptoms worsen. Severe forms are high altitude pulmonary edema (HAPE) and cerebral edema (HACE), which are emergencies."}
{"title":"Roman Republic","text":"The Roman Republic was the era of ancient Rome between the overthrow of the monarchy and the Empire.\nMagistrates called consuls were elected annually by citizen assemblies, and the Senate advised them."}
{"title":"Athenian democracy","text":"Athenian democracy developed around Athens in the fifth century BC.\nMany officials were chosen by lot rather than election, and the Assembly voted directly on laws."}
JSON
python scripts/build_index.py --source jsonl --input /tmp/s.jsonl --out data/knowledge.sqlite --name "Smoke test" --min-chars 100
```
Push it with `scripts/push_assets.sh` and ask: *"Compare how the Roman Republic and Athenian democracy chose officials."* You should see both
articles under "Sources" and a cited answer. (The KB build script and the FTS5 query logic were tested on a PC; the Android side was not run when this repo was generated - see README status.)
