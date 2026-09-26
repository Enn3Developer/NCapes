# NCapes

NCapes lets players upload custom capes to a Minecraft Forge 1.7.10 server. Players with NCapes installed can see each other's capes.

## Use

Press Ctrl+K to open the cape menu. Choose a PNG from your computer. NCapes checks it and uploads it as soon as you select it. On clients running lwjgl3ify, you can also drop a PNG onto the open game window. Use "Remove cape" to clear your cape.

The PNG must be 64x32, 128x64, 192x96, or 256x128 pixels and no larger than 24 KiB.

Install NCapes on the server. Clients need Controlling 2.1.7 or newer for the Ctrl+K binding and Modular UI 2 for the menu. They also need Modular UI 2's dependencies, including GTNHLib and a provider of `gtnhmixins` such as UniMixins. The server does not need Controlling or Modular UI 2.

NCapes is optional on either side of a connection. Forge clients without NCapes can join an NCapes server. A client with NCapes can join a server without it, but cannot upload or receive custom capes there. Other installed mods can still impose their own connection requirements.

The server stores capes in `<world save>/ncapes/capes/`, keyed by player UUID. It sends saved capes to players when they join.

## Build

Use Java 25 to run the Gradle wrapper:

```sh
./gradlew build
```

The mod jar is written to `build/libs/`.
