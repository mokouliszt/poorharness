# Third-party notices

PoorHarness's own code is MIT-licensed (see `LICENSE`). The APK also contains the
components below, each under its own license. None of them is modified.

## In the on-device runtime

| Component | Version | License | Source |
|---|---|---|---|
| PRoot (termux fork) | v5.1.107.78 | GPL-2.0-or-later | https://github.com/termux/proot |
| talloc (linked into PRoot) | 2.4.3 | LGPL-3.0-or-later | https://www.samba.org/ftp/talloc/ |
| Ubuntu Base 24.04 and packages | 24.04.5 | various (see below) | https://cdimage.ubuntu.com/ubuntu-base/ |
| opencode | 1.19.0 | MIT | https://github.com/anomalyco/opencode |

PRoot and talloc are built from the exact sources named in
`runtime/versions.env` with `runtime/proot/build.sh`; anyone can rebuild them
and replace `libproot.so`/`libproot-loader.so`. The corresponding source for
these GPL/LGPL components is available at the URLs above at the pinned tag and
commit, and on request from the PoorHarness maintainers.

Ubuntu packages keep their copyright files in the runtime image under
`/usr/share/doc/<package>/copyright`. Installed packages: ca-certificates, curl,
git, ripgrep, less, procps, unzip, zip, xz-utils, jq, file, python3,
openssh-client and their dependencies.

## Downloaded at run time (not part of the APK)

- `tensorlake-opencode` (Apache-2.0,
  https://github.com/tensorlakeai/opencode-tensorlake-plugin) and its
  dependencies: opencode installs them from npm on the device on its first start.

## In the Android app

| Component | License |
|---|---|
| AndroidX Core, Activity, WebKit, Browser | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | Apache-2.0 |
| OkHttp, okhttp-sse | Apache-2.0 |
| XZ for Java (org.tukaani:xz) | 0BSD |
| SnakeYAML Engine (org.snakeyaml:snakeyaml-engine) | Apache-2.0 |

## In the web UI

| Component | License |
|---|---|
| React, React DOM | MIT |
| Radix UI (Dialog, Switch) | MIT |
| react-markdown, remark-gfm | MIT |
| zustand | MIT |
| clsx | MIT |
| tailwind-merge | MIT |
| Lucide icons | ISC |
| IBM Plex Sans, IBM Plex Mono (via Fontsource) | SIL Open Font License 1.1 |

## Approach credit

The idea of shipping PRoot as a native library and running a Linux userland
from it follows dphmoblie/deepseek-harness-android (MIT). No code from that
project, or from its AGPL-licensed dependencies, is included.
