# Contributing

Thanks for your interest. Storyteller Mobile has a single maintainer, so replies can take a while.

## Bugs and ideas

Open an [issue](https://github.com/javimentallab/Storyteller-Mobile/issues). Say what you expected, what happened, and which version and system you use. English or Spanish are both fine.

Security problems go through [SECURITY.md](SECURITY.md), not through issues.

## Changing the code

For anything big, open an issue first so we can agree on the approach.

```sh
./gradlew testDebugUnitTest assembleDebug
```

It needs JDK 21 and the Android SDK with platform 36: set `ANDROID_HOME`, or `sdk.dir` in `local.properties`.

- **Tests:** anything important that you add comes with a test.
- **Dependencies:** a new one needs a permissive licence (MIT, BSD, Apache…). Check it before adding it, and add it to `NOTICE`. After changing dependencies, refresh `app/gradle.lockfile`, the list that Trivy reads: `./gradlew -I gradle/dependency-lock.init.gradle :app:dependencies --write-locks`.
- **Same text as the desktop:** the text preparation has to cut sentences like the [desktop app](https://github.com/javimentallab/Storyteller). `app/src/test/resources/golden.json` comes from its engine, and the tests check it.
- **Private data:** no personal paths, hostnames, IP addresses or tokens in the code, the history or the screenshots.
- **Interface text:** new text goes in every language, and has to fit on screen in all of them. Texts are in `app/src/main/res/values*/strings.xml`, and the store texts in `fastlane/metadata/android/`.
- **Licence:** your contribution is released under the project's [MIT licence](LICENSE).
