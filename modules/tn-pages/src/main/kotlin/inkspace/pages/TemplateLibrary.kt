package inkspace.pages

import inkspace.core.pages.*
import inkspace.core.codec.CoreCodec
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable

@Serializable data class PageDefaults(val widthPt: Double=595.28,val heightPt:Double=841.89,val metadata:PageMetadata=PageMetadata()) {
    init { require(widthPt.isFinite() && heightPt.isFinite() && widthPt>0 && heightPt>0 && widthPt<=1000000 && heightPt<=1000000) }
}
@Serializable private data class Templates(val entries:List<TemplateDescriptor> = emptyList(),val defaults:PageDefaults=PageDefaults())
class TemplateLibrary(private val file:File) {
    private var state = if(file.exists()) { require(file.length()<=8388608); CoreCodec.decodeFromJson<Templates>(file.readText()).getOrNull() ?: throw IllegalArgumentException("Invalid template library") } else Templates()
    @Synchronized fun list():List<TemplateDescriptor> = java.util.List.copyOf(state.entries)
    @Synchronized fun defaults():PageDefaults = state.defaults.copy(metadata=state.defaults.metadata.frozen())
    @Synchronized fun put(template:TemplateDescriptor) {
        require(state.entries.size<4096 || state.entries.any { it.id==template.id })
        val previous=state.entries.find { it.id==template.id }
        require(previous==null || template.version>previous.version) { "Template version must increase" }
        save(state.copy(entries=state.entries.filterNot { it.id==template.id }+template))
    }
    @Synchronized fun delete(id:String) = save(state.copy(entries=state.entries.filterNot { it.id==id }))
    @Synchronized fun setDefaults(defaults:PageDefaults) = save(state.copy(defaults=defaults.copy(metadata=defaults.metadata.frozen())))
    @Synchronized fun snapshot(id:String):TemplateDescriptor = state.entries.find { it.id==id }?.copy() ?: throw IllegalArgumentException("Template missing")
    private fun save(next:Templates) {
        val bytes=CoreCodec.encodeToJson(next).toByteArray(); require(bytes.size<=8388608)
        file.parentFile?.mkdirs(); val pending=File(file.path+".pending")
        FileOutputStream(pending).use { it.write(bytes);it.fd.sync() }
        Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);state=next
    }
}
object PaperFormats {
    val A4=595.28 to 841.89
    val A5=419.53 to 595.28
    val LETTER=612.0 to 792.0
}
