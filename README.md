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

- `/nonscene start <name> <frames>` - Start recording a cutscene
- `/nonscene play <name>` - Play a cutscene
- `/nonscene all` - List all cutscenes
- `/nonscene delete <name>` - Delete a cutscene
- `/nonscene showpath <name>` - Show cutscene path

## Permissions

- `nonscene.use` - Basic usage
- `nonscene.start` - Recording permission
- `nonscene.play` - Playback permission
- `nonscene.delete` - Delete permission
- `nonscene.list` - List permission
- `nonscene.showpath` - Path visualization permission
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
