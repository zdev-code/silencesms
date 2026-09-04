# Authentication Change Generation Transition

An unlocked mutation route must deliberately bridge the old unlock generation to the newly cached generation only after lifecycle-checked establishment.

## What Happened

In `silencesms/authentication-change-passphrase-host`, CHANGE_PASSPHRASE entry was correctly bound to
the current unlock generation. Successful wrapper activation and key-cache establishment incremented
that generation, so an unchanged token would then reject the legitimate private return.

## Takeaway

Keep mutation entry validation bound to the old generation through guarded activation and cache
establishment. From the live Fragment callback only, rebind the exact owner/token/destination entry to
the current non-null generation, then consume it once. Never relax ordinary stale-generation checks or
persist either generation in route/saved state.

## History

- 2026-09-04 (silencesms/authentication-change-passphrase-host): initial
