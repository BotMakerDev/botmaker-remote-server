# Signing the Linux repositories

How `botmaker-remote-server`'s dnf/apt repository gets signed, and why the steps are all yours to run.

## Where this stands today (2026-09-19)

**The three secrets exist on the `BotMakerDev` organization, visible to every repository in it**, and this
repository moved into that organization on 2026-09-18. So the next release signs itself with no step here.
What follows is still the procedure — for a rotation, for a repository outside the organization, and as the
explanation of what the organization secrets hold. A repository secret of the same name wins over the
organization's, which is the escape hatch if one repository ever needs a different key.


| Repository | Packages | Index | Key |
|---|---|---|---|
| `botmaker-cli` | signed rpm | `repomd.xml.asc`, `InRelease` | `5300F1BC092474AC` — `LiQiyeDev <liqiyedev@users.noreply.github.com>`, published as `botmaker.asc` on its Pages site since 2026-09-04 |
| `botmaker-remote-server` | signed from the next release | the same, from the next release | the same key, inherited from the organization |

**So there is no key to make.** The workflow here is `botmaker-cli`'s with the names changed, it reads the
same three secret names, and the decision taken with the maintainer is *one key for every repository*.
Those three names are what the organization holds:

| Secret | What it holds | Read by |
|---|---|---|
| `GPG_KEY_ID` | the long key id, `5300F1BC092474AC` | the `pages` job (`if: env.GPG_KEY_ID != ''` is what turns signing on) |
| `GPG_PRIVATE_KEY` | the **base64** of the ASCII-armored secret key | the `release` job (nfpm signs the rpm) and the `pages` job (`gpg --import`) |
| `GPG_PASSPHRASE` | the key's passphrase | `nfpm` as `NFPM_RPM_PASSPHRASE`, and `gpg --pinentry-mode loopback` |

A GitHub secret can be written and never read back, so the values come from the key in your own keyring —
not from `botmaker-cli`'s settings page.

## Copy the key across

The umbrella's **`tools/signing-secret.sh`** is the whole procedure:

```bash
tools/signing-secret.sh --org BotMakerDev                            # every repository in the organization
tools/signing-secret.sh --repo BotMakerDev/botmaker-remote-server    # one repository, overriding the org
tools/signing-secret.sh --org BotMakerDev --dry-run                  # rehearse it, no gh call
```

It finds the secret keys in your keyring, makes you choose when there is more than one, prints the
fingerprint to compare by eye, refuses a key that is expired, revoked or disabled, and then pipes the
export straight into `gh` — no file, no shell history, no value as an argument (arguments are readable in
`/proc`). The passphrase is typed twice and never echoed. It ends by listing the three secret **names** it
set, which is all GitHub will ever give back.

### The same thing by hand, which is what the script does

1. Open **Passwords and Keys** (Seahorse; KDE: **Kleopatra**) and find **GPG Keys ▸
   `LiQiyeDev <liqiyedev@users.noreply.github.com>`**. Check it is `5300F1BC092474AC`: *Properties ▸
   Details ▸ Fingerprint* ends `5300 F1BC 0924 74AC`. (Kleopatra: the *Key-ID* column.)
2. Right-click ▸ **Export Secret Keys…** ▸ save as `botmaker-signing.asc` in a directory only you can read
   (`~/` is fine; a shared `/tmp` is not). Kleopatra: right-click ▸ *Backup Secret Keys…*, ASCII armor on.
3. In a terminal, in that directory:

   ```bash
   base64 -w0 botmaker-signing.asc > botmaker-signing.b64
   gh secret set GPG_PRIVATE_KEY --repo BotMakerDev/botmaker-remote-server < botmaker-signing.b64
   printf '5300F1BC092474AC' | gh secret set GPG_KEY_ID --repo BotMakerDev/botmaker-remote-server
   gh secret set GPG_PASSPHRASE --repo BotMakerDev/botmaker-remote-server   # prompts, echoes nothing
   ```

   Each value is piped from a file or typed into a prompt, so none of them reaches your shell history, the
   terminal scrollback, or anybody reading over the session.
4. `shred -u botmaker-signing.asc botmaker-signing.b64`. The key stays in the keyring; the passphrase stays
   in your password manager. **Back the key up once**, to an encrypted volume — a lost signing key means
   every client that already trusts it has to be told about a new one by hand.
5. `gh secret list --repo BotMakerDev/botmaker-remote-server` shows the three names (never the values).

### If you are ever setting up from nothing

Seahorse ▸ **+** ▸ *PGP Key*: a name, an email, **RSA 4096**, no expiry or a long one, a passphrase kept in
the password manager. Then step 2 onwards. A new key is a new trust decision for every installed client, so
prefer the existing one while it is good.

## Turning it on

Nothing in the workflow changes. `.github/workflows/ci.yml` already:

- writes `GPG_PRIVATE_KEY` out to `$RUNNER_TEMP/botmaker-signing.asc` and hands it to nfpm as
  `NFPM_RPM_KEY_FILE`, deleting it in the same step (the `release` job);
- imports the same key and sets `BOTMAKER_SIGN=1` when `GPG_KEY_ID` is non-empty (the `pages` job);
- and `.github/scripts/build-repo.sh` then signs `repodata/repomd.xml.asc`, writes `InRelease` and
  `Release.gpg`, exports `botmaker.asc` beside the indexes, and prints the **verified** snippets —
  `gpgcheck=1`, `repo_gpgcheck=1`, `gpgkey=<pages>/botmaker.asc` and
  `[signed-by=/etc/apt/keyrings/botmaker.asc]` — instead of the unsigned notice.

So the secrets take effect on the **next release of this module**, or on a re-run of the newest tag's
workflow:

```bash
gh run list --repo BotMakerDev/botmaker-remote-server --branch v0.0.3 --limit 3
gh run rerun <id> --repo BotMakerDev/botmaker-remote-server
```

A re-run rebuilds the packages and republishes the Pages site from them, which is the whole repository.

## Checking it worked

```bash
curl -fsS https://botmakerdev.github.io/botmaker-remote-server/botmaker.asc | gpg --show-keys
curl -fsS -o /dev/null -w '%{http_code}\n' https://botmakerdev.github.io/botmaker-remote-server/rpm/repodata/repomd.xml.asc
curl -fsS -o /dev/null -w '%{http_code}\n' https://botmakerdev.github.io/botmaker-remote-server/deb/dists/stable/InRelease
curl -fsS https://botmakerdev.github.io/botmaker-remote-server/ | grep -c unsigned      # 0
```

The key must be `5300F1BC092474AC`, the same one `botmaker-cli`'s site publishes.

## What existing installs have to do

The generated `.repo` turns `gpgcheck` **on**, so a machine that installed while it was off refuses the next
update until it trusts the key:

```bash
sudo rpm --import https://botmakerdev.github.io/botmaker-remote-server/botmaker.asc
sudo curl -fsSL -o /etc/yum.repos.d/botmaker-remote-server.repo \
  https://botmakerdev.github.io/botmaker-remote-server/botmaker-remote-server.repo
sudo dnf upgrade botmaker-remote-server
```

apt: take the line the [repository page](https://botmakerdev.github.io/botmaker-remote-server/) prints — it
carries `[signed-by=/etc/apt/keyrings/botmaker.asc]` and the `curl` that installs that keyring.

Somebody who already trusts the key for `botmaker-cli` still imports it here for apt (the keyring path is
per-repository by the line's own text) and already has it for dnf (`rpm --import` is machine-wide).

## What does not change

- **The unsigned branch stays in `build-repo.sh`.** A fork's tag build has no secrets and must still produce
  a package and a page that is honest about it. The branch is the escape, not dead code.
- **Two repositories, two Pages sites, one key.** Each site publishes its own copy of `botmaker.asc`; a
  shared index was refused because a Pages site is built from the artifacts of the release that triggered it.
- **Nothing here verifies anything on the build runner.** Signing says the packages came from this key. It
  is not a review of what is in them, and the README must keep saying so.
- **Claude never reads these values.** The three secrets are set by you, from your keyring; an assistant
  session can list the secret *names* and check the published key, and that is the whole of its part.
