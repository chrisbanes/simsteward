# Releases

## Release changelog

The project maintains `CHANGELOG.md` following the [Keep a Changelog](https://keepachangelog.com)
format. When preparing releases, update the changelog with entries from GitHub releases. Changes
should be categorized under standard headings: Added, Changed, Fixed, Deprecated, Removed, and
Security. Each version entry includes the release date in `YYYY-MM-DD` format, selected links to pull
requests, and contributor acknowledgments. The changelog can be regenerated or updated using the
GitHub REST API to fetch release notes: `curl -s
"https://api.github.com/repos/chrisbanes/simsteward/releases?per_page=100"`.

Write changelogs around what the release does for users: new capabilities, observable improvements,
and necessary upgrade actions. Summarize related work rather than listing every PR or implementation
detail. Include bug fixes only for defects present in previously released public behavior; omit
fixes to features introduced in the same release. Use PR links selectively and preserve contributor
credits.

## Release steps

1. Run `./gradlew metalavaGenerateSignature` so `api/api.txt` reflects current sources; the
   compatibility check does not flag additions, so it can be stale.
2. Commit `Prepare for release X.Y.Z`: set `VERSION_NAME` to `X.Y.Z`, date the `Unreleased`
   changelog section, and copy `api/api.txt` to `api/X.Y.Z.txt`.
3. Push that commit on its own, then tag it `X.Y.Z`.
4. The `deploy` CI job on `main` publishes to Maven Central and releases automatically. Wait for it
   to succeed. A push runs CI only for its head commit, so pushing the next commit with it would
   deploy only the snapshot. Only `deploy` signs: it sets
   `signAllPublications`, so local builds publish unsigned.
5. Create the GitHub release for the tag.
6. Commit `Prepare next development version`: set `VERSION_NAME` to the next `-SNAPSHOT`, and push.
