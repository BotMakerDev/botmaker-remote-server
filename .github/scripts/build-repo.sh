#!/usr/bin/env bash
# Builds the static dnf + apt repository published to GitHub Pages, out of the .rpm/.deb this release just
# produced. Users then get `sudo dnf install botmaker-remote-server`, and every later version arrives with
# the rest of their system updates.
#
#   build-repo.sh <artifacts-dir> <site-dir> <tag>
#
# This is botmaker-cli's script with the names changed, and the reasoning behind each decision is written
# once, there (`.github/scripts/build-repo.sh` in that repository). The four that matter here:
#
#   - THE PACKAGES ARE HOSTED HERE, both formats. ~14 MB against Pages' ~1 GB soft limit, and only the
#     latest release is ever published, so the `--baseurl` offload botmaker-studio needs buys nothing.
#   - ONLY THE LATEST RELEASE. An upgrade channel, not an archive: every older version stays downloadable
#     as a GitHub Release asset, and the deploy is a fresh artifact each time.
#   - SIGNING is at the repository level (repomd.xml.asc, InRelease) AND at the package level for rpm,
#     because dnf's `gpgcheck` covers the package's own header and `repo_gpgcheck` covers the index — the
#     generated .repo sets both. The rpm is signed in the release job; see packaging/nfpm.yaml.
#   - WITH SIGNING UNCONFIGURED the repository is still built and the snippets turn the checks off, so what
#     is published stays self-consistent. That path is for dry runs, not for a real release.
#
# A SEPARATE REPOSITORY FROM botmaker-cli's, rather than one index serving both packages: a Pages site is
# built from the artifacts of the release that triggered it, so a shared index would mean each repository
# fetching the other's newest package to avoid dropping it from the index — two releases that can break
# each other, to save one `curl` in the install instructions.
set -euo pipefail

ARTIFACTS="${1:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"
SITE="${2:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"
TAG="${3:?usage: build-repo.sh <artifacts-dir> <site-dir> <tag>}"

PACKAGE=botmaker-remote-server
REPO_SLUG="${GITHUB_REPOSITORY:-LiQiyeDev/botmaker-remote-server}"
# Pages serves <owner>.github.io/<repo>, lowercased.
PAGES_URL="${PAGES_URL:-https://$(echo "${REPO_SLUG%%/*}" | tr '[:upper:]' '[:lower:]').github.io/${REPO_SLUG##*/}}"

shopt -s nullglob
rpms=("${ARTIFACTS}"/*.rpm)
debs=("${ARTIFACTS}"/*.deb)
[ ${#rpms[@]} -gt 0 ] || { echo "::error::no .rpm found in ${ARTIFACTS}"; exit 1; }
[ ${#debs[@]} -gt 0 ] || { echo "::error::no .deb found in ${ARTIFACTS}"; exit 1; }
RPM="${rpms[0]}"
DEB="${debs[0]}"

SIGNING=0
if [ "${BOTMAKER_SIGN:-0}" = "1" ] && [ -n "${GPG_KEY_ID:-}" ]; then
  SIGNING=1
else
  echo "::warning::signing not configured — publishing a repository nothing can verify."
fi

# gpg in batch/loopback mode, matching how ci.yml imports the key.
gpg_run() {
  gpg --batch --yes --pinentry-mode loopback --passphrase "${GPG_PASSPHRASE:-}" -u "${GPG_KEY_ID}" "$@"
}

mkdir -p "${SITE}/rpm" "${SITE}/deb/pool/main/b/${PACKAGE}"

# --- dnf --------------------------------------------------------------------------------------------
cp "${RPM}" "${SITE}/rpm/"
# --general-compress-type, NOT --compress-type: the latter covers only the *additional* metadata and would
# leave primary.xml on whatever the default is. gz because every dnf reads it.
createrepo_c --general-compress-type gz "${SITE}/rpm"

if [ "${SIGNING}" = "1" ]; then
  gpg_run --detach-sign --armor -o "${SITE}/rpm/repodata/repomd.xml.asc" "${SITE}/rpm/repodata/repomd.xml"
fi

# --- apt --------------------------------------------------------------------------------------------
# `Architecture: all`, and apt fetches dists/stable/main/binary-<the host's arch>/Packages — it looks in
# binary-all only when the sources.list line says `[arch=all]`, which nobody types. So one index is
# published under every architecture claimed.
ARCHES=(amd64 arm64 all)
for arch in "${ARCHES[@]}"; do
  mkdir -p "${SITE}/deb/dists/stable/main/binary-${arch}"
done
cp "${DEB}" "${SITE}/deb/pool/main/b/${PACKAGE}/"
(
  cd "${SITE}/deb"
  # Filename: in Packages is relative to this directory, which is why apt-ftparchive runs from here.
  apt-ftparchive packages pool > "${TMPDIR:-/tmp}/Packages.$$"
  for arch in "${ARCHES[@]}"; do
    cp "${TMPDIR:-/tmp}/Packages.$$" "dists/stable/main/binary-${arch}/Packages"
    gzip -9cf "dists/stable/main/binary-${arch}/Packages" \
      > "dists/stable/main/binary-${arch}/Packages.gz"
  done
  rm -f "${TMPDIR:-/tmp}/Packages.$$"

  # Hashes every file under dists/stable, so it runs after the loop — and must NOT write into that tree
  # while doing so: redirecting straight into dists/stable/Release makes apt-ftparchive hash its own
  # partially-flushed output and sign a bogus entry.
  apt-ftparchive \
    -o APT::FTPArchive::Release::Origin=BotMaker \
    -o APT::FTPArchive::Release::Label="BotMaker remote server" \
    -o APT::FTPArchive::Release::Suite=stable \
    -o APT::FTPArchive::Release::Codename=stable \
    -o APT::FTPArchive::Release::Architectures="${ARCHES[*]}" \
    -o APT::FTPArchive::Release::Components=main \
    -o APT::FTPArchive::Release::Description="BotMaker remote server release channel" \
    release dists/stable > "${TMPDIR:-/tmp}/Release.$$"
  mv "${TMPDIR:-/tmp}/Release.$$" dists/stable/Release
)
if [ "${SIGNING}" = "1" ]; then
  gpg_run --clearsign -o "${SITE}/deb/dists/stable/InRelease" "${SITE}/deb/dists/stable/Release"
  gpg_run --detach-sign --armor -o "${SITE}/deb/dists/stable/Release.gpg" "${SITE}/deb/dists/stable/Release"
fi

# --- the public key and the two snippets --------------------------------------------------------------
# `signed-by=` accepts an ASCII-armored key as long as the file is named .asc, so one export serves apt and
# `rpm --import` both. The key is the same one botmaker-cli's repository publishes, under the same name:
# somebody who installed that has already trusted it.
if [ "${SIGNING}" = "1" ]; then
  gpg --export --armor "${GPG_KEY_ID}" > "${SITE}/botmaker.asc"
  rpm_gpg=$'gpgcheck=1\nrepo_gpgcheck=1\ngpgkey='"${PAGES_URL}/botmaker.asc"
  apt_opts="[signed-by=/etc/apt/keyrings/botmaker.asc] "
else
  rpm_gpg=$'gpgcheck=0\nrepo_gpgcheck=0'
  apt_opts="[trusted=yes] "
fi

cat > "${SITE}/${PACKAGE}.repo" <<EOF
[${PACKAGE}]
name=BotMaker remote server
baseurl=${PAGES_URL}/rpm
enabled=1
${rpm_gpg}
EOF

DNF_SNIPPET="sudo curl -fsSL -o /etc/yum.repos.d/${PACKAGE}.repo ${PAGES_URL}/${PACKAGE}.repo
sudo dnf install ${PACKAGE}
systemctl --user enable --now botmaker-remote"

APT_SNIPPET="sudo install -d -m 755 /etc/apt/keyrings
sudo curl -fsSL -o /etc/apt/keyrings/botmaker.asc ${PAGES_URL}/botmaker.asc
echo \"deb ${apt_opts}${PAGES_URL}/deb stable main\" | sudo tee /etc/apt/sources.list.d/${PACKAGE}.list
sudo apt-get update && sudo apt-get install ${PACKAGE}
systemctl --user enable --now botmaker-remote"

# --- landing page --------------------------------------------------------------------------------------
# One self-contained file with no assets: this site is metadata, and a stylesheet request would be one more
# thing to keep alive for a page people visit once.
cat > "${SITE}/index.html" <<EOF
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${PACKAGE} — package repository</title>
<style>
  :root { color-scheme: light dark; --fg: #1a1a1a; --bg: #ffffff; --muted: #5f6368; --line: #e0e0e0; --code-bg: #f5f5f5; }
  @media (prefers-color-scheme: dark) {
    :root { --fg: #e8e8e8; --bg: #16181c; --muted: #9aa0a6; --line: #2c2f36; --code-bg: #1f2228; }
  }
  body { margin: 0 auto; padding: 3rem 1.25rem 5rem; max-width: 46rem; color: var(--fg); background: var(--bg);
         font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
  h1 { font-size: 1.6rem; margin: 0 0 .25rem; }
  h2 { font-size: 1.15rem; margin: 2.5rem 0 .5rem; padding-top: 1.25rem; border-top: 1px solid var(--line); }
  p.sub { color: var(--muted); margin: 0 0 2rem; }
  pre { background: var(--code-bg); border: 1px solid var(--line); border-radius: 6px;
        padding: .9rem 1rem; overflow-x: auto; font-size: .875rem; }
  code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  footer { margin-top: 3rem; color: var(--muted); font-size: .875rem; }
  a { color: inherit; }
</style>
</head>
<body>
<h1>${PACKAGE}</h1>
<p class="sub">dnf and apt repositories for <strong>${TAG}</strong> — the Claude Code terminals on a dev
box, served to a phone over Tailscale. Install once, then update with your package manager.</p>

<h2>Fedora / RHEL</h2>
<pre><code>${DNF_SNIPPET}</code></pre>

<h2>Debian / Ubuntu</h2>
<pre><code>${APT_SNIPPET}</code></pre>

<p>Later updates, either way: <code>sudo dnf upgrade ${PACKAGE}</code> or
<code>sudo apt-get update &amp;&amp; sudo apt-get install --only-upgrade ${PACKAGE}</code>, then
<code>systemctl --user restart botmaker-remote</code>.</p>

<h2>Anywhere else</h2>
<p>One jar: download <code>${PACKAGE}-all.jar</code> from the
<a href="https://github.com/${REPO_SLUG}/releases">Releases</a> page and run it with <code>java -jar</code>,
or use <code>tools/install.sh</code> from a checkout for the same four files under <code>~/.local</code>.</p>

<footer>
<p>Installing starts nothing. The unit is a <strong>user</strong> unit — this program hands out a shell and
runs <code>cswap</code> and <code>claude</code> as you, so it is your process, and
<code>systemctl --user enable --now botmaker-remote</code> is where you consent to it listening. It binds
the <code>tailscale0</code> address and refuses to start without one.</p>
<p>The package installs the jar at <code>/usr/share/botmaker/</code>, the launcher at
<code>/usr/bin/${PACKAGE}</code>, the Claude Code hook at <code>/usr/bin/botmaker-remote-hook</code> and the
unit at <code>/usr/lib/systemd/user/</code>. It requires a Java 25 runtime and <code>tmux</code>.</p>
<p>This repository carries the <strong>latest release only</strong> — it is an upgrade channel, not an
archive.</p>
</footer>
</body>
</html>
EOF

echo "Site built at ${SITE} ($(du -sh "${SITE}" | cut -f1)), advertising ${TAG} at ${PAGES_URL}"
