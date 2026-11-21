# IndoorPulse repository instructions

## Resume and hand off

- Read the local `HANDOFF.md` if present and run `git status --short --branch` before editing. The handoff is ignored and will be absent from a fresh clone. Review the staged diff, unstaged diff, and untracked files separately. Preserve work from the owner and other sessions.
- Update the local handoff after meaningful changes, verification, a deployment, or a new blocker or decision. Refresh it before pausing or switching sessions. Routine questions need no update. Record what shipped or changed, files and entry points, what was actually verified, remaining work, and exact reproduction or continuation steps. Keep the latest verified APK and test results separate from older findings and from current unbuilt source.
- Keep the handoff free of credential values, real map coordinates, wireless ADB identifiers and raw dumps. Share it privately when changing machines or accounts. Review the working tree separately because an ignored handoff does not back up uncommitted code.

## Build and test safely

- Run `:app:assembleDebug` and `:app:testDebugUnitTest` before device work. Check the handoff for the known-good local JDK and Gradle invocation.
- Obtain the owner's go-ahead before device testing. Use the in-memory Room workflow fixture or an isolated map. Verify Find exit first when validating navigation. Install over the existing app with `-r` and preserve saved maps, including during debugging and data migration. Read instrumentation output for test failures even if ADB exits successfully.

## Git and private data

- The owner handles staging, commits and pushes unless explicitly delegated. Stage reviewed paths by name. Before every commit review `git diff --cached --name-status` and `git diff --cached`, and check untracked paths with `git ls-files --others --exclude-standard`. Before pushing, inspect `git log '@{upstream}..HEAD'` and the commit contents that will reach the remote.
- Keep `HANDOFF.md`, attachments, screenshots, device captures/logs, real map exports, SDK settings, signing material, Android Studio deployment targets, and ADB metadata local. Use ignored `local/` for manually collected files and `build/ui-audit/` for generated verification artifacts. Use synthetic maps in committed fixtures. Review new JSON, XML and image files for private data.
- Check exclusions with `git check-ignore -v -- <path>` and check tracking with `git ls-files -- <path>`. An ignore rule applies to untracked files. For a tracked local file the owner can review a `git rm --cached -- <path>` removal, which preserves the local copy. Earlier commits retain their contents. Get explicit owner approval for history rewrites or force pushes. If an actual credential has been committed, flag it privately and recommend revoking or rotating it.
