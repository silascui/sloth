#!/usr/bin/env python3
"""Package verified sources and the Gradle-generated local Maven repository."""
from pathlib import Path
from hashlib import sha256
from io import BytesIO
import json
import shutil
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
def gradle_property(name):
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    raise ValueError(f"Missing Gradle property: {name}")

VERSION = gradle_property("VERSION_NAME")
GROUP_PATH = gradle_property("GROUP").replace(".", "/")
EXCLUDED = {".git", ".gradle", ".kotlin", ".idea", "build", "dist", "__pycache__"}

def digest(data):
    return sha256(data).hexdigest()

def source_files():
    for path in sorted(ROOT.rglob("*")):
        relative = path.relative_to(ROOT)
        if not path.is_file() or any(part in EXCLUDED for part in relative.parts):
            continue
        # Keep original device evidence in Git, but omit logs containing local
        # paths and historical package names from the source distribution.
        if relative.parts[:2] == ("docs", "verification") and path.suffix == ".log":
            continue
        if path.name in {"local.properties", ".DS_Store"} or path.suffix in {".iml", ".pyc", ".asc", ".gpg", ".key", ".p12", ".jks", ".keystore"} or path.name.startswith(".env"):
            continue
        yield path, relative

def main():
    repository = ROOT / "player/build/bundle-repository"
    version = repository / f"{GROUP_PATH}/sloth/{VERSION}"
    aar = version / f"sloth-{VERSION}.aar"
    ui_version = repository / f"{GROUP_PATH}/sloth-ui/{VERSION}"
    ui_aar = ui_version / f"sloth-ui-{VERSION}.aar"
    for folder, artifact in ((version, "sloth"), (ui_version, "sloth-ui")):
        for extension in ("aar", "pom", "module", "sources.jar", "javadoc.jar"):
            suffix = f"-{extension}" if extension in {"sources.jar", "javadoc.jar"} else f".{extension}"
            assert (folder / f"{artifact}-{VERSION}{suffix}").is_file(), "Publish both player and player-ui to the bundle repository first"
    with zipfile.ZipFile(ui_aar) as binary:
        with zipfile.ZipFile(BytesIO(binary.read("classes.jar"))) as classes:
            names = classes.namelist()
            expected = {"VideoFeedBinding", "PlayerControlView", "FullScreenCoordinator", "VideoViewHolder"}
            assert {f"io/github/silascui/sloth/ui/{name}.class" for name in expected}.issubset(names), "Missing public UI control API"
            assert not any("BindingTestActivity" in name for name in names), "Debug host in release UI AAR"
    ui_metadata = (ui_version / f"sloth-ui-{VERSION}.pom").read_text()
    assert "<artifactId>sloth</artifactId>" in ui_metadata, "UI artifact must depend on the core library"
    with zipfile.ZipFile(aar) as binary:
        with zipfile.ZipFile(BytesIO(binary.read("classes.jar"))) as classes:
            names = classes.namelist()
            assert not any("FrameworkTestActivity" in name for name in names), "Debug host in release AAR"
            assert any("META-INF/sloth/Apache-2.0.txt" == name for name in names)
            expected = {"VideoPlaybackRuntime", "PlaybackController", "PlaybackState", "PlaybackStatus"}
            assert {f"io/github/silascui/sloth/{name}.class" for name in expected}.issubset(names), "Missing public playback API"
    # Validate public dependency groups without embedding legacy private names.
    for folder, artifact in ((version, "sloth"), (ui_version, "sloth-ui")):
        pom = ET.fromstring((folder / f"{artifact}-{VERSION}.pom").read_text())
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        groups = {node.text for node in pom.findall("m:dependencies/m:dependency/m:groupId", namespace)}
        module = json.loads((folder / f"{artifact}-{VERSION}.module").read_text())
        groups.update(dependency["group"] for variant in module["variants"] for dependency in variant.get("dependencies", []))
        assert all(group and (group.startswith("androidx.") or group.startswith("org.jetbrains.") or group == gradle_property("GROUP")) for group in groups), "Unexpected dependency group"
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    source = dist / f"sloth-{VERSION}-source.zip"
    with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as out:
        for path, relative in source_files():
            out.write(path, f"sloth/{relative.as_posix()}")
    maven = dist / f"sloth-{VERSION}-maven.zip"
    with zipfile.ZipFile(maven, "w", zipfile.ZIP_DEFLATED) as out:
        for artifact in ("sloth", "sloth-ui"):
            for path in sorted((repository / GROUP_PATH / artifact / VERSION).rglob("*")):
                if path.is_file():
                    out.write(path, f"maven/{path.relative_to(repository).as_posix()}")
    standalone = dist / f"sloth-{VERSION}.aar"
    shutil.copyfile(aar, standalone)
    ui_standalone = dist / f"sloth-ui-{VERSION}.aar"
    shutil.copyfile(ui_aar, ui_standalone)
    (dist / "SHA256SUMS").write_text("".join(f"{digest(p.read_bytes())}  {p.name}\n" for p in (source, maven, standalone, ui_standalone)))
    print(json.dumps({p.name: p.stat().st_size for p in (source, maven, standalone, ui_standalone)}, indent=2))

if __name__ == "__main__":
    main()
