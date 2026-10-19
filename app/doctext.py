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
