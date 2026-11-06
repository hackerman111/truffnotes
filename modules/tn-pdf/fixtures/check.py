"""Host corpus integrity check; this does not exercise the Android PDFium binding."""

from pathlib import Path
import hashlib
import json
import fitz

root = Path(__file__).resolve().parents[1] / "src/androidTest/assets/pdf"
manifest = json.loads((root / "manifest.json").read_text())
for fixture in manifest["fixtures"]:
    path = root / fixture["file"]
    assert hashlib.sha256(path.read_bytes()).hexdigest() == fixture["sha256"], path.name
    try:
        document = fitz.open(path)
    except Exception:
        assert fixture.get("rejected"), path.name
        print(path.name, "rejected as expected")
        continue
    with document:
        assert not fixture.get("rejected"), path.name
        if "password" in fixture:
            assert document.needs_pass
            assert document.authenticate(fixture["password"])
        assert len(document) == fixture["pages"], path.name
        if "text" in fixture:
            assert fixture["text"] in document[0].get_text(), path.name
        if "internal_target" in fixture:
            assert document[0].get_links()[0]["page"] == fixture["internal_target"], path.name
        print(path.name, len(document), "pages, hash and metadata verified")
