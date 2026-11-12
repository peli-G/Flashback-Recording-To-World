The converted world will be in your saves folder in Minecraft.

This Mod adds a button to the Minecraft Title Screen which allows you to convert a Flashback Recording into a World.

The Mod Converts:
 - Blocks
 - Block Entites
 - Entities
 - Biomes

Download on [Github](https://github.com/peli-G/Flashback-Recording-To-World) & [Modrinth](https://modrinth.com/mod/fbr-to-world).

> [!NOTE]
> This project contains AI-generated code. See [AI-USAGE.md](AI-USAGE.md) for more information.

# Gamerule Config
Press the Gamerule Options to configure your default gamerules for the converted world.

# Fabric API
Fabric API is required for this mod to work. You can download it [here](https://modrinth.com/mod/fabric-api).

# Building
One branch builds every supported Minecraft version. Each version has its own folder in `versions/` with its Minecraft, Fabric API and Loader versions.

 - `./gradlew build` builds every version.
 - `./gradlew :mc-1.21.11:build` or `./gradlew :mc-26.2:build` builds one version.
 - The jars are in `versions/<version>/build/libs`.

Code shared by all versions lives in `src/main`. It is written against the 1.21.11 names, and the 26.x build rewrites those names with the rules in `versions/26.2/source-transform.rules`. Code that has to differ per version lives in `src/mc1_21` and `src/mc26`. Building 26.x needs Java 25.

# Images
<img width="2560" height="1440" alt="517841422-1a0f8344-ac26-4c8d-918b-1615f5816d4e" src="https://github.com/user-attachments/assets/fb64371f-bd16-47b7-b95a-4dd5126a553b" />
