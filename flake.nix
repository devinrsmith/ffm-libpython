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

      # The official jextract build from jdk.java.net, not nixpkgs' (built from source
      # against nixpkgs' LLVM). jextract supports only LLVM 13, which the official
      # build bundles; nixpkgs no longer has it, and newer LLVMs both crash jextract
      # (LLVM 21+) and change its output for anonymous records. The tarball is
      # self-contained (its own JVM and libclang), laid out as <home>/bin/jextract
      # and <home>/runtime.
      jextractBinaries = {
        x86_64-linux = { arch = "linux-x64"; hash = "sha256-0MxIGrwa2xb7lRThxeC/wI04wpIovs5mf7UFTOr/qkI="; };
        aarch64-linux = { arch = "linux-aarch64"; hash = "sha256-DiXm9u+gQvh1jq7GWoc4h/0iR/zy4+Itz9fkF5/IsK4="; };
      };
      jextractFor = pkgs:
        let binary = jextractBinaries.${pkgs.stdenv.hostPlatform.system};
        in pkgs.stdenv.mkDerivation {
          pname = "jextract";
          version = "25+2-4";
          src = pkgs.fetchurl {
            url = "https://download.java.net/java/early_access/jextract/25/2/openjdk-25-jextract+2-4_${binary.arch}_bin.tar.gz";
            inherit (binary) hash;
          };
          nativeBuildInputs = [ pkgs.autoPatchelfHook ];
          buildInputs = [ pkgs.zlib pkgs.stdenv.cc.cc.lib ];
          dontBuild = true;
          installPhase = ''
            runHook preInstall
            mkdir -p $out
            cp -r bin runtime $out/
            rm $out/bin/jextract.bat $out/bin/jextract.ps1
            runHook postInstall
          '';
          meta = {
            description = "Generates Java FFM bindings from C headers (official build)";
            homepage = "https://jdk.java.net/jextract/";
            license = pkgs.lib.licenses.gpl2Only;
            sourceProvenance = [ pkgs.lib.sourceTypes.binaryNativeCode ];
            platforms = builtins.attrNames jextractBinaries;
            mainProgram = "jextract";
          };
        };
    in
    {
      packages = forEachSystem (pkgs: { jextract = jextractFor pkgs; });

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
            # python (3.12) first, so it is the shell's python3; the others only
            # supply their headers for `./gradlew jextract`.
            packages = [ jdk (jextractFor pkgs) python pkgs.python313 pkgs.python314 pkgs.python315 ]
              ++ gradleWrapper.extraBuildInputs;

            # jextract's bundled libclang has no default C library include path
            # under Nix; it honours CPATH, so `./gradlew jextract` just works.
            CPATH = "${pkgs.glibc.dev}/include";

            # nixpkgs' Python setup hook exports build-time defaults: PYTHONPATH
            # (python312's site-packages, which would leak into any other Python
            # this shell runs or embeds), PYTHONHASHSEED=0 and PYTHONNOUSERSITE=1.
            shellHook = gradleWrapper.isolatedHomeHook + gradleWrapper.warmupHook + ''
              unset PYTHONPATH PYTHONHASHSEED PYTHONNOUSERSITE
            '';
          };
        });
    };
}
