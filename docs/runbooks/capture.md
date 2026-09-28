# Capture Runbook — Production Relay Traffic (Phase 1, §43.2-1.1)

**Security class: security-critical.** This procedure touches the production relay
(`wss://zcode.z.ai/ws`) and live pairing credentials. INV-15/INV-16 are absolute:
no real credential, QR link, conversation content, personal path, or provider secret
may enter the repository, CI, or any committed fixture.

## Preconditions

1. A disposable test environment: a synthetic workspace in the desktop (no personal
   repositories opened), synthetic task content only.
2. A freshly generated pairing QR from the desktop's *Mobile Remote Control* dialog,
   captured into an ephemeral file **outside the repository** (e.g. `$TMPDIR`), mode 600.
3. The spike client built: `gradle :spike:installDist`.
4. Raw capture output directory outside the repository (default `$TMPDIR/zcode-spike-capture`).
   The `.gitignore` excludes `capture-raw/`, but prefer `$TMPDIR` entirely.

## Procedure (each probe window)

1. **Environment**: open the synthetic workspace in the desktop; create one synthetic
   task with known, non-sensitive content; generate a fresh QR.
2. **Capture**: run the spike client with capture enabled:
   ```
   SPIKE_QR_URL=... SPIKE_CAPTURE_DIR=$TMPDIR/zcode-spike-capture \\
     spike/bin/spike --stage full --capture-id <date>-<seq>
   ```
   Every inbound/outbound relay JSON frame is appended to
   `$SPIKE_CAPTURE_DIR/<capture-id>.jsonl` as `{dir, ts, msg}` lines.
3. **Stop condition**: close the spike client before closing the desktop dialog.
4. **Redact immediately** (before any inspection that could copy content elsewhere):
   ```
   node tools/capture/redact.mjs \\
     --in $TMPDIR/zcode-spike-capture/<capture-id>.jsonl \\
     --out fixtures/relay/<capture-id>.fixture.jsonl \\
     --report $TMPDIR/zcode-spike-capture/<capture-id>.redaction-report.json
   ```
5. **Validate**:
   ```
   node tools/capture/validate-fixture.mjs fixtures/relay/<capture-id>.fixture.jsonl
   node --test tools/capture/redact.test.mjs   # property test, must be green
   ```
6. **Manual review**: open the fixture, confirm no recognizable content, paths, ids or
   hashes remain (the redaction report lists every replacement class and count).
7. Only then `git add fixtures/relay/<capture-id>.fixture.jsonl`.

## Sanitization contract (normative)

The redactor MUST remove/replace:

| Class | Rule |
|---|---|
| Pairing secrets | `hash`/`pass_hash`/`proof`/`nonce` values → fixed synthetic tokens |
| Device ids | `device_sid` (`d_…`), `device_mid` → format-preserving synthetic tokens |
| Transport correlation | `bridgeSessionId`, `recoveryId`, `windowControlSessionId` → synthetic tokens (format preserved) |
| Payload content | rpc-frame `dataBase64` → decoded-size-preserving synthetic bytes, CRC recomputed (transport geometry survives, content does not) |
| Sensitive keys anywhere | `workspacePath`→`/synthetic/ws`, `workspaceIdentity`, `label`, `name`, task/session ids, `title`, prompt-bearing strings → synthetic tokens |
| Nonces/timestamps | `nonce` → synthetic; timestamps may stay (non-identifying, keep ordering evidence) |

Prohibited in any committed fixture (INV-15/16): real credentials, QR links,
conversation content, personal paths, repository names, model keys, provider tokens,
personal tokens.

## Failure handling

- **Any real secret discovered post-commit**: purge from git history (filter/rebase),
  delete the fixture, restart the capture per §43.2-1.1 rollback. Report the incident
  in the phase gate document.
- **Redactor doubt** (unknown field that might carry content): the tool's default is
  value replacement for unknown string fields longer than 24 chars inside workspace/
  task/bootstrap payloads — when in doubt, redact.

## Verification commands (evidence for the phase gate)

```
node --test tools/capture/redact.test.mjs         # redaction property test
node tools/capture/validate-fixture.mjs <fixture>  # fixture schema validation
```
