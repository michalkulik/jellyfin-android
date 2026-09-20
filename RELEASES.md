# Releasing this fork

This repository is a fork of [jellyfin/jellyfin-android](https://github.com/jellyfin/jellyfin-android).
It publishes its own APK builds through GitHub releases using the
[`Fork / Release`](.github/workflows/fork-release.yaml) workflow.

## Remotes

| Remote     | Points at                                   |
| ---------- | ------------------------------------------- |
| `fork`     | `michalkulik/jellyfin-android` (this fork)  |
| `upstream` | `jellyfin/jellyfin-android` (the original)  |

`master` tracks `fork/master`, so a plain `git push` goes to the fork.

## Keeping the fork up to date with upstream

The fork keeps its own commits (the Jellykulik branding, managed downloads, the release workflow) on
`master` and pulls in upstream changes with a **merge commit**. Rebasing is deliberately avoided: it
rewrites already published commits, invalidates the release tags and would need a force-push over the
fork history.

The whole procedure is scripted:

```powershell
# look first, change nothing
.\scripts\sync-upstream.ps1 -DryRun

# then actually merge
.\scripts\sync-upstream.ps1
```

The script refuses to run with uncommitted changes, fetches `upstream`, prints how far the fork has
diverged, lists the incoming commits, warns about files that changed on **both** sides (the usual
source of conflicts), creates a `backup/before-upstream-merge` branch, merges with `--no-ff` and
finally checks that the Jellykulik branding survived. It never pushes.

Afterwards build and push by hand:

```powershell
.\gradlew.bat :app:compileLibreDebugKotlin
git push fork master
```

If the merge went wrong, undo it and start over:

```powershell
git merge --abort                                  # during the merge
git reset --hard backup/before-upstream-merge      # after the merge commit
```

Once you are happy with the result, drop the safety net:

```powershell
git branch -D backup/before-upstream-merge
git push fork --delete backup/before-upstream-merge
```

## Publishing a new version

1. Make sure your changes are committed and pushed to `master`:

   ```bash
   git push fork master
   ```

2. Create and push a version tag (the `v` prefix is stripped from the app version):

   ```bash
   git tag -a v0.2.0 -m "Jellyfin Android fork 0.2.0"
   git push fork v0.2.0
   ```

3. The workflow builds the APKs and creates (or updates) the GitHub release at
   `https://github.com/michalkulik/jellyfin-android/releases`.

You can also run the workflow manually from the **Actions** tab (*Fork / Release* → *Run workflow*)
and provide a version number.

## What gets published

Each release contains two signed APKs, and never a debug build:

| File                                                    | Description                          |
| ------------------------------------------------------- | ------------------------------------ |
| `jellyfin-android-<version>-libre-release.apk`           | fully free build, no Chromecast      |
| `jellyfin-android-<version>-proprietary-release.apk`     | with Chromecast support              |

## Signing

Signing is required — the workflow fails if the secrets are missing, because a debug APK cannot be
used to update an installed release build.

### 1. Create a keystore

Run the helper script (it never writes the key into the repository):

```powershell
.\scripts\create-signing-key.ps1
```

It creates `signing/fork-release.jks` next to the repository, prints the passwords and writes the
long base64 keystore to `signing/github-secrets.txt`. Keep both files and the passwords safe:
losing them means you can no longer update an installed build in place.

### 2. Add repository secrets

In the fork open
[Settings → Secrets and variables → Actions](https://github.com/michalkulik/jellyfin-android/settings/secrets/actions)
and create four *New repository secret* entries using the values the script printed:

| Secret              | Value                                            |
| ------------------- | ------------------------------------------------ |
| `KEYSTORE`          | base64 keystore from `signing/github-secrets.txt` |
| `KEYSTORE_PASSWORD` | keystore password                                |
| `KEY_ALIAS`         | key alias                                        |
| `KEY_PASSWORD`      | key password                                     |

### 3. Publish

With the secrets in place every tagged version publishes the signed `libre` and `proprietary`
release APKs.

> Note: an installed debug build (`org.jellyfin.mobile.debug`) is a separate app from the release
> build (`org.jellyfin.mobile`). Installing the signed release APK for the first time requires
> uninstalling the debug build. After that, later releases update in place.
