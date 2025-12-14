{ pkgs ? import <nixpkgs> {} }:

let
  # Graphics and audio libraries needed for Minecraft/LWJGL
  libPath = pkgs.lib.makeLibraryPath [
    # OpenGL
    pkgs.libGL
    pkgs.libGLU
    pkgs.libglvnd
    
    # X11/Xorg
    pkgs.xorg.libX11
    pkgs.xorg.libXext
    pkgs.xorg.libXcursor
    pkgs.xorg.libXrandr
    pkgs.xorg.libXxf86vm
    pkgs.xorg.libXi
    pkgs.xorg.libXrender
    pkgs.xorg.libXtst
    pkgs.xorg.libXinerama
    
    # Wayland
    pkgs.wayland
    pkgs.libxkbcommon
    pkgs.libdecor
    
    # Audio
    pkgs.openal
    pkgs.libpulseaudio
    pkgs.alsa-lib
    pkgs.pipewire
    
    # Other dependencies
    pkgs.flite
    pkgs.udev
    
    # GTK for file dialogs
    pkgs.gtk3
    pkgs.glib
    
    # Additional LWJGL dependencies
    pkgs.stdenv.cc.cc.lib
  ];
in
pkgs.mkShell {
  buildInputs = [
    pkgs.zulu21
    
    # Graphics libraries
    pkgs.libGL
    pkgs.libGLU
    pkgs.libglvnd
    pkgs.mesa-demos
    
    # X11
    pkgs.xorg.libX11
    pkgs.xorg.libXext
    pkgs.xorg.libXcursor
    pkgs.xorg.libXrandr
    pkgs.xorg.libXxf86vm
    pkgs.xorg.libXi
    pkgs.xorg.libXrender
    pkgs.xorg.libXtst
    pkgs.xorg.libXinerama
    
    # Wayland
    pkgs.wayland
    pkgs.libxkbcommon
    pkgs.libdecor
    
    # Audio
    pkgs.openal
    pkgs.libpulseaudio
    pkgs.alsa-lib
    pkgs.pipewire
    
    # Other
    pkgs.flite
    pkgs.udev
    pkgs.gtk3
    
    # C library
    pkgs.stdenv.cc.cc.lib
  ];

  shellHook = ''
    export LD_LIBRARY_PATH="${libPath}:$LD_LIBRARY_PATH"
    export JAVA_HOME="${pkgs.zulu21}"
    
    # Tell LWJGL to use system OpenAL instead of bundled one
    export ALSOFT_DRIVERS=pulse
    
    echo "Nix shell ready for Minecraft mod development"
    echo "Java version: $(java -version 2>&1 | head -n1)"
    echo ""
    echo "Run: ./gradlew runClient"
  '';
}
