# Bootstrap Continuation Boundary

Authentication gates should exchange only typed destinations and opaque memory tokens, then reacquire secrets at the protected operation boundary.

## What Happened

In `silencesms/authentication-bootstrap-continuation`, nested Intents and `MasterSecret` extras were
replaced by a finite Activity allowlist backed by an owner-bound, expiring, consume-once store. Target
Intents remain in process memory and are rebuilt from target-specific validated fields.

## Takeaway

Bind entries to unlock generation only when issued while unlocked so locked passphrase flows can advance.
Treat cold, stale, expired, duplicate, or wrong-owner tokens as fresh policy evaluation, never restored
readiness. Long-running services should receive a generation primitive and resolve the secret when work
actually starts.

## History

- 2026-09-04 (silencesms/authentication-bootstrap-continuation): initial