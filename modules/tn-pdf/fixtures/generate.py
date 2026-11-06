from pathlib import Path
import hashlib
import json
import fitz

root = Path(__file__).resolve().parents[1] / 'src/androidTest/assets/pdf'
root.mkdir(parents=True, exist_ok=True)
manifest = []

def save(name, doc, expected, **options):
    path = root / f'{name}.pdf'
    doc.save(path, **options)
    doc.close()
    manifest.append({'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), **expected})

doc = fitz.open()
page = doc.new_page(width=200, height=200)
page.insert_text((20, 40), 'Hello PDF', fontsize=16)
page.draw_rect(fitz.Rect(20, 60, 180, 180), color=(0, 0, 1), fill=(1, 1, 0))
page.insert_link({'kind': fitz.LINK_URI, 'from': fitz.Rect(20, 20, 110, 45), 'uri': 'https://example.org/'})
doc.set_toc([[1, 'First page', 1]])
save('text', doc, {'pages': 1, 'text': 'Hello PDF', 'links': 1, 'outline': 1})

doc = fitz.open()
page = doc.new_page(width=200, height=200)
pix = fitz.Pixmap(fitz.csRGB, 2, 2, bytes([255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 255]), False)
page.insert_image(page.rect, pixmap=pix)
save('scan', doc, {'pages': 1, 'text': ''})

doc = fitz.open()
page = doc.new_page(width=400, height=300)
page.insert_text((60, 80), 'Rotated crop')
page.set_cropbox(fitz.Rect(40, 30, 340, 250))
page.set_rotation(90)
save('rotated-crop', doc, {'pages': 1, 'rotation': 90, 'text': 'Rotated crop'})

doc = fitz.open()
page = doc.new_page(width=200, height=200)
page.insert_text((20, 40), 'Secret PDF')
save('encrypted', doc, {'pages': 1, 'password': 'fixture-password', 'text': 'Secret PDF'}, encryption=fitz.PDF_ENCRYPT_AES_256, owner_pw='fixture-owner', user_pw='fixture-password')

doc = fitz.open()
page = doc.new_page(width=1000001, height=200)
save('huge', doc, {'pages': 1, 'rejected_dimensions': True})

doc = fitz.open()
page = doc.new_page(width=200, height=200)
page.insert_text((20, 40), 'Fallback font')
font_xref = page.get_fonts()[0][0]
doc.update_object(font_xref, doc.xref_object(font_xref).replace('/Helvetica', '/MissingFixtureFont'))
save('missing-font', doc, {'pages': 1, 'text': 'Fallback font'})

doc = fitz.open()
doc.new_page(width=200, height=200)
doc.new_page(width=200, height=200)
page = doc[0]
page.insert_text((20, 40), 'Go to second page')
page.insert_link({'kind': fitz.LINK_GOTO, 'from': fitz.Rect(20, 20, 160, 45), 'page': 1, 'to': fitz.Point(0, 0)})
doc.set_toc([[1, 'Root', 1], [2, 'Second', 2]])
save('internal-links', doc, {'pages': 2, 'text': 'Go to second page', 'internal_target': 1, 'outline': 2})

doc = fitz.open()
doc.new_page(width=200, height=200)
doc.new_page(width=200, height=200)
page = doc[0]
page.insert_text((20, 40), 'Named destination')
page.insert_link({'kind': fitz.LINK_GOTO, 'from': fitz.Rect(20, 20, 160, 45), 'page': 1, 'to': fitz.Point(0, 0)})
page = doc.reload_page(page)
target = doc[1].xref
doc.xref_set_key(doc.pdf_catalog(), 'Names', f'<< /Dests << /Names [(ChapterTwo) [{target} 0 R /XYZ 0 200 0]] >> >>')
doc.xref_set_key(page.get_links()[0]['xref'], 'A', '<< /S /GoTo /D (ChapterTwo) >>')
doc.set_toc([[1, 'ChapterTwo', 2]])
save('named-links', doc, {'pages': 2, 'text': 'Named destination', 'named_destination': 'ChapterTwo', 'internal_target': 1, 'outline': 1})

broken = root / 'damaged.pdf'
broken.write_bytes(b'%PDF-1.7\ntruncated object and xref\n')
manifest.append({'file': broken.name, 'sha256': hashlib.sha256(broken.read_bytes()).hexdigest(), 'rejected': True})
(root / 'manifest.json').write_text(json.dumps({'generator': 'PyMuPDF ' + fitz.VersionBind, 'fixtures': manifest}, indent=2) + '\n')
