"""Helpers for the JSON document model: plaintext extraction + title derivation."""


def _spans_text(spans) -> str:
    return "".join(s.get("text", "") for s in spans or [])


def extract_plaintext(doc: dict) -> str:
    """Flatten a {blocks: [...]} document to searchable plain text."""
    parts: list[str] = []
    for block in doc.get("blocks", []) or []:
        btype = block.get("type")
        if btype in ("paragraph", "heading", "quote", "code"):
            parts.append(_spans_text(block.get("spans")))
        elif btype in ("bullet_list", "ordered_list"):
            for item in block.get("items", []) or []:
                parts.append(_spans_text(item.get("spans")))
        elif btype == "image":
            alt = block.get("alt")
            parts.append(f"[image: {alt}]" if alt else "[image]")
        elif btype == "divider":
            parts.append("---")
    return "\n".join(p for p in parts if p.strip()).strip()


def derive_title(doc: dict) -> str:
    """Title = first line of text, capped at 80 chars; 'Untitled' if empty."""
    text = extract_plaintext(doc)
    first = text.split("\n")[0].strip() if text else ""
    return first[:80] if first else "Untitled"


# ---------------------------------------------------------------- validation

BLOCK_TYPES = {"paragraph", "heading", "bullet_list", "ordered_list",
               "quote", "code", "image", "divider"}
MARKS = {"bold", "italic", "underline", "strikethrough", "code", "link"}
ALIGNS = {"left", "center", "right"}


def _validate_spans(spans, where: str) -> str | None:
    if not isinstance(spans, list):
        return f"{where}: spans must be a list"
    for j, s in enumerate(spans):
        if not isinstance(s, dict) or not isinstance(s.get("text"), str):
            return f"{where}.spans[{j}]: need {{\"text\": str}}"
        for m in s.get("marks", []) or []:
            # marks are plain strings, except link: {"name": "link", "href": "..."}
            name = m.get("name") if isinstance(m, dict) else m
            if name not in MARKS:
                return f"{where}.spans[{j}]: unknown mark {name!r}"
            if name == "link":
                href = m.get("href") if isinstance(m, dict) else None
                if not href:
                    return f"{where}.spans[{j}]: link mark needs href"
    return None


def _validate_block(block, where: str) -> str | None:
    if not isinstance(block, dict):
        return f"{where}: block must be an object"
    btype = block.get("type")
    if btype not in BLOCK_TYPES:
        return f"{where}: unknown block type {btype!r}"
    if block.get("align", "left") not in ALIGNS:
        return f"{where}: align must be left/center/right"
    if btype in ("paragraph", "heading", "quote", "code"):
        if btype == "heading" and block.get("level", 1) not in (1, 2, 3):
            return f"{where}: heading level must be 1-3"
        return _validate_spans(block.get("spans", []), where)
    if btype in ("bullet_list", "ordered_list"):
        items = block.get("items")
        if not isinstance(items, list) or not items:
            return f"{where}: list needs a non-empty items array"
        for k, item in enumerate(items):
            if not isinstance(item, dict):
                return f"{where}.items[{k}]: must be an object"
            err = _validate_spans(item.get("spans", []), f"{where}.items[{k}]")
            if err:
                return err
        return None
    if btype == "image":
        if not block.get("attachment_id"):
            return f"{where}: image needs attachment_id"
        return None
    return None  # divider


def validate_doc(doc) -> str | None:
    """Return an error message if the document is invalid, else None."""
    if not isinstance(doc, dict) or not isinstance(doc.get("blocks"), list):
        return 'body must be an object like {"blocks": [...]}'
    for i, block in enumerate(doc["blocks"]):
        err = _validate_block(block, f"blocks[{i}]")
        if err:
            return err
    return None
