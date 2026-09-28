# diagnostic-sidecar

A small HTTP service that lives next to a local git repository and turns an
uploaded incident bundle (metadata + a zip of logs/crash artifacts) into an
AI-generated root-cause analysis, correlated against the actual source code
that was running.

Zero third-party dependencies — Python 3.9+ standard library only. That's a
deliberate design choice for something meant to be dropped next to an
arbitrary repo and just run.

## Why it exists

When a monitoring/crash-reporting system (Sentry, Crashlytics, a custom
alert, whatever) fires, a human still has to open the repo, find the
matching commit, and manually cross-reference the stack trace against the
code. This sidecar automates that first pass: point it at your repo, POST it
the incident, get back a Markdown report with ranked root-cause hypotheses
and `file:line` evidence.

It is deliberately *not* a code-fixing agent. The harness runs in a
read/investigate posture against a disposable checkout — it produces a
report, it does not open a PR.

## Architecture

```
                 POST /v1/incidents
                 (metadata JSON + zip blob)
                         |
                         v
   +---------------------------------------------+
   |  HTTP server (stdlib, ThreadingHTTPServer)   |
   |  - auth check, size limits                   |
   |  - multipart parse                            |
   +---------------------------------------------+
                         |
              write upload.zip to disk,
              respond 202 immediately,
              hand off to worker pool
                         |
                         v
   +---------------------------------------------+
   |  Pipeline (background thread)                |
   |  1. safe_extract_zip()  -- zip-slip/bomb      |
   |     guards, extract to incident's own tmp dir |
   |  2. git worktree add --detach <ref>           |
   |     -- disposable, isolated checkout, never   |
   |        the live working tree                  |
   |  3. build_prompt() -- incident metadata +      |
   |     extracted evidence + checkout path         |
   |  4. harness.run(prompt) -- agentic analysis    |
   |  5. git worktree remove -- always, even on     |
   |     failure                                    |
   |  6. persist report.md, update status           |
   +---------------------------------------------+
                         |
                         v
        GET /v1/incidents/{id}  -> status + report
```

Analysis is asynchronous because agentic root-cause analysis can take
minutes. The client gets a 202 with an incident id immediately and polls.

## API

### `POST /v1/incidents`

`multipart/form-data` with exactly two parts:

- `metadata` — a JSON object. No fixed schema beyond being an object; fields
  the harness specifically makes use of:
  - `incident_id` (optional) — client-supplied id; a uuid4 is generated if
    absent.
  - `git_ref` (optional) — the commit/branch/tag that was running when the
    incident occurred. Falls back to `HEAD` of the companion repo if absent
    or unresolvable.
  - `stack_trace` (optional) — inlined verbatim into the analysis prompt.
  - anything else (title, severity, tags, source system, timestamps, device
    info, ...) — passed through to the harness as context.
- `bundle` — the zip blob (crash logs, heap dumps, device state, screenshots
  metadata, etc).

Response `202 Accepted`:

```json
{
  "incident_id": "a1b2c3...",
  "status": "queued",
  "report_url": "/v1/incidents/a1b2c3..."
}
```

Example:

```sh
curl -X POST http://127.0.0.1:8787/v1/incidents \
  -F metadata='{"title":"NPE on checkout","git_ref":"a1b2c3d","stack_trace":"..."}' \
  -F bundle=@incident-bundle.zip
```

### `GET /v1/incidents/{id}`

```json
{
  "incident_id": "a1b2c3...",
  "status": "queued | extracting | analyzing | done | failed",
  "created_at": 1732999999.0,
  "updated_at": 1733000123.4,
  "resolved_ref": "a1b2c3d4e5...",
  "error": null,
  "report": "# Root-cause analysis\n\n..."
}
```

### `GET /healthz`

Liveness probe, `{"status": "ok"}`.

## Running it

```sh
python3 main.py --repo /path/to/your/git/repo --data-dir ./sidecar-data
```

Key flags: `--host`/`--port` (defaults to `127.0.0.1:8787` — loopback only
by default, deliberately), `--workers` (concurrent analysis jobs),
`--harness-timeout`, `--harness-cmd` (override the agent invocation),
`--max-request-mb`.

Set `SIDECAR_AUTH_TOKEN` in the environment to require
`Authorization: Bearer <token>` on every request. There is no auth by
default because the intended deployment is loopback-only, next to a
developer's or CI runner's checkout — set the token (and/or put this behind
your own reverse proxy) before exposing it any more broadly than that.

## The agentic harness

`sidecar/harness.py` defines a tiny interface (`AgenticHarness.run(prompt,
cwd, timeout) -> HarnessResult`) so the actual agent is swappable:

- **`ClaudeCodeHarness`** (default) shells out to the `claude` CLI in
  non-interactive print mode (`claude -p --output-format text
  --permission-mode plan`). The prompt is always sent on **stdin**, never
  interpolated into argv or a shell string, so nothing in the uploaded
  bundle or metadata can inject extra flags or escape into a shell. `cwd`
  is the disposable worktree, and `--permission-mode plan` keeps the agent
  investigating rather than editing.
- **`StubHarness`** is a deterministic no-op used automatically when the
  configured binary isn't found on `PATH` (logged loudly), and explicitly
  in tests so the suite never shells out to a real model.

Swap in a different agent (a custom LangGraph runner, a different CLI, a
hosted API call) by implementing `AgenticHarness` and passing an instance to
`run_server(config, harness=...)`.

## Security model

This process accepts file uploads (optionally unauthenticated, see above)
and then runs `git` and an AI agent with source-code access. Every
extraction and isolation step exists because of that:

- **Zip-slip**: every archive member's resolved path is checked against the
  destination directory (`extract.py`); `../../etc/passwd`-style and
  absolute-path entries are rejected before anything is written.
- **Symlink entries**: rejected outright — a zip can declare a member as a
  symlink pointing anywhere on the filesystem.
- **Zip bombs**: entry count, per-file size, and total uncompressed size are
  all capped (`--max-request-mb` bounds the upload itself; the extraction
  caps bound what it expands to).
- **No live working tree access**: the harness only ever sees a disposable
  `git worktree` checked out at the incident's reported ref (falling back to
  `HEAD`), created fresh per incident and force-removed in a `finally`
  block — including on extraction failure, harness crash, or timeout. The
  developer's actual working directory (possibly holding uncommitted
  changes) is never touched.
- **Command injection**: the harness command is a fixed argv list from
  config, never built by string-formatting untrusted input; the prompt
  (which does contain untrusted content) goes over stdin only.
- **Resource limits**: `Content-Length` is checked before the body is read
  (early 413 for oversized requests); harness runs are subprocess-timeout
  bounded.
- **Retention**: incident directories (upload, extracted bundle, prompt,
  report) are reaped after `incident_ttl_seconds` (default 7 days) by a
  background thread — this data can include sensitive log contents and
  shouldn't accumulate forever.

Things intentionally left to the deployer: TLS termination (put a reverse
proxy in front if this leaves loopback), and rate limiting.

## Testing

```sh
python3 -m unittest tests.test_sidecar -v
```

Covers: multipart parsing (including a binary-safety regression test for
payloads that end in bytes that look like `\r\n`), zip-slip/absolute-path/
zip-bomb rejection, and a full end-to-end run against a real temporary git
repo (incident submitted over real HTTP, worktree created and cleaned up,
status transitions through to `done`, and that the repo's own `git
worktree list` is left clean afterward) — all using the injected
`StubHarness` so tests never shell out to a real agent.
