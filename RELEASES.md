# Releasing this fork

This repository is a fork of [jellyfin/jellyfin-android](https://github.com/jellyfin/jellyfin-android).
It publishes its own APK builds through GitHub releases using the
[`Fork / Release`](.github/workflows/fork-release.yaml) workflow.

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

## Signing

Without any configuration the release contains the debug APK, which is signed with a keystore that
is generated fresh on every CI run. Android then treats each build as a different app and cannot
install it over the previous one, so **configure the signing secrets once** to get a stable,
upgradeable release build.

### 1. Create a keystore

Run the helper script (it never writes the key into the repository):

```powershell
.\scripts\create-signing-key.ps1
```

It creates `signing/fork-release.jks` in the parent of the repository and prints the four values you
need. Keep the file and the passwords safe: losing them means you can no longer update an installed
build in place.

### 2. Add repository secrets

In the fork: **Settings → Secrets and variables → Actions → New repository secret**.

| Secret              | Value                                             |
| ------------------- | ------------------------------------------------- |
| `KEYSTORE`          | base64 encoded content of the `.jks` file         |
| `KEYSTORE_PASSWORD` | keystore password                                 |
| `KEY_ALIAS`         | key alias                                         |
| `KEY_PASSWORD`      | key password                                      |

The script prints the base64 value as well, ready to paste.

### 3. Publish again

Once the secrets exist, the workflow additionally builds `assembleLibreRelease` and attaches a
signed `jellyfin-android-<version>-libre-release.apk`.

> Note: an installed debug build (`org.jellyfin.mobile.debug`) is a separate app from the release
> build (`org.jellyfin.mobile`). Installing the signed release APK for the first time requires
> uninstalling the debug build. After that, later releases update in place.
