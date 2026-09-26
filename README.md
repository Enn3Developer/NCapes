# NCapes

NCapes lets players upload custom capes to a Minecraft Forge 1.7.10 server. Players with NCapes installed can see each other's capes.

## Use

Put a PNG in `.minecraft/config/ncapes/capes`, then press K in game. K is the default key. Select the file in the Modular UI 2 screen and click "Upload selected". Click "Remove cape" to clear your cape.

The PNG must be 64x32, 128x64, 192x96, or 256x128 pixels and no larger than 24 KiB.

Install NCapes and Modular UI 2 on the server and on each client that should upload or see capes. Modular UI 2 also requires GTNHLib and a provider of `gtnhmixins`, such as UniMixins.

The server stores capes in `<world save>/ncapes/capes/`, keyed by player UUID. It sends saved capes to players when they join.

## Build

Use Java 25 to run the Gradle wrapper:

```sh
./gradlew build
```

The mod jar is written to `build/libs/`.
