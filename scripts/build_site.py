"""Builds the GitHub Pages site into _site/ from PRIVACY.md and the Play listing text.

Usage: python scripts/build_site.py  (needs `pip install markdown`)
"""
import html
import pathlib
import shutil

import markdown

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "_site"
LISTING = ROOT / "fastlane/metadata/android/en-US"
TEMPLATE = (ROOT / "site/template.html").read_text(encoding="utf-8")


def page(path: str, title: str, description: str, body_html: str) -> None:
    root = "../" * path.count("/")
    target = OUT / path / "index.html" if path else OUT / "index.html"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        TEMPLATE.format(title=html.escape(title), description=html.escape(description), root=root, content=body_html),
        encoding="utf-8",
    )


def main() -> None:
    shutil.rmtree(OUT, ignore_errors=True)
    OUT.mkdir()
    shutil.copy(LISTING / "images/icon.png", OUT / "icon.png")

    short = (LISTING / "short_description.txt").read_text(encoding="utf-8").strip()

    privacy = markdown.markdown((ROOT / "PRIVACY.md").read_text(encoding="utf-8"))
    page("privacy/", "Privacy policy · Keystore Manager", "Privacy policy for the Keystore Manager Android app.", privacy)

    paragraphs = (LISTING / "full_description.txt").read_text(encoding="utf-8").strip().split("\n\n")
    intro = "".join(f"<p>{html.escape(p)}</p>" for p in paragraphs[:2])
    page(
        "",
        "Keystore Manager",
        short,
        f"<h1>Keystore Manager</h1><p><strong>{html.escape(short)}</strong></p>{intro}"
        '<p><a href="privacy/">Privacy policy</a></p>',
    )
    (OUT / ".nojekyll").touch()
    print("Built", sorted(str(p.relative_to(OUT)) for p in OUT.rglob("*") if p.is_file()))


if __name__ == "__main__":
    main()
