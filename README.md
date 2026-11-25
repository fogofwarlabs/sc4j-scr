<h1 align="center">
  <img src="docs/branding/icon.svg" alt="SC:R logo" width="160">
  <br>
  SC:R
</h1>

<p align="center">StarCraft: Remastered as an SC4J mod.</p>

<p align="center">
  <a href="https://github.com/fogofwarlabs/sc4j-scr/releases"><img src="https://img.shields.io/github/v/release/fogofwarlabs/sc4j-scr" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0"></a>
</p>

<p align="center"><b>English</b> | <a href="README.ko.md">한국어</a></p>

---

An [SC4J](https://github.com/fogofwarlabs/sc4j) mod with the id `scr` and the jar `scr.jar`. It reads the game data from the player's own StarCraft: Remastered installation.

Supported game build: StarCraft: Remastered 1.23.10.

- [Layout](#layout)
- [Game data](#game-data)
- [Build and run](#build-and-run)
- [Tests](#tests)
- [Contributing](#contributing)
- [Releases](#releases)
- [Licence](#licence)

## Layout

| Path | Contents |
|---|---|
| `scr-data` | data files, content tables, extension API for added units |
| `scr-game` | units, orders, combat, computer players, triggers, replays, saved games |
| `scr-render` | game screen renderer |
| `scr-net` | LAN and Beacon.Link play |
| `scr-client` | client: menus, game screen, audio, replay playback |
| `scr-benchmark` | JMH benchmarks (not in `scr.jar`) |
| `sc-recorder` | frame recorder of the native game, [SCRECBIN format](docs/sc-recorder-format.md) (not in `scr.jar`) |
| `mod.properties` | modules and compiler arguments of `scr.jar` |
| `build-logic/` | Gradle conventions |
| `docs/` | SCRECBIN format, [launcher art](docs/branding/README.md) |
| `licenses/` | third-party licence texts |

## Game data

The Gradle property `sc4j.data` names the StarCraft: Remastered folder: the one with `.build.info` and `x86_64/StarCraft.exe` (Windows) or `x86_64/StarCraft.app` (macOS). Set it in `gradle.properties` of the Gradle user home (`%USERPROFILE%\.gradle` on Windows, `~/.gradle` on Linux and macOS) as `sc4j.data=<folder>` with forward slashes, with `-Psc4j.data=<folder>`, or in CI with `ORG_GRADLE_PROJECT_sc4j.data`.

StarCraft: Remastered has no Linux version. On Linux, use the folder of an install made with Wine, Proton or Lutris, or a copy of an install folder (build 1.23.10). SC4J reads the folder itself and does not need Wine to run.

`installMod` writes it as the shared game folder of an install that has none. The launcher looks for the folder where the system usually has the game, or asks for it in the mod's settings.

## Build and run

Needs JDK 25. Commands run from the repository root. The commands below are for Windows; on Linux and macOS use `./gradlew` instead of `.\gradlew.bat` and `/` in paths.

```powershell
git clone https://github.com/fogofwarlabs/sc4j-scr.git
cd sc4j-scr
.\gradlew.bat build
```

```sh
git clone https://github.com/fogofwarlabs/sc4j-scr.git
cd sc4j-scr
./gradlew build
```

The engine version is `sc4jVersion` in `gradle.properties`, taken from Maven Central. An engine checkout at `../sc4j` (or `-Psc4j.engine.dir=<folder>`) is used instead; `-Psc4j.engine.local=false` turns that off.

| Command | What it does |
|---|---|
| `.\gradlew.bat jar` | `build/libs/scr.jar` |
| `..\sc4j\gradlew.bat -p ..\sc4j devInstall` | engine development install `../sc4j/build/dev-install/` |
| `.\gradlew.bat installMod` | copies `scr.jar` into `mods/` of that install, or of `-Psc4j.install=<folder>` |
| `.\gradlew.bat :run` | runs `scr.jar` with the user data of that install |
| `..\sc4j\gradlew.bat -p ..\sc4j runDevLauncher` | opens the launcher of the development install |

## Tests

| Command | Runs | Needs |
|---|---|---|
| `.\gradlew.bat build` | unit tests and checks | nothing |
| `.\gradlew.bat integrationTest` | `@RequiresInstallation` tests, GPU tests | `sc4j.data`, a GPU (`-Psc4j.render.smoke=false` skips the GPU tests) |
| `.\gradlew.bat corpusTest` | `@CorpusTest` long runs | `sc4j.data` and the folders below; a test without its folder is skipped |

| Property | Folder |
|---|---|
| `sc4j.replays` | replay corpus root (`sc4j.replays.limit`: files to parse) |
| `sc4j.maps` | maps with large EUD maps |
| `sc4j.corpus` | replays the corpus survey plays |

## Contributing

See the engine's [contributing guide](https://github.com/fogofwarlabs/sc4j/blob/main/CONTRIBUTING.md). Run `integrationTest` when you have the installation. Never commit game data or anything extracted from it.

## Releases

`develop` sets `version=X.Y.Z` and a released `sc4jVersion` in `gradle.properties`; a pull request from `develop` into `main` titled ``Release version `X.Y.Z` `` is merged with a merge commit. The push to `main` creates the GitHub release `vX.Y.Z` with `scr.jar` and `scr.jar.sha256`.

## Licence

[Apache License 2.0](LICENSE). Third-party material: [NOTICE](NOTICE), [`licenses/`](licenses/).

StarCraft is a trademark of Blizzard Entertainment, Inc. SC4J is not affiliated with or endorsed by Blizzard Entertainment.
