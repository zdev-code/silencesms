# Authentication-Only Host Boundary

Move authentication surfaces incrementally behind a strict auth-only host while returning protected targets to fresh access-policy evaluation.

## What Happened

In `silencesms/authentication-welcome-host`, WELCOME moved first because it carries no secret state.
The private host accepts one surface enum plus the existing opaque continuation fields, restores only
the matching authentication Fragment, and contains no application Navigation graph.

## Takeaway

An authentication host should validate its complete Intent shape before attaching UI, persist no
READY state, and consume protected targets only from an owner-bound one-shot store. Any malformed,
cold, stale, expired, or duplicate handoff should launch the private application host so its normal
bootstrap controller reevaluates current policy rather than trusting restored authentication state.

## History

- 2026-09-04 (silencesms/authentication-welcome-host): initial