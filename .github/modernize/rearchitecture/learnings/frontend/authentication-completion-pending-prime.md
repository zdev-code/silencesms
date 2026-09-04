# Authentication Completion Pending Prime

Authentication completion must treat a primed key as coordinator-owned pending state until lifecycle-checked service establishment succeeds.

## What Happened

In `silencesms/authentication-create-passphrase-host`, create work moved from an Activity to a
Fragment-owned cancellable controller. The existing key-cache handoff primes a static secret before
service binding, so merely nulling the Fragment field on teardown could leave a stale pending key.

## Takeaway

Keep generated secrets out of route and saved state, invalidate worker callbacks by operation
generation, and recheck coordinator generation, Activity lifecycle, and cache identity immediately
before `KeyCachingService.setMasterSecret`. Hide the pending prime from cache and unlock-snapshot
readers so service startup cannot publish it early. On pre-establishment cancellation, clear only the
exact object that coordinator primed; never clear a replacement key installed by another owner.

## History

- 2026-09-04 (silencesms/authentication-create-passphrase-host): initial