# finds.team

A service to search jobs easily.

## Development

Enter the Nix development shell from the repository root:

```sh
nix develop
```

The shell provides Java 25 LTS. The checked-in wrapper provides Gradle 9.7.1,
so builds do not depend on a globally installed Gradle. Run the complete backend
build from its project directory:

```sh
cd backend
./gradlew check
```

Run the pure domain tests alone while working on business rules:

```sh
./gradlew :domain:test
```

The backend is organized as six Gradle modules with inward-only dependencies:

- `domain`: immutable values and pure business policies;
- `application`: use cases and ports;
- `adapter-source`: web protocols and provider parsers;
- `adapter-persistence`: PostgreSQL, Flyway, and jOOQ;
- `adapter-graphql`: schema and transport mapping;
- `bootstrap`: Spring Boot wiring, scheduling, and configuration.

The dependency direction is `bootstrap -> adapters -> application -> domain`.
The legacy JPA/H2 prototype temporarily lives in `bootstrap` while its complete
replacement is built and verified.
