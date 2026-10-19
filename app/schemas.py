"""Request bodies. Note bodies are {blocks: [...]} dicts, validated server-side."""
from pydantic import BaseModel


class NoteCreate(BaseModel):
    title: str | None = None
    body: dict
    folder_id: str | None = None
    tags: list[str] = []
    reminder_at: str | None = None  # ISO 8601 UTC; None = no reminder
    pinned: bool = False
    archived: bool = False


class NoteUpdate(BaseModel):
    """Full body replace. Metadata fields are optional — omitted means keep
    the current value (never silently wiped)."""
    title: str | None = None
    body: dict
    base_revision: int
    folder_id: str | None = None
    tags: list[str] | None = None
    favorite: bool | None = None
    reminder_at: str | None = None
    pinned: bool | None = None
    archived: bool | None = None


class NotePatch(BaseModel):
    title: str | None = None
    folder_id: str | None = None  # explicit null clears the folder
    tags: list[str] | None = None
    favorite: bool | None = None
    base_revision: int | None = None  # 409-checked when provided
    reminder_at: str | None = None  # explicit null clears the reminder
    pinned: bool | None = None
    archived: bool | None = None


class FolderCreate(BaseModel):
    name: str
    parent_id: str | None = None


class FolderUpdate(BaseModel):
    name: str
