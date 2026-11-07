package inkspace.pdf.android

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.OutputStream

/** Source streams and Android Canvas overlays are imported as PDF forms, never page bitmaps. */
class PdfCompositionWriter : AutoCloseable {
    companion object {
        fun initialize(context: android.content.Context) { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext) }
    }
    private val document = PDDocument(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMainMemoryOnly(64L*1024*1024))
    private var sourceBytes = 0L
    private val sources = LinkedHashMap<File, PDDocument>()
    private val flattened = HashSet<Pair<File,Int>>()
    private data class Mapping(val page: PDPage,val form: PDFormXObject,val x: Float,val y: Float,val width: Float,val height: Float,val transform: FloatArray?)
    private val mappings = LinkedHashMap<Pair<File, Int>, Mapping>()
    private data class Link(val page: Int,val rect: FloatArray,val target: Int?,val url: String?)
    private val links = mutableListOf<Link>()
    fun link(page: Int, rect: FloatArray, target: Int?, url: String?) { links += Link(page,rect,target,url) }
    private val warnings = linkedSetOf<String>()
    val fallbackReport: List<String> get() = warnings.toList()

    fun page(width: Float, height: Float, backgroundColor: Int = -1): Int {
        val page=PDPage(PDRectangle(width,height)); document.addPage(page)
        PDPageContentStream(document,page).use { stream ->
            stream.setNonStrokingColor((backgroundColor ushr 16) and 255,(backgroundColor ushr 8) and 255,backgroundColor and 255)
            stream.addRect(0f,0f,width,height);stream.fill()
        }
        return document.numberOfPages-1
    }

    fun source(pageIndex: Int, file: File, index: Int, x: Float, y: Float, width: Float, height: Float,
        opacity: Float = 1f, mapNavigation: Boolean = false, transform: FloatArray? = null) {
        val page = document.getPage(pageIndex)
        val source = sources.getOrPut(file) {
            require(file.length() <= 128L*1024*1024-sourceBytes) { "PDF composition source budget exceeded; choose a smaller page range" }
            sourceBytes += file.length()
            PDDocument.load(file,com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMainMemoryOnly(64L*1024*1024))
        }
        require(index in 0 until source.numberOfPages)
        val sourcePage = source.getPage(index)
        if(flattened.add(file to index)) {
            for(annotation in sourcePage.annotations.filter { it !is PDAnnotationLink && !it.isHidden && !it.isInvisible }) {
                if(annotation.normalAppearanceStream == null) runCatching { annotation.constructAppearances(source) }
                val appearance = annotation.normalAppearanceStream
                val rect = annotation.rectangle
                if(appearance != null && rect != null) drawIn(source,sourcePage,appearance,rect.lowerLeftX,rect.lowerLeftY,rect.width,rect.height)
                else warnings += "Source ${annotation.subtype} annotation has no supported appearance"
            }
        }
        val form = LayerUtility(document).importPageAsForm(source,index)
        draw(page,form,x,y,width,height,opacity,transform)
        if (mapNavigation) {
            val previous=mappings.putIfAbsent(file to index,Mapping(page,form,x,y,width,height,transform))
            if(previous != null && previous.page !== page) warnings += "Repeated source page navigation targets the first exported copy"
        } else if(sourcePage.annotations.any { it is PDAnnotationLink } || source.documentCatalog.documentOutline != null)
            warnings += "Source navigation omitted where region/placement page mapping is unavailable"
        if (source.getPage(index).annotations.any { it !is PDAnnotationLink })
            warnings += "Source annotations retained as their source appearance only; editable source annotation conversion is unsupported"
    }

    fun overlay(pageIndex: Int, bytes: ByteArray, subtype: String? = null, contents: String = "", ink: FloatArray? = null,
        x: Float = 0f, y: Float = 0f, width: Float? = null, height: Float? = null) {
        val page = document.getPage(pageIndex)
        PDDocument.load(bytes).use { overlay ->
            val form = LayerUtility(document).importPageAsForm(overlay,0)
            if (subtype == null) draw(page,form,x,y,width ?: page.mediaBox.width,height ?: page.mediaBox.height)
            else {
                val dictionary = COSDictionary()
                dictionary.setName(COSName.TYPE,"Annot")
                dictionary.setName(COSName.SUBTYPE,subtype)
                val annotation = PDAnnotation.createAnnotation(dictionary)
                annotation.rectangle = PDRectangle(x,y,width ?: page.mediaBox.width,height ?: page.mediaBox.height)
                annotation.contents = contents
                annotation.isPrinted = true
                val appearance = PDAppearanceDictionary()
                val appearanceStream = PDAppearanceStream(form.cosObject)
                appearance.setNormalAppearance(appearanceStream)
                annotation.appearance = appearance
                if (ink != null) {
                    val points = COSArray(); ink.forEach { points.add(COSFloat(it)) }
                    val lines = COSArray(); lines.add(points); dictionary.setItem(COSName.getPDFName("InkList"),lines)
                }
                page.annotations.add(annotation)
            }
        }
    }

    fun fallback(message: String) { warnings += message }

    private fun drawIn(owner: PDDocument, page: PDPage, form: PDFormXObject, x: Float, y: Float, width: Float, height: Float) {
        PDPageContentStream(owner,page,PDPageContentStream.AppendMode.APPEND,true,true).use { stream ->
            val box = transformedBox(form)
            stream.transform(Matrix(width/box.width,0f,0f,height/box.height,x-box.lowerLeftX*width/box.width,y-box.lowerLeftY*height/box.height))
            stream.drawForm(form)
        }
    }

    private fun draw(page: PDPage, form: PDFormXObject, x: Float, y: Float, width: Float, height: Float, opacity: Float = 1f, transform: FloatArray? = null) {
        PDPageContentStream(document,page,PDPageContentStream.AppendMode.APPEND,true,true).use { stream ->
            stream.saveGraphicsState()
            if(opacity < 1f) {
                val state = com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState()
                state.nonStrokingAlphaConstant = opacity; state.strokingAlphaConstant = opacity
                stream.setGraphicsStateParameters(state)
            }
            if(transform != null) stream.transform(Matrix(transform[0],transform[1],transform[2],transform[3],transform[4],transform[5]))
            val box = transformedBox(form)
            stream.transform(Matrix(width/box.width,0f,0f,height/box.height,x-box.lowerLeftX*width/box.width,y-box.lowerLeftY*height/box.height))
            stream.drawForm(form)
            stream.restoreGraphicsState()
        }
    }

    fun write(output: OutputStream) {
        for(entry in links) {
            val annotation=PDAnnotationLink(); annotation.rectangle=PDRectangle(entry.rect[0],entry.rect[1],entry.rect[2],entry.rect[3])
            if(entry.target != null && entry.target in 0 until document.numberOfPages)
                annotation.destination=PDPageFitDestination().also { it.page=document.getPage(entry.target) }
            else if(entry.url != null) annotation.action=com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI().also { it.uri=entry.url }
            else { warnings += "Notebook link target outside exported pages"; continue }
            document.getPage(entry.page).annotations.add(annotation)
        }
        val root = PDDocumentOutline()
        for ((file,source) in sources) {
            for (index in 0 until source.numberOfPages) {
                val mapping = mappings[file to index] ?: continue
                val target = mapping.page
                val sourcePage = source.getPage(index)
                for (annotation in sourcePage.annotations.filterIsInstance<PDAnnotationLink>()) {
                    val link = PDAnnotationLink()
                    val rect = annotation.rectangle ?: continue
                    val box = transformedBox(mapping.form)
                    val projected = transformRect(rect,mapping.form.matrix)
                    val placed = PDRectangle(mapping.x+(projected.lowerLeftX-box.lowerLeftX)*mapping.width/box.width,
                        mapping.y+(projected.lowerLeftY-box.lowerLeftY)*mapping.height/box.height,
                        projected.width*mapping.width/box.width,projected.height*mapping.height/box.height)
                    link.rectangle = mapping.transform?.let { m -> transformRect(placed,Matrix(m[0],m[1],m[2],m[3],m[4],m[5])) } ?: placed
                    val destination = annotation.destination ?: (annotation.action as? PDActionGoTo)?.destination
                    if(destination != null) {
                        val destPage = resolve(source,destination)
                        val mapped = destPage?.let { mappings[file to source.pages.indexOf(it)] }
                        if(mapped == null) { warnings += "Link destination omitted because its page is outside the export"; continue }
                        link.destination = PDPageFitDestination().also { it.page = mapped.page }
                    } else if(annotation.action is com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI) {
                        link.action = annotation.action
                    } else { warnings += "Unsupported PDF link action omitted"; continue }
                    target.annotations.add(link)
                }
            }
            fun copyOutline(first: PDOutlineItem?, parent: PDOutlineNode) {
                var item = first
                while(item != null) {
                    val current = item
                    val destination = runCatching { current.findDestinationPage(source) }.getOrNull()
                    val mapped = destination?.let { mappings[file to source.pages.indexOf(it)] }
                    if(mapped != null) {
                        val copy = PDOutlineItem(); copy.title = current.title
                        copy.destination = PDPageFitDestination().also { it.page = mapped.page }
                        parent.addLast(copy); copyOutline(current.firstChild,copy)
                    } else copyOutline(current.firstChild,parent)
                    item = current.nextSibling
                }
            }
            copyOutline(source.documentCatalog.documentOutline?.firstChild,root)
        }
        if(root.firstChild != null) document.documentCatalog.documentOutline = root
        document.save(output)
    }

    private fun transformedBox(form: PDFormXObject): PDRectangle = transformRect(form.bBox,form.matrix)
    private fun transformRect(box: PDRectangle, matrix: Matrix): PDRectangle {
        val points = listOf(box.lowerLeftX to box.lowerLeftY,box.upperRightX to box.lowerLeftY,
            box.lowerLeftX to box.upperRightY,box.upperRightX to box.upperRightY).map { (x,y) ->
            (x*matrix.scaleX+y*matrix.shearX+matrix.translateX) to (x*matrix.shearY+y*matrix.scaleY+matrix.translateY)
        }
        val left=points.minOf { it.first }; val bottom=points.minOf { it.second }
        return PDRectangle(left,bottom,points.maxOf { it.first }-left,points.maxOf { it.second }-bottom)
    }

    private fun resolve(source: PDDocument, destination: PDDestination): PDPage? {
        val actual = if(destination is PDNamedDestination) source.documentCatalog.findNamedDestinationPage(destination) else destination
        return (actual as? PDPageDestination)?.let { it.page ?: it.retrievePageNumber().takeIf { n -> n in 0 until source.numberOfPages }?.let(source::getPage) }
    }
    override fun close() { try { document.close() } finally { sources.values.forEach { it.close() } } }
}
