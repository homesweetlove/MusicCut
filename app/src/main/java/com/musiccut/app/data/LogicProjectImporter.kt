package com.musiccut.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.musiccut.app.audio.AudioDecoder
import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.plugin.PluginRegistry
import com.musiccut.core.project.AudioSourceInfo
import com.musiccut.core.project.AuxBus
import com.musiccut.core.project.ExternalPluginRef
import com.musiccut.core.project.PluginSlot
import com.musiccut.core.project.Project
import com.musiccut.core.project.ProjectImportInfo
import com.musiccut.core.project.Region
import com.musiccut.core.project.Track
import com.musiccut.core.project.newId
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipInputStream

/** Result of a best-effort Logic Pro project import. */
data class LogicImportResult(
    val project: Project,
    val audio: Map<String, AudioBuffer>,
    val mappedPlugins: Int,
    val preservedPlugins: Int,
    val warnings: List<String>,
)

/**
 * Imports Logic Pro project bundles (`.logicx`).
 *
 * A .logicx file on macOS is actually a package/directory. Android document providers normally
 * cannot hand an app a directory as a single document, so this importer accepts either:
 *  - a file:// URI that points at an unpacked .logicx directory, or
 *  - a ZIP containing the .logicx package (the ZIP itself may still be named *.logicx).
 *
 * Logic's ProjectData format is private. We only consume the stable, self-describing record layer
 * needed for channel/plugin recovery and keep unknown AU state opaque instead of discarding it.
 */
object LogicProjectImporter {
    private const val PROJECT_DATA = "ProjectData"
    private const val MAX_PRESERVED_STATE = 8 * 1024 * 1024

    private data class LogicRecord(
        val tag: String,
        val owner: Int,
        val key: Int,
        val raw: ByteArray,
        val payload: ByteArray,
    )

    private data class LogicChannel(
        val owner: Int,
        val name: String,
        val plugins: MutableList<Pair<Int, PluginSlot>> = mutableListOf(),
    )

    private data class AuFingerprint(
        val manufacturer: String,
        val type: String,
        val subtype: String,
    ) {
        val id: String get() = "$type/$subtype/$manufacturer"
    }

    fun looksLikeLogicProject(context: Context, uri: Uri): Boolean {
        val name = displayName(context, uri).lowercase(Locale.US)
        if (name.endsWith(".logicx") || name.endsWith(".logicx.zip") || name.endsWith(".logic.zip")) return true
        val type = context.contentResolver.getType(uri)?.lowercase(Locale.US).orEmpty()
        return type.contains("logicx")
    }

    fun import(
        context: Context,
        uri: Uri,
        targetRate: Int,
        progress: (Float) -> Unit = {},
    ): LogicImportResult {
        val sourceName = displayName(context, uri)
        val warnings = mutableListOf<String>()
        val bundle = materializeBundle(context, uri)
        progress(0.08f)

        val projectData = chooseProjectData(bundle)
            ?: throw IOException("Logic ProjectData를 찾지 못했습니다. .logicx 패키지 전체를 ZIP해서 가져오세요.")
        val records = parseRecords(projectData.readBytes())
        if (records.isEmpty()) throw IOException("지원되지 않는 Logic ProjectData 형식입니다")
        progress(0.18f)

        val channelNames = linkedMapOf<Int, String>()
        records.filter { it.tag == "OCuA" }.forEach { r ->
            channelNames[r.owner] = bestChannelName(r.payload, r.owner)
        }
        val channels = linkedMapOf<Int, LogicChannel>()
        fun channel(owner: Int): LogicChannel = channels.getOrPut(owner) {
            LogicChannel(owner, channelNames[owner] ?: "Logic Channel $owner")
        }

        var mappedPlugins = 0
        var preservedPlugins = 0
        records.filter { it.tag == "UCuA" }.forEach { record ->
            val native = parseNativePlugin(record, warnings)
            if (native != null) {
                channel(record.owner).plugins += record.key to native.first
                if (native.second) mappedPlugins++ else preservedPlugins++
                return@forEach
            }
            val au = parseAuPlugin(record, warnings)
            if (au != null) {
                channel(record.owner).plugins += record.key to au
                preservedPlugins++
            }
        }
        progress(0.30f)

        val mediaFiles = findMediaFiles(bundle)
        val decoded = linkedMapOf<String, AudioBuffer>()
        val sources = mutableListOf<AudioSourceInfo>()
        mediaFiles.forEachIndexed { i, file ->
            try {
                val audio = AudioDecoder.decode(context, Uri.fromFile(file), targetRate) { local ->
                    val fraction = (i + local) / mediaFiles.size.coerceAtLeast(1).toFloat()
                    progress(0.30f + fraction * 0.55f)
                }
                val id = newId()
                decoded[id] = audio
                sources += AudioSourceInfo(
                    id = id,
                    name = file.nameWithoutExtension,
                    frames = audio.frames.toLong(),
                    sampleRate = targetRate,
                    fileName = "$id.wav",
                )
            } catch (t: Throwable) {
                warnings += "미디어 '${file.name}' 디코딩 실패: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        progress(0.86f)

        val orderedChannels = channels.values.sortedBy { it.owner }
        val auxChannels = orderedChannels.filter { isAux(it.name) }
        val masterChannels = orderedChannels.filter { isMaster(it.name) }
        val trackChannels = orderedChannels.filterNot { it in auxChannels || it in masterChannels }

        val tracks = mutableListOf<Track>()
        val sourceIterator = sources.iterator()
        trackChannels.forEach { ch ->
            val src = if (sourceIterator.hasNext()) sourceIterator.next() else null
            val regions = if (src == null) emptyList() else listOf(
                Region(
                    sourceId = src.id,
                    name = src.name,
                    start = 0,
                    sourceOffset = 0,
                    length = src.frames,
                ),
            )
            tracks += Track(
                name = ch.name,
                regions = regions,
                plugins = ch.plugins.sortedBy { it.first }.map { it.second },
            )
        }
        while (sourceIterator.hasNext()) {
            val src = sourceIterator.next()
            tracks += Track(
                name = src.name,
                regions = listOf(Region(sourceId = src.id, name = src.name, start = 0, sourceOffset = 0, length = src.frames)),
            )
        }
        if (tracks.isEmpty()) tracks += Track(name = "Audio 1")

        val buses = auxChannels.map { ch ->
            AuxBus(name = ch.name, plugins = ch.plugins.sortedBy { it.first }.map { it.second })
        }
        val masterPlugins = masterChannels.flatMap { it.plugins.sortedBy { p -> p.first }.map { p -> p.second } }

        warnings += "Logic의 비공개 ProjectData 전체 타임라인 문법은 공개 규격이 아니어서 리전 위치/MIDI/오토메이션은 현재 오디오 파일 기준으로 안전하게 재구성합니다."
        if (preservedPlugins > 0) {
            warnings += "macOS Audio Unit은 Android에서 실행할 수 없어 ${preservedPlugins}개 슬롯을 원본 식별자/상태와 함께 pass-through로 보존했습니다."
        }

        val projectName = sourceName
            .removeSuffix(".zip")
            .removeSuffix(".logicx")
            .ifBlank { "Imported Logic Project" }
        val project = Project(
            name = projectName,
            sampleRate = targetRate,
            tracks = tracks,
            sources = sources,
            masterPlugins = masterPlugins,
            buses = buses,
            importInfo = ProjectImportInfo(
                sourceFormat = "logicx",
                sourceName = sourceName,
                warnings = warnings.toList(),
            ),
        )
        PluginRegistry.ensureExternal(project)
        progress(1f)
        return LogicImportResult(project, decoded, mappedPlugins, preservedPlugins, warnings)
    }

    private fun parseNativePlugin(record: LogicRecord, warnings: MutableList<String>): Pair<PluginSlot, Boolean>? {
        val state = record.payload
        val tag = indexOf(state, "GAMETSPP".toByteArray(Charsets.US_ASCII))
        if (tag < 12 || tag + 12 > state.size) return null
        val count = leInt(state, tag - 4).coerceIn(0, 16_384)
        val typeId = leInt(state, tag + 8)
        val floatStart = tag + 12
        if (count <= 0 || floatStart + count * 4 > state.size) return null
        val floats = FloatArray(count) { i -> leFloat(state, floatStart + i * 4) }
        val mappedId = PluginRegistry.logicNativePluginId(typeId)
        val mapped = mappedId != null
        val descriptor = mappedId?.let { PluginRegistry.find(it) }
        val logicName = descriptor?.name ?: printableNameBefore(state, tag).ifBlank { "Logic native plug-in #$typeId" }
        val original = ExternalPluginRef(
            format = "logic-native",
            name = logicName,
            subtype = typeId.toString(),
            opaqueStateBase64 = preserveState(record.raw, logicName, warnings),
        )
        val pluginId = mappedId ?: "external.logic.native.$typeId"
        val params = if (mapped) decodeNativeParams(typeId, floats, descriptor!!.defaults()) else emptyMap()
        return PluginSlot(pluginId = pluginId, params = params, external = original) to mapped
    }

    private fun decodeNativeParams(typeId: Int, f: FloatArray, defaults: Map<String, Float>): Map<String, Float> {
        fun at(i: Int): Float? = f.getOrNull(i)?.takeIf { it.isFinite() }
        val out = defaults.toMutableMap()
        when (typeId) {
            236 -> { // Channel EQ: 8 bands x [Q, enabled, freq, gain/slope], then master gain.
                at(1)?.let { out["hpOn"] = if (it > 0.5f) 1f else 0f }
                at(2)?.let { out["hpFreq"] = it }
                at(6)?.let { out["lsFreq"] = it }
                at(7)?.let { out["lsGain"] = it }
                for (band in 0 until 4) {
                    val base = 8 + band * 4
                    val n = band + 1
                    at(base)?.let { out["b${n}Q"] = it }
                    at(base + 2)?.let { out["b${n}Freq"] = it }
                    at(base + 3)?.let { out["b${n}Gain"] = it }
                }
                at(26)?.let { out["hsFreq"] = it }
                at(27)?.let { out["hsGain"] = it }
                at(29)?.let { out["lpOn"] = if (it > 0.5f) 1f else 0f }
                at(30)?.let { out["lpFreq"] = it }
                at(32)?.let { out["output"] = it }
            }
            154 -> { // Compressor
                at(1)?.let { out["threshold"] = it }
                at(2)?.let { out["ratio"] = it }
                at(3)?.let { out["attack"] = it }
                at(4)?.let { out["release"] = it }
                at(5)?.let { out["makeup"] = it }
                at(6)?.let { out["knee"] = it }
                at(8)?.let { out["autoGain"] = if (it > 0.5f) 1f else 0f }
                at(10)?.let { out["circuit"] = mapLogicCompressorCircuit(it.toInt()).toFloat() }
            }
            199 -> { // Limiter
                at(1)?.let { out["gain"] = it }
                at(4)?.let { out["release"] = it }
                at(5)?.let { out["ceiling"] = it }
            }
        }
        // Let AudioPlugin clamp values to the local descriptor's supported range on instantiation.
        return out
    }

    private fun mapLogicCompressorCircuit(value: Int): Int = when (value) {
        0 -> 0 // Platinum
        1, 2, 7 -> 1 // VCA families
        3, 5, 6 -> 2 // FET families
        4, 8 -> 3 // Opto families
        else -> 4 // Classic fallback
    }

    private fun parseAuPlugin(record: LogicRecord, warnings: MutableList<String>): PluginSlot? {
        val fp = findAuFingerprint(record.payload) ?: return null
        val name = printableNameAroundFingerprint(record.payload, fp).ifBlank { "Audio Unit ${fp.subtype}" }
        val safe = listOf(fp.type, fp.subtype, fp.manufacturer)
            .joinToString(".") { it.lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_") }
        val ref = ExternalPluginRef(
            format = "logic-au",
            name = name,
            manufacturer = fp.manufacturer,
            type = fp.type,
            subtype = fp.subtype,
            opaqueStateBase64 = preserveState(record.raw, name, warnings),
        )
        return PluginSlot(pluginId = "external.logic.au.$safe", params = emptyMap(), external = ref)
    }

    private fun preserveState(raw: ByteArray, name: String, warnings: MutableList<String>): String? {
        if (raw.size > MAX_PRESERVED_STATE) {
            warnings += "'$name' 상태가 ${raw.size / (1024 * 1024)}MB로 너무 커서 식별정보만 보존했습니다."
            return null
        }
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun findAuFingerprint(bytes: ByteArray): AuFingerprint? {
        if (bytes.size < 12) return null
        for (i in 4 until bytes.size - 4) {
            val rawType = ascii4(bytes, i)
            if (rawType !in setOf("xfua", "umua", "fmua")) continue
            val manufacturer = reversedFourCc(bytes, i - 4)
            val type = reversedFourCc(bytes, i)
            val subtype = reversedFourCc(bytes, i + 4)
            if (manufacturer.isFourCc() && type.isFourCc() && subtype.isFourCc()) {
                return AuFingerprint(manufacturer, type, subtype)
            }
        }
        return null
    }

    private fun printableNameAroundFingerprint(bytes: ByteArray, fp: AuFingerprint): String {
        // Prefer a human-readable token that appears next to the AU descriptor; avoid returning 4CCs.
        val strings = printableStrings(bytes)
        return strings.lastOrNull { s ->
            s.length in 4..96 && !s.equals(fp.type, true) && !s.equals(fp.subtype, true) && !s.equals(fp.manufacturer, true)
        }.orEmpty()
    }

    private fun parseRecords(bytes: ByteArray): List<LogicRecord> {
        if (bytes.size < 24) return emptyList()
        if (!(bytes[0] == 0x23.toByte() && bytes[1] == 0x47.toByte() && bytes[2] == 0xc0.toByte() && bytes[3] == 0xab.toByte())) {
            return emptyList()
        }
        val records = mutableListOf<LogicRecord>()
        var pos = 24
        while (pos + 36 <= bytes.size) {
            val size = leInt(bytes, pos + 28)
            if (size < 0 || size > bytes.size - pos - 36) break
            val end = pos + 36 + size
            val tag = ascii4(bytes, pos)
            val owner = leU16(bytes, pos + 14)
            val key = leU16(bytes, pos + 18)
            records += LogicRecord(
                tag = tag,
                owner = owner,
                key = key,
                raw = bytes.copyOfRange(pos, end),
                payload = bytes.copyOfRange(pos + 36, end),
            )
            pos = end
        }
        return records
    }

    private fun bestChannelName(payload: ByteArray, owner: Int): String {
        val candidates = printableStrings(payload)
            .map { it.trim() }
            .filter { it.length in 2..80 }
            .filterNot { it.startsWith("GAMETSPP") }
        val preferred = candidates.lastOrNull {
            val s = it.lowercase(Locale.US)
            s.contains("audio") || s.contains("aux") || s.contains("inst") || s.contains("bus") ||
                s.contains("output") || s.contains("master") || s.contains("stereo")
        }
        return preferred ?: candidates.lastOrNull() ?: "Logic Channel $owner"
    }

    private fun printableNameBefore(bytes: ByteArray, end: Int): String {
        val from = (end - 220).coerceAtLeast(0)
        return printableStrings(bytes.copyOfRange(from, end)).lastOrNull().orEmpty()
    }

    private fun printableStrings(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.length >= 3) out += sb.toString()
            sb.setLength(0)
        }
        bytes.forEach { b ->
            val c = b.toInt() and 0xff
            if (c in 32..126) sb.append(c.toChar()) else flush()
        }
        flush()
        return out
    }

    private fun isAux(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("aux") || n.startsWith("bus ")
    }

    private fun isMaster(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("stereo out") || n.contains("output") || n.contains("master")
    }

    private fun chooseProjectData(bundle: File): File? {
        val files = bundle.walkTopDown().filter { it.isFile && it.name == PROJECT_DATA }.toList()
        return files.firstOrNull { it.invariantSeparatorsPath.contains("/Alternatives/") } ?: files.firstOrNull()
    }

    private fun findMediaFiles(bundle: File): List<File> {
        val supported = setOf("wav", "wave", "aif", "aiff", "caf", "mp3", "m4a", "aac", "flac", "ogg")
        return bundle.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.US) in supported }
            .filter {
                val path = it.invariantSeparatorsPath.lowercase(Locale.US)
                path.contains("/media/") || path.contains("/audio files/") || path.contains("/samples/")
            }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
    }

    private fun materializeBundle(context: Context, uri: Uri): File {
        if (uri.scheme == "file") {
            val direct = uri.path?.let(::File)
            if (direct?.isDirectory == true) return direct
        }
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("파일을 열 수 없습니다")
        val buffered = BufferedInputStream(input)
        buffered.mark(8)
        val header = ByteArray(4)
        val read = buffered.read(header)
        buffered.reset()
        if (read < 4 || header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) {
            buffered.close()
            throw IOException(".logicx는 macOS 패키지입니다. .logicx 폴더를 ZIP한 뒤 가져오세요.")
        }

        val root = File(context.cacheDir, "logic-import/${System.nanoTime()}").apply { mkdirs() }
        val canonicalRoot = root.canonicalPath + File.separator
        ZipInputStream(buffered).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(root, entry.name)
                val canonical = out.canonicalPath
                if (!canonical.startsWith(canonicalRoot)) throw IOException("잘못된 ZIP 경로가 포함되어 있습니다")
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        return root.walkTopDown().firstOrNull { it.isDirectory && it.name.lowercase(Locale.US).endsWith(".logicx") } ?: root
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Logic Project.logicx"
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun leU16(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun leFloat(bytes: ByteArray, offset: Int): Float =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float

    private fun ascii4(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset + 4 > bytes.size) return ""
        return String(bytes, offset, 4, Charsets.US_ASCII)
    }

    private fun reversedFourCc(bytes: ByteArray, offset: Int): String {
        if (offset < 0 || offset + 4 > bytes.size) return ""
        return String(byteArrayOf(bytes[offset + 3], bytes[offset + 2], bytes[offset + 1], bytes[offset]), Charsets.US_ASCII)
    }

    private fun String.isFourCc(): Boolean = length == 4 && all { it.code in 32..126 }
}
