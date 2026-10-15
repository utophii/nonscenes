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
- `/nonscene play <name>` - Play a cutscene
- `/nonscene all` - List all cutscenes
- `/nonscene delete <name>` - Delete a cutscene
- `/nonscene showpath <name>` - Show cutscene path
- `/nonscene edit <name> [tolerance]` - Edit a cutscene's keyframes (creates it if it doesn't exist)
- `/nonscene key add [#]` / `move <#>` / `remove <#>` - Add, move or remove a keyframe at your position and view
- `/nonscene key speed <#> <seconds>` - How long the camera takes from keyframe `#` to the next
- `/nonscene key tp <#>` / `key list` - Go to a keyframe / list keyframes
- `/nonscene preview` / `save` / `discard` - Watch, save or throw away the draft

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
