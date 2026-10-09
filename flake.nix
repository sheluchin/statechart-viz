{
  description = "statechart-viz: a dev shell with a pinned, recent Graphviz and babashka";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in {
      # `nix develop`, then `bb test` or `bb readme-images`. flake.lock pins the
      # Graphviz, so every machine draws the same pictures.
      devShells = forAll (pkgs: {
        default = pkgs.mkShell {
          packages = [
            pkgs.graphviz
            pkgs.babashka
            # bb resolves deps.edn dependencies with a JVM, and git deps with git
            pkgs.jdk21_headless
            pkgs.git
          ];
        };
      });
    };
}
