# Selector Payload Host Boundary

Keep external conversation payloads in one generation-bound store entry while a host selector returns only validated recipient IDs.

## What Happened

In `silencesms/final-normal-flow-migration`, share and SENDTO content could not enter the new-conversation route, but selecting a recipient changed the payload destination binding. The store gained an atomic retarget operation that preserves generation, expiry, and cleanup while replacing only thread, recipient, distribution, and owner bindings. Group contact selection moved to a one-shot FragmentResult carrying positive recipient IDs.

## Takeaway

Route only opaque selector tokens, retain only that token across configuration changes, and fail closed before rendering when the memory entry is absent. Retarget the existing entry only after recipient validation. Clear adapters, selection maps, pending callbacks, result bundles, and destination ViewModels synchronously on relock.

## History

- 2026-09-04 (silencesms/final-normal-flow-migration): initial
