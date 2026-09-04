# Generation-Bound Document Results

Host-owned document flows should retain generation tokens and memory-only operation material, never cached secrets or route payloads.

## What Happened

In Silence SMS task `prompt-mms-import-export-host-migration`, five Activity Result continuations had
to survive outside a secret-retaining Activity bridge. Each launcher now captures an `UnlockSession`,
the callback consumes it once, and `ConversationUnlockCapability.use` resolves the secret only around
the actual import, export, permission persistence, or backup-manager invocation.

## Takeaway

Keep raw URIs in callback parameters only, recovery material in wipeable destination fields only, and
generation tokens alongside pending launchers. Relock must null pending tokens, wipe byte arrays,
dismiss secret-rendering dialogs, cancel workers, and invalidate late completion callbacks before the
host routes to authentication.

## History

- 2026-09-04 (silencesms/prompt-mms-import-export-host-migration): initial
