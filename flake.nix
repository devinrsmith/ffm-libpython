{
  description = "ffm-proto: embedding CPython 3.12 in Java 25 via FFM (dev shell)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    # A plain Nix function, not a flake: see its README.
    nix-gradle-wrapper = {
      url = "github:devinrsmith/nix-gradle-wrapper";
      flake = false;
    };
  };

  outputs = { self, nixpkgs, nix-gradle-wrapper }:
    let
      # Linux only for now: untested elsewhere, and the libc header setup below is glibc-specific.
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forEachSystem = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forEachSystem (pkgs:
        let
          jdk = pkgs.jdk25;
          python = pkgs.python312;
          # Vendors the Gradle distribution pinned in gradle-wrapper.properties and
          # restricts Gradle's toolchain detection to the JDK this shell provides.
          gradleWrapper = import "${nix-gradle-wrapper}/gradle-wrapper.nix" {
            inherit pkgs;
            wrapperPropertiesFile = ./gradle/wrapper/gradle-wrapper.properties;
            name = "ffm-proto";
            javaHome = jdk.home;
          };
        in
        {
          default = pkgs.mkShell {
            packages = [ jdk pkgs.jextract python ] ++ gradleWrapper.extraBuildInputs;

            # jextract's bundled libclang has no default C library include path
            # under Nix; it honours CPATH, so `./gradlew jextract` just works.
            CPATH = "${pkgs.glibc.dev}/include";

            shellHook = gradleWrapper.isolatedHomeHook + gradleWrapper.warmupHook;
          };
        });
    };
}
