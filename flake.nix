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
          corepackPnpm = pkgs.writeShellScriptBin "pnpm" ''
            exec ${pkgs.nodejs_24}/bin/corepack pnpm "$@"
          '';
        in
        {
          default = pkgs.mkShell {
            packages = [
              pkgs.jdk25
              pkgs.nodejs_24
              # nixpkgs currently resolves pkgs.pnpm to 11.22.0. Keep pnpm's
              # version authority in package.json and invoke it through Corepack.
              corepackPnpm
            ];

            JAVA_HOME = pkgs.jdk25.home;
          };
        }
      );
    };
}
