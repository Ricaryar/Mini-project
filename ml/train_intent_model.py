import json
import math
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path


def tokenize(text: str):
    text = text.lower().strip()
    tokens = []
    latin = []
    for ch in text:
        if "\u4e00" <= ch <= "\u9fff":
            if latin:
                tokens.append("".join(latin))
                latin = []
            tokens.append(ch)
            continue
        if ("a" <= ch <= "z") or ("0" <= ch <= "9"):
            latin.append(ch)
            continue
        if latin:
            tokens.append("".join(latin))
            latin = []
    if latin:
        tokens.append("".join(latin))
    return tokens


def train(dataset_path: Path):
    docs = []
    with dataset_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            intent = obj["intent"]
            tokens = tokenize(obj["text"])
            docs.append((intent, tokens))

    label_doc_count = Counter()
    label_token_count = Counter()
    label_token_freq = defaultdict(Counter)
    vocab = set()

    for label, tokens in docs:
        label_doc_count[label] += 1
        label_token_count[label] += len(tokens)
        for t in tokens:
            label_token_freq[label][t] += 1
            vocab.add(t)

    labels = sorted(label_doc_count.keys())
    total_docs = len(docs)
    vocab_size = len(vocab)
    alpha = 1.0

    log_prior = {}
    unknown_log_prob = {}
    log_likelihood = {}

    for label in labels:
        log_prior[label] = math.log(label_doc_count[label] / total_docs)
        denom = label_token_count[label] + alpha * vocab_size
        unknown_log_prob[label] = math.log(alpha / denom)
        token_probs = {}
        for t in vocab:
            token_probs[t] = math.log((label_token_freq[label][t] + alpha) / denom)
        log_likelihood[label] = token_probs

    model = {
        "labels": labels,
        "logPrior": log_prior,
        "unknownLogProb": unknown_log_prob,
        "logLikelihood": log_likelihood,
        "meta": {
            "docs": total_docs,
            "vocabSize": vocab_size,
            "tokenizer": "cjk-char + latin-word",
        },
    }
    return model


def main():
    base = Path(__file__).resolve().parent
    dataset = base / "intent_dataset.jsonl"
    out = base.parent / "app" / "src" / "main" / "assets" / "intent_model.json"

    model = train(dataset)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(model, ensure_ascii=False), encoding="utf-8")
    print(f"Model written to: {out}")
    print(f"Labels: {', '.join(model['labels'])}")
    print(f"Docs: {model['meta']['docs']}, Vocab: {model['meta']['vocabSize']}")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"Training failed: {e}", file=sys.stderr)
        raise
