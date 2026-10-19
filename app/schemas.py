"""Request bodies. Note bodies are free-form {blocks: [...]} dicts (see spec)."""
from pydantic import BaseModel


class NoteCreate(BaseModel):
    title: str | None = None
    body: dict
    folder_id: str | None = None
    tags: list[str] = []


class NoteUpdate(BaseModel):
    """Full replace; requires the base revision for conflict detection."""
    title: str
    body: dict
    base_revision: int
    folder_id: str | None = None
    tags: list[str] = []
    favorite: bool = False


class NotePatch(BaseModel):
    title: str | None = None
    folder_id: str | None = None
    tags: list[str] | None = None
    favorite: bool | None = None


class FolderCreate(BaseModel):
    name: str
    parent_id: str | None = None
