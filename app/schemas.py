"""Request bodies. Note bodies are {blocks: [...]} dicts, validated server-side."""
from pydantic import BaseModel


class NoteCreate(BaseModel):
    title: str | None = None
    body: dict
    folder_id: str | None = None
    tags: list[str] = []


class NoteUpdate(BaseModel):
    """Full body replace. Metadata fields are optional — omitted means keep
    the current value (never silently wiped)."""
    title: str | None = None
    body: dict
    base_revision: int
    folder_id: str | None = None
    tags: list[str] | None = None
    favorite: bool | None = None


class NotePatch(BaseModel):
    title: str | None = None
    folder_id: str | None = None  # explicit null clears the folder
    tags: list[str] | None = None
    favorite: bool | None = None
    base_revision: int | None = None  # 409-checked when provided


class FolderCreate(BaseModel):
    name: str
    parent_id: str | None = None


class FolderUpdate(BaseModel):
    name: str
