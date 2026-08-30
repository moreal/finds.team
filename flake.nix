{
  description = "finds.team development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      supportedSystems = [
        "aarch64-darwin"
        "aarch64-linux"
        "x86_64-linux"
      ];
      forAllSystems = nixpkgs.lib.genAttrs supportedSystems;
    in
    {
      devShells = forAllSystems (
        system:
        let
          pkgs = import nixpkgs { inherit system; };
          gradle = pkgs.gradle-packages.mkGradle {
            version = "8.12.1";
            hash = "sha256-jZepeYT2y9K4X+TGCnQ0QKNHVEvxiBgEjmEfUojUbJQ=";
            defaultJava = pkgs.jdk21;
          };
        in
        {
          default = pkgs.mkShell {
            packages = [
              pkgs.jdk21
              gradle
            ];

            JAVA_HOME = pkgs.jdk21.home;
          };
        }
      );
    };
}
