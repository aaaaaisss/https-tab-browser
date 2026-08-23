# HTTPS Tab Browser — Development / Handoff Plan

## Goal
Create a working Android APK from the **correct development branch**, with the intended address-bar / IME fixes and the existing browser stabilization changes reflected in the APK. Do not treat unrelated `main` CI artifacts as the deliverable.

## Sole working branch
- `rebuild/addressbar-96-clean`
- From this point onward, all application and CI-related work must be done on this branch only.
- **Do not modify `main`** for this task.
- Do not create or switch to another working branch unless explicitly requested.

## What has been happening
The project has had several address-bar / keyboard (IME) stabilization changes. The intended behavior is:
- The address bar should not remain in an editing state after the keyboard is dismissed / focus is lost in the relevant UI flow.
- Pressing X should clear the current address text while preserving editing/focus and reopening the keyboard when appropriate.
- The address-bar UI should not become visually duplicated/overlapped during keyboard input.
- The input area must remain usable when the keyboard appears; the keyboard must not hide the input field.
- Suggestions and bottom controls should restore correctly when editing ends.
- The browser should preserve normal navigation/back behavior and existing stabilization work.

## Important technical issue already found
A previous implementation used unsupported Compose API text such as:
- `WindowInsets.isImeVisible`

The actual supported implementation in the current code uses:
- `WindowInsets.ime.getBottom(density) > 0`

The verification script was temporarily inconsistent with the implementation and was corrected to check the supported API. **Do not reintroduce `WindowInsets.isImeVisible`.**

## Important workflow mistake already found
An obsolete workflow named:
- `.github/workflows/one-shot-ime-v2.yml`

could automatically rewrite `BrowserControls.kt` / `BrowserScreen.kt` on PR events. It contained older IME-fix code and could interfere with the intended branch state.

That obsolete workflow was removed from `main` in commit:
- `19c7e0d7b4fde2f027535bc259a0a74a43e53db7`

This was a workflow cleanup only; it did not intentionally modify the application source on `main`.

**Do not recreate that workflow or any other automatic source-rewriting workflow.**

## CI / APK rules
Every time an APK is considered a candidate deliverable, verify all of the following:
1. The workflow run is for `rebuild/addressbar-96-clean`.
2. The workflow checked out the exact latest commit SHA of that branch.
3. The relevant Android build job is successful.
4. `Upload signed release APK` is successful.
5. The artifact is the APK produced by that exact run/SHA.

A successful APK from `main` is **not** the deliverable for this task, even if the build is otherwise identical.

## Current known CI history
- Run `32644052515` / job `97205435029` was the correct branch's Android CI and failed during Gradle build because `BrowserControls.kt` referenced unsupported `isImeVisible`.
- The implementation was subsequently changed to the supported `WindowInsets.ime.getBottom(density) > 0` form.
- Run `32645569034` / job `97209195932` reached the verification stage but failed because the verification script was still looking for the obsolete `WindowInsets.isImeVisible` string. The verifier was then updated.
- Run `32647146938` successfully produced an APK, but it was a **`main` branch** run with head SHA `19c7e0d7...`; it must NOT be treated as the requested repaired-branch APK.

## Safe workflow from here
1. Inspect the current tip of `rebuild/addressbar-96-clean`.
2. Inspect the current relevant source and verification files before changing anything.
3. Confirm there are no obsolete source-rewriting workflows affecting the branch.
4. Trigger/observe Android CI for the branch.
5. If CI fails, inspect the actual failing log and fix the actual cause on the same branch.
6. Re-run CI.
7. Repeat until the Android build and APK upload succeed.
8. Before declaring success, match the APK artifact's run SHA to the branch's exact commit SHA.
9. Only then report the APK as the final repaired APK.

## Change discipline
- Do not make speculative fixes based only on a line number.
- Do not alter `main`.
- Do not create unrelated branches.
- Do not use old successful `main` artifacts as evidence that the repaired branch is complete.
- If a verifier and implementation disagree, inspect both and make the verifier accurately reflect the intended implementation; do not weaken verification just to make CI green.
- Preserve the user's existing project changes; do not overwrite complete source files with shortened/reconstructed versions unless absolutely necessary.

## Current objective
Get `rebuild/addressbar-96-clean` to a clean Android CI build and produce an APK artifact that can be proven to originate from that exact branch/commit.