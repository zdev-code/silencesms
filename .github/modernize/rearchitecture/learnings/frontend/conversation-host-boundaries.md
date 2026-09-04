# Conversation Host Boundaries

Extract shared conversation ownership before making the Navigation host authoritative, while keeping popup window behavior and sensitive payloads outside route state.

## What Happened

In Silence SMS task `conversation-host-migration`, the main conversation Activity was both a large UI/controller and the superclass of the mandatory popup window. The controller was extracted into a Fragment used by a thin popup-compatible Activity host and by the normal Navigation host. Exported share and SENDTO bridges now place sensitive content in a generation-bound one-shot store and route only an opaque token.

## Takeaway

Use a shared Fragment/controller for host and popup, keep route state primitive or opaque, and consume external payload tokens once under the active unlock generation. Preserve former `singleTask` behavior by updating an already-visible conversation destination instead of stacking it. Pop selector destinations before opening a conversation. Message details can remain a separate retained secure flow when conversation ownership no longer depends on it.

## History

- 2026-09-04 (silencesms/conversation-host-migration): initial
- 2026-09-04 (silencesms/conversation-host-migration): recorded completed shared-controller, payload-token, and back-stack design
