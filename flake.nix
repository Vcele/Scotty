{
  description = "Scotty – Miracast + AirPlay sender for Android (Rust core)";
  inputs = {
    nixpkgs.url = "nixpkgs";
    fenix = {
      url = "github:nix-community/fenix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };
  outputs = { self, nixpkgs, fenix }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = { allowUnfree = true; android_sdk.accept_license = true; };
      };
      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "36" ];
        buildToolsVersions = [ "36.0.0" ];
        includeEmulator = false;
        includeNDK = true;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";
      fx = fenix.packages.${system};
      rust = fx.combine [
        fx.stable.cargo
        fx.stable.rustc
        fx.stable.clippy
        fx.stable.rustfmt
        fx.targets.aarch64-linux-android.stable.rust-std
        fx.targets.x86_64-linux-android.stable.rust-std
      ];
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          pkgs.jdk17 pkgs.gradle android.platform-tools
          rust pkgs.cargo-ndk
          # local test receivers / tooling
          pkgs.shairport-sync pkgs.ffmpeg
        ];
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        ANDROID_NDK_HOME = "${sdk}/ndk-bundle";
        JAVA_HOME = pkgs.jdk17.home;
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/36.0.0/aapt2";
      };
    };
}
