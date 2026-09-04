# Authentication Upgrade Restartable Coordinator

Authentication upgrade UI should reattach to one application-scoped restartable operation without persisting progress or readiness in host state.

## What Happened

In `silencesms/authentication-database-upgrade-host`, database upgrade moved from an Activity into the
shared authentication host. The persisted coordinator remained authoritative for stages and latest
progress, while a Fragment controller owned observer generations and one-shot completion claims.
Synchronous progress replay during Fragment `onStart` occurred before the lifecycle registry reported
`STARTED`, so callbacks had to be posted through the Fragment view and rechecked at execution time.

## Takeaway

For restartable auth work, capture a fresh `UnlockSession`, start through the application-scoped
coordinator, and keep only observer/claim state in the Fragment controller. Reattach on `onStart`, post
synchronous replay through the view, and require both current lifecycle and exact host surface before
claiming navigation. Disable widget state saving when progress must not enter restored bundles.

## History

- 2026-09-04 (silencesms/authentication-database-upgrade-host): initial