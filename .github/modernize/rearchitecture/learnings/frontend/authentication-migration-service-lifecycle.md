# Authentication Migration Service Lifecycle

Long-running authentication work should remain service-owned while a Fragment controller owns only lifecycle-scoped observation and navigation.

## What Happened

In `silencesms/authentication-database-migration-host`, SMS import could complete while the migration
Fragment was stopped. The service then committed the durable imported flag, broadcast completion, and
terminated, so a later bind could expose a fresh idle service rather than the completed in-memory state.

## Takeaway

Pair bind/unbind and register/unregister under Fragment start/stop, generation-guard every service and
receiver callback, and clear Handler work plus service references on detach. Reattach by reading both
the live service state and the operation's durable completion marker. Keep starting the service with
only a captured unlock generation so it reacquires and validates the secret at execution time.

For nested authentication surfaces, sanitize only a live token already owned by the exact target
surface; class-level host ownership is insufficient when several security flows share one Activity.

## History

- 2026-09-04 (silencesms/authentication-database-migration-host): initial
