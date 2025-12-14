# WireGuard Tunnel

A Fabric mod that tunnels Minecraft server connections through WireGuard/WARP, providing encrypted and private networking for your gameplay.

## Features

- Tunnels all Minecraft server connections through Cloudflare WARP
- Automatic WARP credential generation and management
- Cross-platform native library support (Linux, Windows, macOS - x86_64 and aarch64)
- Seamless integration with Minecraft's networking stack

## Requirements

- Minecraft 1.19.2
- Fabric Loader 0.16.0+
- Java 17+

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/)
2. Download the mod from [Releases](https://github.com/DreamingCodes/wireguard-tunnel/releases)
3. Place the JAR file in your `mods` folder
4. Launch Minecraft

## How It Works

The mod uses a native Rust library to create a userspace WireGuard tunnel using Cloudflare WARP. When you connect to a Minecraft server, the connection is routed through this encrypted tunnel, providing:

- **Privacy**: Your real IP is hidden from servers
- **Security**: All traffic is encrypted
- **Bypass**: Can help bypass certain network restrictions

## Building from Source

### Prerequisites

- Java 21 JDK
- Rust toolchain with `cross` for cross-compilation
- Docker (for cross-compilation)

### Build

```bash
# Build for current platform only
./gradlew build

# Build with all platform natives (requires cross + Docker)
./gradlew buildAllNativesRelease build
```

## Development

```bash
# Enter the Nix development shell (provides all dependencies)
nix-shell

# Run the Minecraft client with the mod
./gradlew runClient
```

## License

This project is licensed under the GPL-3.0 License - see the [LICENSE](LICENSE) file for details.

## Credits

- [wireguard-netstack](https://crates.io/crates/wireguard-netstack) - Userspace WireGuard implementation
- [warp-wireguard-gen](https://crates.io/crates/warp-wireguard-gen) - WARP credential generation
