"""Run after :tn-pdf:testDebugUnitTest; requires qpdf, Poppler and PyMuPDF."""
import json
from pathlib import Path
import subprocess
import fitz

root = Path(__file__).resolve().parents[3]
fixtures = Path(__file__).with_name("pdf-fixtures")
results = []
for path in sorted(fixtures.glob("*.pdf")):
    checker = subprocess.run(["qpdf", "--check", str(path)], capture_output=True, text=True)
    poppler = subprocess.run(["pdftoppm", "-f", "1", "-singlefile", "-scale-to", "400", "-png", str(path), str(fixtures / (path.stem + "-poppler"))], capture_output=True, text=True)
    assert checker.returncode == 0, checker.stderr
    assert poppler.returncode == 0, poppler.stderr
    with fitz.open(path) as document:
        page = document[0]
        pixmap = page.get_pixmap(matrix=fitz.Matrix(2, 2))
        pixmap.save(fixtures / (path.stem + "-mupdf.png"))
        annotations = [annotation.type for annotation in page.annots() or []]
        if path.stem == "standard":
            assert any(annotation[1] == "Ink" for annotation in annotations)
            assert page.get_links() and document.get_toc()[0][2] == 2
        if path.stem == "standard-types":
            assert {annotation[1] for annotation in annotations} == {"Text", "Square", "Circle", "Ink"}
        if path.stem == "source-annotations-flattened":
            assert annotations == [] and len(page.get_drawings()) >= 4
        if path.stem == "flattened":
            assert annotations == []
            assert page.get_drawings()
        if path.stem == "source-text-affine":
            assert "Hello PDF" in page.get_text()
            assert page.get_drawings()
            assert not page.get_images()
        if path.stem == "source-rotated-crop":
            assert "Rotated crop" in page.get_text()
            assert page.rect == fitz.Rect(0, 0, 220, 300)
            with fitz.open(root / "modules/tn-pdf/src/androidTest/assets/pdf/rotated-crop.pdf") as original:
                reference = original[0].get_pixmap(matrix=fitz.Matrix(2, 2))
                assert (pixmap.width, pixmap.height) == (reference.width, reference.height)
                differences = [abs(a-b) for a,b in zip(pixmap.samples, reference.samples)]
                mean_difference = sum(differences) / len(differences)
                assert mean_difference < 1, mean_difference
        results.append(dict(file=path.name, pages=len(document), qpdf_exit=checker.returncode,
                            poppler_exit=poppler.returncode, mupdf_pixels=[pixmap.width, pixmap.height],
                            annotations=annotations, links=page.get_links(), outline=document.get_toc(),
                            text=page.get_text(), paths=len(page.get_drawings()), images=len(page.get_images()),
                            rotated_crop_mean_pixel_difference=mean_difference if path.stem == "source-rotated-crop" else None))
report = dict(mupdf_version=fitz.VersionBind, results=results)
(fixtures / "viewer-report.json").write_text(json.dumps(report, indent=2, default=str))
print(json.dumps(report, indent=2, default=str))
