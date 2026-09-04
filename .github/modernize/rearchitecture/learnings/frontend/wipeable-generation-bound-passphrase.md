# Wipeable Generation-Bound Passphrase

Passphrase mutations should combine explicit buffer ownership with a guard inside the atomic activation coordinator.

## What Happened

In `silencesms/passphrase-pipeline-hardening`, Activity-owned immutable strings and async work were
replaced by closeable character buffers plus a controller that owns worker buffers, cancellation, and
callback generation. The existing stage/verify/activate coordinator gained guard checks before work and
immediately before activation, preserving wrapper authority on stale-generation failure.

Java's `Normalizer.normalize` returns an immutable `String`; using a `CharBuffer` avoids an additional
raw-passphrase string, but the normalized result itself cannot be wiped. It must remain method-local,
with UTF-8 bytes wiped in `finally`.

## Takeaway

Wipe queued-operation buffers from the controller's cancel path because a cancelled callable may never
execute its own `finally`. Keep compatibility String overloads, but delegate them through temporary
character arrays and clear PBKDF specs, encoded keys, Argon2 bytes, and derived keys. Put the generation
guard inside the coordinator, not only around the Activity callback.

## History

- 2026-09-04 (silencesms/passphrase-pipeline-hardening): initial