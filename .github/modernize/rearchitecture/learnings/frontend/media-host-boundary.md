# Media host boundary

- Normal media overview and preview belong to `ConversationListActivity`; popup preview replaces the conversation Fragment on one local back-stack entry inside the retained popup task rather than switching tasks or launching a bridge Activity.
- Persisted media routes carry positive attachment/message/thread/recipient IDs only; draft routes carry mode and optional positive size, with URI/content type in an expiring consume-once memory store.
- Draft validation must reject both attachment row ID and unique ID; testing only the row ID leaves a database-identity smuggling gap.
- External decrypted media should stream through a captured-generation pipe, not a temp file. Revalidate generation per chunk and revoke the URI grant on result, teardown, and relock.
- Popup menu and compose focus must be gated by the active local Fragment. Back restores the conversation action bar/system bars and compose focus; relock clears both Fragment states and the draft store before authentication routing.

## History

- 2026-09-04 (silencesms/media-host-migration): initial host and draft boundary
- 2026-09-04 (silencesms/popup-media-preview-host-migration): removed the bridge after local popup navigation passed focused, policy, full-test, lint, and release gates
