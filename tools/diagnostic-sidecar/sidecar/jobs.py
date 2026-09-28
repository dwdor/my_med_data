"""Thread-safe incident/job tracking, persisted to disk as JSON.

In-memory dict is the source of truth for a running process; the on-disk
copy exists so `GET /v1/incidents/<id>` survives a restart and so a human
can inspect `data_dir/incidents/<id>/status.json` directly while debugging.
"""
from __future__ import annotations

import json
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path


class Status:
    QUEUED = "queued"
    EXTRACTING = "extracting"
    ANALYZING = "analyzing"
    DONE = "done"
    FAILED = "failed"


@dataclass
class Incident:
    incident_id: str
    status: str
    created_at: float
    metadata: dict
    updated_at: float = field(default_factory=time.time)
    error: str | None = None
    resolved_ref: str | None = None
    report: str | None = None


class JobStore:
    def __init__(self, data_dir: Path):
        self.data_dir = data_dir
        self._lock = threading.Lock()
        self._incidents: dict[str, Incident] = {}

    def incident_dir(self, incident_id: str) -> Path:
        return self.data_dir / "incidents" / incident_id

    def create(self, incident_id: str, metadata: dict) -> Incident:
        incident = Incident(
            incident_id=incident_id,
            status=Status.QUEUED,
            created_at=time.time(),
            metadata=metadata,
        )
        with self._lock:
            self._incidents[incident_id] = incident
        self._persist(incident)
        return incident

    def update(self, incident_id: str, **fields) -> Incident:
        with self._lock:
            incident = self._incidents[incident_id]
            for key, value in fields.items():
                setattr(incident, key, value)
            incident.updated_at = time.time()
        self._persist(incident)
        return incident

    def get(self, incident_id: str) -> Incident | None:
        with self._lock:
            incident = self._incidents.get(incident_id)
        if incident is not None:
            return incident
        return self._load(incident_id)

    def _persist(self, incident: Incident) -> None:
        path = self.incident_dir(incident.incident_id)
        path.mkdir(parents=True, exist_ok=True)
        (path / "status.json").write_text(json.dumps(asdict(incident), indent=2, default=str))

    def _load(self, incident_id: str) -> Incident | None:
        status_file = self.incident_dir(incident_id) / "status.json"
        if not status_file.exists():
            return None
        data = json.loads(status_file.read_text())
        return Incident(**data)

    def reap_expired(self, ttl_seconds: int) -> None:
        import shutil

        now = time.time()
        incidents_root = self.data_dir / "incidents"
        if not incidents_root.exists():
            return
        for child in incidents_root.iterdir():
            status_file = child / "status.json"
            if not status_file.exists():
                continue
            try:
                data = json.loads(status_file.read_text())
            except (OSError, json.JSONDecodeError):
                continue
            if now - data.get("updated_at", now) > ttl_seconds:
                shutil.rmtree(child, ignore_errors=True)
                with self._lock:
                    self._incidents.pop(child.name, None)
