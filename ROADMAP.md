## Roadmap

### Blockers

- [x] Remove committed `bin/` from the repo and ignore it
- [x] Default playback to `ASYNC_PACKET` only - do not add a no-PacketEvents / `TICK` fallback
- [x] Remove `LINEAR` interpolation from config, code, tests, and docs
- [x] Remove Redis storage (in-memory, data loss, blocking `KEYS`)
- [x] Sanitize cutscene names (`[a-zA-Z0-9_-]` only) to prevent path traversal
- [x] Fix `showpath` duration (task period is 5 ticks, counter treats it as 1 tick)
- [x] Start async packet playback only after the armor stand is spawned
- [ ] Attach a real plugin JAR to GitHub Releases (CI `shadowJar` artifact)
- [ ] Switch versioning to semver (`0.9.0-alpha` → `1.0.0`) instead of `08-a`

### Cleanup

- [ ] Stop dual-writing every cutscene to both the database and `cutscenes/*.yml`
- [ ] Either wire HikariCP + pool / table-prefix / retry settings, or delete those unused config keys
- [ ] Relocate shaded libraries (SQLite, Hikari, JDBC drivers) or stop bundling unused DB drivers
- [ ] Drop unused shaded deps already provided by Paper (SnakeYAML, Adventure, Commons Lang)
- [ ] Return original cutscene names from `getCutsceneNames()` (not lowercase map keys)
- [ ] Fix mixed-up cancel messages and add the missing `error-occurred` key
- [ ] Use or remove `smooth-rotation` and other unused config options
- [ ] Delete empty `PlayerStateRestorer.onPlayerJoin` or actually restore location after a crash
- [ ] Use `plugin.logger` instead of a raw `Logger.getLogger("nonscenes")`

### Playback / recording correctness

- [x] Remove `LINEAR` interpolation
- [x] Wait for chunk preload before starting playback
- [x] Do not skip camera updates when a chunk is unloaded
- [x] Support multi-world paths in `PathBaker` (do not force the first frame’s world)
- [x] Lock look / cancel interact, inventory, drop, and damage during playback
- [ ] Block Brigadier commands as well, not only `PlayerCommandPreprocessEvent`
- [ ] Make the SQLite connection safe (WAL + single-thread access)
- [ ] Keep MySQL/Postgres credentials out of the JDBC URL; do not default `useSSL=false`

### Needed for 1.0

- [ ] Console / other-player playback: `/nonscene play <name> [player]`
- [ ] Tiny public API (`play`, `stop`, `isPlaying`) for quests and other plugins
- [ ] Timeline events: title, sound, particles, console/player commands
- [ ] Triggers: first join, region, custom command
- [ ] Fade in/out and a proper camera lock (not only an armor stand)
- [ ] Keyframe editor: add / remove / move points, per-segment speed (spline through keyframes, never linear)
- [ ] Pause, skip, rename, copy, export/import a single cutscene file
- [ ] `/nonscene reload`
- [ ] Confirm before delete
- [ ] Tests for storage, name validation, recording duration, path baking, and non-linear interpolation only
- [ ] FastStats

### Later

- [ ] Split `CutsceneManager` (repository / recording / playback)
- [ ] Adventure `Component` + MiniMessage everywhere (drop Bungee `ChatColor`)
- [ ] Folia / region scheduler support
- [ ] Plugin listing
