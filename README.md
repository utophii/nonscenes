# nonscenes

A cutscene plugin for Minecraft 1.20.x - 1.21.x servers.

> **Note**: This plugin has been written on Kottlin. It is currently in alpha development stage but is actively maintained and improved.

## Features

- Record player movements to create cutscenes
- Play back recorded cutscenes
- Visualize cutscene paths with particles
- Multiple database support (SQLite, MySQL, PostgreSQL, MongoDB, Redis)

## Installation

1. Download the latest JAR from releases
2. Place in your server's `plugins/` folder
3. Install LuckPerms (required for permissions)
4. Restart your server

## Building from Source

This project uses Gradle with Kotlin DSL. To build the plugin:

### Prerequisites
- Java 21 or higher
- Gradle 8.5+ (or use the included wrapper)

### Build Commands
```bash
# Clone the repository
git clone https://github.com/utophii/nonscenes.git
cd nonscenes

# Build the plugin
./gradlew build

# The JAR file will be created in build/libs/
```

### Development
```bash
# Run tests
./gradlew test

# Clean build
./gradlew clean build

# Generate IDE files (for IntelliJ IDEA)
./gradlew idea
```

## Commands

- `/nonscene start <name> <seconds>` - Start recording a cutscene
- `/nonscene play <name> [player]` - Play a cutscene
- `/nonscene skip [player]` - Skip a cutscene (viewers can also sneak, see `skip-on-sneak`)
- `/nonscene pause [player]` / `resume [player]` - Freeze and continue the camera and timeline
- `/nonscene rename <name> <new-name>` / `copy <name> <new-name>` - Rename or copy a cutscene
- `/nonscene export <name>` - Write `plugins/nonscenes/exports/<name>.yml`
- `/nonscene import <file> [new-name]` - Load a cutscene from `plugins/nonscenes/exports/`
- `/nonscene all` - List all cutscenes
- `/nonscene delete <name>` - Delete a cutscene
- `/nonscene showpath <name>` - Show cutscene path
- `/nonscene edit <name> [tolerance]` - Edit a cutscene's keyframes (creates it if it doesn't exist)
- `/nonscene key add [#]` / `move <#>` / `remove <#>` - Add, move or remove a keyframe at your position and view
- `/nonscene key speed <#> <seconds>` - How long the camera takes from keyframe `#` to the next
- `/nonscene key tp <#>` / `key list` - Go to a keyframe / list keyframes
- `/nonscene preview` / `save` / `discard` - Watch, save or throw away the draft
- `/nonscene reload` - Reload `config.yml`, `messages.yml` and triggers (storage changes need a restart)

### Skip, pause and sharing

Skipping ends a cutscene as completed: console and player command events that have
not fired yet run right away, titles, sounds and particles are dropped. Pause works
only while the camera moves, not during the fades around it.

Cutscenes live in the configured database. `cutscenes/*.yml` is used only when the
database cannot be reached; with a working database those files are migrated once
and renamed to `*.yml.migrated`. To move a cutscene between servers, export it,
copy the file into the other server's `exports/` folder and import it.

Renaming a cutscene does not change `config.yml`; triggers that still use the old
name are listed after the rename.

### Keyframe editor

`/nonscene edit` opens a private draft. The camera path is drawn only for you: dots
along the spline (spaced 0.1 s apart, so denser dots mean a slower camera), a flame
and a view ray at every keyframe, and a numbered label showing its time and the
duration of the next segment. Nothing is stored until `/nonscene save`.

The camera follows a timed spline through the keyframes and never moves in straight
lines between them. It passes keyframe `#` at exactly its time and does not stop
there, so changing one segment's duration only changes how fast the camera moves
along that part of the path.

Opening a recorded cutscene reduces it to keyframes first. Every keyframe keeps its
original time, so the cutscene's length stays the same. Tolerances are under
`cutscene.editor` in `config.yml`.

## Permissions

- `nonscene.use` - Basic usage
- `nonscene.start` - Recording permission
- `nonscene.play` - Playback permission
- `nonscene.delete` - Delete permission
- `nonscene.list` - List permission
- `nonscene.showpath` - Path visualization permission
- `nonscene.edit` - Keyframe editor permission
- `nonscene.admin` - All permissions

## Configuration

The plugin creates `config.yml` and `messages.yml` in `plugins/nonscenes/` on first run.

### Database Setup

Default is SQLite. For other databases, edit `config.yml`:

```yaml
storage:
  type: MYSQL  # or POSTGRESQL, MONGODB, REDIS
  mysql:
    host: localhost
    port: 3306
    database: minecraft
    username: your_username
    password: your_password
```

## Support

This is an alpha release. Report issues on GitHub.

## License

MIT License
