#!/usr/bin/env python3
"""Writes prodserver/win_args_abs.txt: Forge's server launch arguments with absolute library paths, so a
production server can run with any working directory (selfplay.ps1 -Prod)."""
import pathlib

root = pathlib.Path(__file__).resolve().parent.parent / "prodserver"
lib = root.as_posix() + "/libraries"
src = root / "libraries/net/minecraftforge/forge/1.20.1-47.4.28/win_args.txt"
s = src.read_text(encoding="utf-8")
s = s.replace("libraries/", lib + "/").replace("=libraries", "=" + lib)
(root / "win_args_abs.txt").write_text(s, encoding="utf-8")
print("wrote", root / "win_args_abs.txt", s.count(lib), "paths")
