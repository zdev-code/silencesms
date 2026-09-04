# Message Details Host Boundary

Move secure detail screens into the authoritative host with primitive routes and generation-gated in-memory result delivery.

## What Happened

In Silence SMS task `message-details-host-migration`, message details needed a decrypted `MessageRecord` and
`MasterSecret` for existing row actions. Navigation carries only opaque IDs and an integer transport type;
the existing generation-gated repository callback delivers the secret and record directly to the Fragment.
Host relock clears the ViewModel, rows, dialog, and secret synchronously before authentication routing.

## Takeaway

Keep sensitive screen models out of route and saved state. Reacquire them behind the current unlock generation,
make the Fragment the sole action/result owner, and explicitly clear every child view or dialog that captures them.
Route child-list actions upward through a primitive callback so reusable row views never choose host destinations.

## History

- 2026-09-04 (silencesms/message-details-host-migration): initial
