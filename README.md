# finds.team

A service to search jobs easily.

## Development

Enter the Nix development shell from the repository root:

```sh
nix develop
```

The shell provides Java 21 and Gradle 8.12.1. Run the backend build from its
project directory:

```sh
cd backend/finds.team
gradle test
```

The checked-in Gradle wrapper uses the same Gradle version and is also
available through `./gradlew`.
