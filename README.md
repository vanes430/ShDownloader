# ShDownloader

A single-jar, **console-only** downloader plugin for **Spigot/Bukkit** and **Velocity**.  
It behaves like a minimal `wget`: follows redirects, supports a custom request header (User-Agent), and can **throttle download speed** by Mbps.

> ✅ Built for Java 8 target (the resulting JAR is Java 8–compatible).  
> ✅ No NMS usage — works across Minecraft versions that support the platform APIs.  
> ✅ One JAR for both Spigot and Velocity.

---

## Commands (Console only)

```
/shdownload <url> [--name <filename>]
```

- If executed by a player, the plugin will refuse with: **"This command can only be executed from the console."**
- `--name` is optional; if omitted, the filename is inferred from the URL or `Content-Disposition` header.

---

## Configuration (`config.yml`)

```yaml
downloadpath: "./"  # resolve relative to server.jar working directory
customheaderrequest: "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
maxspeed_on_mbps: 20   # throttle speed (Mbps)
```

- **downloadpath**: Destination directory for downloads. The plugin will create it if missing.
- **customheaderrequest**: Request header (User-Agent). If missing/invalid, it **falls back to Chrome on Ubuntu**.
- **maxspeed_on_mbps**: Throttle rate (megabits per second). Must be positive. `0` or negative means the default (20 Mbps).

---

## Build

This project uses Maven and produces **one JAR** with shaded dependencies needed for Velocity config parsing (SnakeYAML).

```bash
mvn -q -e -U clean package
```

- **Java 8 compatible output** (`maven-compiler-plugin` targets 1.8). You can compile on newer JDKs too.
- The JAR is located at: `target/ShDownloader-1.0.0-shaded.jar`.

> **Note**: The Spigot/Velocity APIs are marked `provided` and are not bundled in the jar.

---

## Install

- **Spigot/Paper**: Put the JAR into `plugins/` and restart.
- **Velocity**: Put the JAR into `plugins/` and restart.

A default `config.yml` will be created in the plugin data folder and/or working directory on first run if missing.

---

## How it works

- Follows HTTP redirects (up to 10 hops) using `HttpURLConnection` and the `Location` header, just like `wget`.
- Applies your `customheaderrequest` as the **User-Agent** (falls back to Chrome on Ubuntu if invalid/empty).
- Throttles bandwidth at the stream level to stay under `maxspeed_on_mbps`.
- No NMS; only platform APIs for command registration and console handling.

---

## License

MIT
