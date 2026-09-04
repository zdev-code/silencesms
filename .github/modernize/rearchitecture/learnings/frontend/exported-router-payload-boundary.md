# Exported Router Payload Boundary

Exported Android routers should terminate external Intent state before protected UI and transfer only an opaque memory token to private hosts.

## What Happened

In `silencesms/exported-router-minimization`, launcher, share, and SENDTO Activities became immediate
no-layout shells. Locked share/SENDTO entries bind to the first authenticated host generation. External
URI access remains cleanup-bound until the private host encrypts media and atomically replaces the
payload cleanup. Obsolete share bootstrap restoration was removed.

## Takeaway

Validate exact action/category/scheme/MIME/flag/field contracts at the exported edge, clear the source
Intent before dispatch, and use a one-shot dispatch guard. Keep plaintext, URIs, addresses, direct-share
metadata, and secrets in an owner/expiry/generation/cleanup-bound memory entry. Resolve or encrypt only
inside the private authenticated host, then atomically transfer cleanup and revoke delegated grants.

## History

- 2026-09-04 (silencesms/exported-router-minimization): initial