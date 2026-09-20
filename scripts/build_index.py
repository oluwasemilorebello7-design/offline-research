#!/usr/bin/env python3
"""
Build the offline knowledge base (one SQLite file with an FTS5/BM25 index) that the Android app searches.

Sources
  --source parquet   Wikipedia parquet shards, e.g. HF dataset `wikimedia/wikipedia` (config 20231101.en).
                     Needs `pip install pyarrow`. Columns used: title, text.
  --source jsonl     Any corpus as JSON lines: {"title": "...", "text": "..."}  (Wikivoyage, Wikibooks,
                     StackExchange, medical/first-aid guides, your own notes ...). No extra deps.

Example
  huggingface-cli download wikimedia/wikipedia --repo-type dataset \
      --include "20231101.en/*" --local-dir data/wiki
  python scripts/build_index.py --source parquet --input "data/wiki/20231101.en/*.parquet" \
      --out data/knowledge.sqlite --name "Wikipedia EN (2023-11-01)" --min-chars 2500 --max-chunks 6

Size control: the output is roughly (kept chunks) x ~1.3 KB x ~1.5 (index). The script prints the running
size; use --min-chars / --max-chunks / --max-articles to stay well under your storage budget
(model + this file must total < 50 GB).
"""
import argparse, glob, json, os, re, sqlite3, sys, time


def iter_parquet(paths):
    import pyarrow.parquet as pq  # lazy: only needed for parquet input
    for p in sorted(paths):
        pf = pq.ParquetFile(p)
        for batch in pf.iter_batches(batch_size=512, columns=["title", "text"]):
            d = batch.to_pydict()
            yield from zip(d["title"], d["text"])


def iter_jsonl(paths):
    for p in sorted(paths):
        with open(p, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    o = json.loads(line)
                    yield o["title"], o["text"]


def split_long(para, target):
    out = []
    while len(para) > int(target * 1.5):
        cut = para.rfind(". ", 0, target)
        cut = cut + 1 if cut > target // 2 else para.rfind(" ", 0, target)
        if cut <= 0:
            cut = target
        out.append(para[:cut].strip())
        para = para[cut:].strip()
    if para:
        out.append(para)
    return out


def chunk_article(text, target=1200, max_chunks=8):
    pieces = []
    for para in re.split(r"\n+", text):
        para = para.strip()
        if para:
            pieces.extend(split_long(para, target))
    chunks, cur, n = [], [], 0
    for p in pieces:
        if cur and n + len(p) > target:
            chunks.append("\n".join(cur))
            cur, n = [], 0
            if len(chunks) >= max_chunks:
                return chunks
        cur.append(p)
        n += len(p) + 1
    if cur and len(chunks) < max_chunks:
        chunks.append("\n".join(cur))
    return chunks


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", choices=["parquet", "jsonl"], required=True)
    ap.add_argument("--input", required=True, nargs="+", help="file(s) or glob(s)")
    ap.add_argument("--out", default="data/knowledge.sqlite")
    ap.add_argument("--name", default="knowledge", help="human-readable name shown in the app")
    ap.add_argument("--min-chars", type=int, default=800, help="skip articles shorter than this (proxy for notability)")
    ap.add_argument("--max-chunks", type=int, default=8, help="max chunks kept per article (lead + first sections)")
    ap.add_argument("--chunk-chars", type=int, default=1200)
    ap.add_argument("--max-articles", type=int, default=0, help="stop after N kept articles (0 = no limit)")
    ap.add_argument("--skip-lists", action="store_true", help="drop 'List of ...' / disambiguation articles")
    a = ap.parse_args()

    paths = [p for pat in a.input for p in glob.glob(pat)] or sys.exit("no input files matched")
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    if os.path.exists(a.out):
        os.remove(a.out)

    db = sqlite3.connect(a.out)
    db.executescript("""
        PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA page_size=4096;
        CREATE TABLE chunks(id INTEGER PRIMARY KEY, title TEXT NOT NULL, text TEXT NOT NULL);
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
    """)
    it = iter_parquet(paths) if a.source == "parquet" else iter_jsonl(paths)

    t0, kept, chunks_n, seen, buf = time.time(), 0, 0, 0, []
    for title, text in it:
        seen += 1
        if not title or not text or len(text) < a.min_chars:
            continue
        if a.skip_lists and (title.startswith("List of") or "(disambiguation)" in title):
            continue
        for c in chunk_article(text, a.chunk_chars, a.max_chunks):
            buf.append((title, c))
        kept += 1
        if len(buf) >= 20000:
            db.executemany("INSERT INTO chunks(title,text) VALUES (?,?)", buf)
            chunks_n += len(buf); buf.clear(); db.commit()
        if kept % 20000 == 0:
            print(f"[{time.time()-t0:6.0f}s] seen {seen:,} kept {kept:,} chunks {chunks_n:,} "
                  f"size {os.path.getsize(a.out)/1e9:.2f} GB", flush=True)
        if a.max_articles and kept >= a.max_articles:
            break
    if buf:
        db.executemany("INSERT INTO chunks(title,text) VALUES (?,?)", buf)
        chunks_n += len(buf); db.commit()

    print("building FTS5 index (this is the slow part)...", flush=True)
    db.execute("""CREATE VIRTUAL TABLE chunks_fts USING fts5(
        title, text, content='chunks', content_rowid='id',
        tokenize='porter unicode61 remove_diacritics 2')""")
    db.execute("INSERT INTO chunks_fts(rowid,title,text) SELECT id,title,text FROM chunks")
    db.execute("INSERT INTO chunks_fts(chunks_fts) VALUES('optimize')")
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("name", a.name), ("chunks", f"{chunks_n:,}"), ("articles", f"{kept:,}"),
        ("built", time.strftime("%Y-%m-%d")), ("min_chars", str(a.min_chars)),
        ("max_chunks", str(a.max_chunks)), ("chunk_chars", str(a.chunk_chars)),
    ])
    db.commit()
    db.execute("PRAGMA journal_mode=DELETE")
    db.close()
    print(f"done: {kept:,} articles, {chunks_n:,} chunks, {os.path.getsize(a.out)/1e9:.2f} GB -> {a.out}")


if __name__ == "__main__":
    main()
