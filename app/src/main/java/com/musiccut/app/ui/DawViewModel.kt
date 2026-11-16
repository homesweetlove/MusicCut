package com.musiccut.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.musiccut.app.audio.AacEncoder
import com.musiccut.app.audio.AudioDecoder
import com.musiccut.app.audio.AudioRecorder
import com.musiccut.app.audio.PlaybackEngine
import com.musiccut.app.data.BrowserItem
import com.musiccut.app.data.MediaBrowser
import com.musiccut.app.data.ModelManager
import com.musiccut.app.data.ProjectStore
import com.musiccut.app.data.SavedProject
import com.musiccut.app.drive.DriveFile
import com.musiccut.app.drive.DriveUploader
import com.musiccut.core.audio.AudioBuffer
import com.musiccut.core.audio.Mp3Encoder
import com.musiccut.core.dsp.LoudnessMeter
import com.musiccut.core.dsp.NoteSegmenter
import com.musiccut.core.dsp.PitchNote
import com.musiccut.core.dsp.TempoDetector
import com.musiccut.core.dsp.TimePitch
import com.musiccut.core.edit.TakeOps
import com.musiccut.core.engine.Automation
import com.musiccut.core.engine.FaderLaw
import com.musiccut.core.plugin.SpectrumMatch
import com.musiccut.core.plugin.effects.THIRD_OCTAVE
import com.musiccut.core.project.AutoPoint
import com.musiccut.core.project.AutomationLane
import com.musiccut.core.separation.HtDemucsModelSpec
import com.musiccut.core.separation.HtDemucsSeparator
import com.musiccut.core.separation.MdxModelSpec
import com.musiccut.core.separation.MdxNetSeparator
import com.musiccut.core.audio.WavCodec
import com.musiccut.core.audio.WavFormat
import com.musiccut.core.edit.ArrangementOps
import com.musiccut.core.edit.Clipboard
import com.musiccut.core.edit.EditOps
import com.musiccut.core.edit.UndoStack
import com.musiccut.core.engine.Mixer
import com.musiccut.core.engine.OfflineRenderer
import com.musiccut.core.plugin.AudioPlugin
import com.musiccut.core.plugin.PluginRegistry
import com.musiccut.core.project.AudioSourceInfo
import com.musiccut.core.project.AuxBus
import com.musiccut.core.project.Send
import com.musiccut.core.project.GridSize
import com.musiccut.core.project.PluginSlot
import com.musiccut.core.project.Project
import com.musiccut.core.project.Region
import com.musiccut.core.project.SourcePool
import com.musiccut.core.project.TimeUtil
import com.musiccut.core.project.newId
import com.musiccut.core.separation.SeparationOptions
import com.musiccut.core.separation.SpectralVocalSeparator
import com.musiccut.core.separation.StemKind
import com.musiccut.core.separation.StemSeparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.log10

enum class Tool(val label: String) { POINTER("포인터"), RANGE("범위 (마퀴)"), SCISSORS("가위") }

enum class BottomPane { NONE, MIXER, EDITOR }

enum class ExportFormat(val label: String, val extension: String, val mime: String) {
    WAV16("WAV 16-bit", "wav", "audio/wav"),
    WAV24("WAV 24-bit", "wav", "audio/wav"),
    WAV32F("WAV 32-bit float", "wav", "audio/wav"),
    MP3_320("MP3 320k", "mp3", "audio/mpeg"),
    MP3_192("MP3 192k", "mp3", "audio/mpeg"),
    M4A("M4A (AAC 256k)", "m4a", "audio/mp4"),
}

/** Loudness normalization targets offered on export. */
enum class LoudnessTarget(val label: String, val lufs: Float?) {
    OFF("안 함", null),
    STREAMING("-14 LUFS (유튜브·스트리밍)", -14f),
    APPLE("-16 LUFS (애플 뮤직)", -16f),
    LOUD("-9 LUFS (클럽/라우드)", -9f),
}

enum class SeparationEngine(val label: String) {
    AI_DEMUCS_6S("AI · HT-Demucs 6-Stem (악기별)"),
    AI_VOC_FT("AI · UVR MDX-Net Voc FT (보컬 / Inst 최고 품질)"),
    AI_KIM("AI · Kim Vocal 2 (보컬 / Inst)"),
    DSP("빠른 보컬 / Inst (스테레오 센터 추출, 오프라인·가벼움)"),
}

enum class ExportRange(val label: String) { SONG("전체 곡"), CYCLE("사이클 구간"), SELECTION("선택 범위") }

enum class ExportTarget(val label: String) { DEVICE("기기에 저장"), DRIVE("Google Drive 업로드"), SHARE("공유") }

/** Time range selected with the Range tool, on the given tracks. */
data class RangeSelection(val start: Long, val end: Long, val trackIds: Set<String>) {
    val length: Long get() = end - start
}

data class BusyState(val message: String, val progress: Float? = null, val cancellable: Boolean = false)

/** Master bus pseudo-id for plugin operations. */
const val MASTER_ID = Mixer.MASTER

class DawViewModel(app: Application) : AndroidViewModel(app) {
    val sampleRate = 44100
    val pool = SourcePool()
    val engine = PlaybackEngine(pool, sampleRate)
    private val recorder = AudioRecorder(sampleRate)
    private val store = ProjectStore(app)
    private val undoStack = UndoStack<Project>()
    private val separator: StemSeparator = SpectralVocalSeparator()
    private val drive = DriveUploader()
    val models = ModelManager(app)
    val browser = MediaBrowser(app)

    var project by mutableStateOf(Project(name = "새 프로젝트", sampleRate = sampleRate))
        private set
    var selectedRegions by mutableStateOf(emptySet<String>())
        private set
    var selectedTrackId by mutableStateOf<String?>(null)
        private set
    var range by mutableStateOf<RangeSelection?>(null)
        private set
    var tool by mutableStateOf(Tool.POINTER)
    var grid by mutableStateOf(GridSize.BEAT)
    var clipboard by mutableStateOf<Clipboard?>(null)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var busy by mutableStateOf<BusyState?>(null)
        private set
    var isRecording by mutableStateOf(false)
        private set
    var dirty by mutableStateOf(false)
        private set
    var savedProjects by mutableStateOf(emptyList<SavedProject>())
        private set

    // ---- view state (Logic "View" menu)
    var showInspector by mutableStateOf(true)
    /** 0 = control bar + menu bar + global tracks, 1 = control bar only, 2 = one-line mini transport. */
    var headerLevel by mutableStateOf(0)
    var bottomPane by mutableStateOf(BottomPane.NONE)
    var trackHeight by mutableStateOf(84f)
    var showArrangement by mutableStateOf(true)
    var showMarkers by mutableStateOf(true)
    var selectedSectionId by mutableStateOf<String?>(null)
        private set

    // ---- metronome / count-in
    var metronomeOn by mutableStateOf(false)
        private set
    var countIn by mutableStateOf(true)

    // ---- automation (Logic: A key / Latch mode)
    var automationMode by mutableStateOf(false)
    var automationWrite by mutableStateOf(false)
    /** Target shown / edited per track (defaults to volume). */
    var automationTargets by mutableStateOf(emptyMap<String, String>())
        private set

    // ---- misc settings
    var autosave by mutableStateOf(true)
    var browserTree by mutableStateOf<Uri?>(null)
        private set
    var browserItems by mutableStateOf(emptyList<BrowserItem>())
        private set
    var browserPath by mutableStateOf(emptyList<Pair<String, String>>())
        private set
    var previewing by mutableStateOf<Uri?>(null)
        private set
    private val matchTemplates = HashMap<String, FloatArray>()
    var matchTemplateReady by mutableStateOf(emptySet<String>())
        private set

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages
    val position: StateFlow<Long> get() = engine.position
    val playing: StateFlow<Boolean> get() = engine.playing
    val inputLevel: StateFlow<Float> get() = recorder.level

    private val _meters = MutableStateFlow<Map<String, Float>>(emptyMap())
    val meters: StateFlow<Map<String, Float>> = _meters

    private var gestureBase: Project? = null
    private var recordStart = 0L
    private var recordTrackId: String? = null
    private var recordPreRoll = 0L
    private var busyJob: Job? = null
    @Volatile private var cancelRequested = false

    init {
        val (p, t) = EditOps.addTrack(project, "Audio 1")
        applyProject(p)
        selectedTrackId = t.id
        refreshProjects()
        // reopen the most recent project, like Logic does
        savedProjects.firstOrNull()?.let { open(it.name) }
        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                if (autosave && dirty && busy == null && gestureBase == null && project.tracks.any { it.regions.isNotEmpty() }) {
                    runCatching { withContext(Dispatchers.IO) { store.save(project, pool) }; dirty = false; refreshProjects() }
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _meters.value = if (engine.playing.value) HashMap(engine.meters()) else emptyMap()
                delay(40)
            }
        }
    }

    // ------------------------------------------------------------------ core state helpers

    private fun applyProject(p: Project) {
        project = p
        engine.project = p
        canUndo = undoStack.canUndo
        canRedo = undoStack.canRedo
    }

    /** Applies an undoable edit. */
    fun edit(label: String, f: (Project) -> Project) {
        val before = project
        val after = f(before)
        if (after == before) return
        undoStack.push(label, before)
        dirty = true
        applyProject(after)
        pruneSelection()
    }

    /** Applies a live (non-undoable) change, e.g. a fader or plugin knob move. */
    fun live(f: (Project) -> Project) {
        dirty = true
        applyProject(f(project))
    }

    fun beginGesture(label: String) {
        if (gestureBase != null) return
        gestureBase = project
        undoStack.push(label, project)
        canUndo = true
    }

    fun updateGesture(f: (Project) -> Project) {
        val base = gestureBase ?: return
        dirty = true
        applyProject(f(base))
    }

    fun endGesture() {
        val base = gestureBase ?: return
        gestureBase = null
        if (base == project) undoStack.dropLast()
        canUndo = undoStack.canUndo
        canRedo = undoStack.canRedo
        pruneSelection()
    }

    fun undo() {
        undoStack.undo(project)?.let { applyProject(it); dirty = true }
        pruneSelection()
    }

    fun redo() {
        undoStack.redo(project)?.let { applyProject(it); dirty = true }
        pruneSelection()
    }

    private fun pruneSelection() {
        val ids = project.tracks.flatMap { t -> t.regions.map { it.id } }.toSet()
        if (!ids.containsAll(selectedRegions)) selectedRegions = selectedRegions intersect ids
        if (selectedTrackId != null && project.track(selectedTrackId!!) == null) selectedTrackId = project.tracks.firstOrNull()?.id
    }

    fun toast(msg: String) {
        _messages.value = msg
    }

    fun consumeMessage() {
        _messages.value = null
    }

    private fun runBusy(message: String, cancellable: Boolean = false, block: suspend (progress: (Float) -> Unit) -> Unit) {
        if (busyJob?.isActive == true) return
        busy = BusyState(message, 0f, cancellable)
        cancelRequested = false
        busyJob = viewModelScope.launch {
            try {
                block { p -> busy = busy?.copy(progress = p) }
            } catch (e: InterruptedException) {
                toast("취소되었습니다")
            } catch (e: kotlinx.coroutines.CancellationException) {
                toast("취소되었습니다")
            } catch (e: Throwable) {
                toast("오류: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    fun cancelBusy() {
        cancelRequested = true
        busyJob?.cancel()
    }

    private fun isBusyCancelled(): Boolean = cancelRequested

    // ------------------------------------------------------------------ selection

    fun selectRegion(id: String, additive: Boolean = false) {
        selectedRegions = if (additive) {
            if (id in selectedRegions) selectedRegions - id else selectedRegions + id
        } else setOf(id)
        project.findRegion(id)?.first?.let { selectedTrackId = it.id }
    }

    fun clearSelection() {
        selectedRegions = emptySet()
    }

    fun selectTrack(id: String) {
        selectedTrackId = id
    }

    fun selectAllOnTrack() {
        val t = project.track(selectedTrackId ?: return) ?: return
        selectedRegions = t.regions.map { it.id }.toSet()
    }

    fun selectRange(r: RangeSelection?) {
        range = r?.takeIf { it.end > it.start }
    }

    // ------------------------------------------------------------------ transport

    fun togglePlay() = if (engine.playing.value) stop() else play()

    fun play() = engine.play()

    fun stop() {
        if (isRecording) stopRecording()
        engine.stop()
    }

    fun seek(frame: Long) = engine.seek(frame)

    fun rewind() = engine.seek(if (project.cycleEnabled) project.cycleStart else 0L)

    /** Jumps one bar back / forward (Logic's rewind / fast-forward buttons). */
    fun stepBar(direction: Int) {
        val bar = project.framesPerBar
        val cur = position.value
        val barIndex = kotlin.math.floor(cur / bar).toLong() + if (direction > 0) 1 else if (cur % bar.toLong() < project.sampleRate / 10) -1 else 0
        engine.seek((barIndex.coerceAtLeast(0) * bar).toLong())
    }

    fun toggleMetronome() {
        metronomeOn = !metronomeOn
        engine.metronomeOn = metronomeOn
    }

    fun setTimeSignature(beats: Int) = edit("박자") { it.copy(beatsPerBar = beats.coerceIn(1, 12)) }

    fun setKey(key: String) = edit("키") { it.copy(keySignature = key) }

    /** Moves the selected regions by one grid step (or 10 ms when snap is off). */
    fun nudge(direction: Int) {
        val ids = selectedRegions
        if (ids.isEmpty()) return
        val step = TimeUtil.gridFrames(project, grid).toLong().takeIf { it > 0 } ?: (sampleRate / 100L)
        edit("넛지") { EditOps.moveMany(it, ids, step * direction) }
    }

    fun pluginInstance(slotId: String): AudioPlugin? = engine.pluginInstance(slotId)

    fun toggleCycle() {
        val p = project
        if (!p.cycleEnabled && p.cycleEnd <= p.cycleStart) {
            val r = range
            val (s, e) = if (r != null) r.start to r.end else {
                val bar = p.framesPerBar.toLong()
                val start = TimeUtil.snap(position.value, p, GridSize.BAR)
                start to start + bar * 4
            }
            live { it.copy(cycleEnabled = true, cycleStart = s, cycleEnd = e) }
        } else live { it.copy(cycleEnabled = !it.cycleEnabled) }
    }

    /** Sets and enables the cycle (dragging in the ruler's cycle strip). */
    fun setCycleRange(start: Long, end: Long) = live {
        it.copy(cycleEnabled = true, cycleStart = minOf(start, end).coerceAtLeast(0), cycleEnd = maxOf(start, end))
    }

    fun cycleFromRange() {
        val r = range ?: return
        live { it.copy(cycleEnabled = true, cycleStart = r.start, cycleEnd = r.end) }
    }

    fun setTempo(bpm: Float) = edit("템포") { it.copy(tempo = bpm.coerceIn(30f, 300f)) }

    fun setMasterVolume(db: Float) = live { it.copy(masterVolumeDb = db) }

    fun rename(name: String) = live { it.copy(name = name.ifBlank { "Untitled" }) }

    // ------------------------------------------------------------------ recording

    fun startRecording() {
        if (isRecording) return
        var trackId = project.tracks.firstOrNull { it.armed }?.id ?: selectedTrackId
        if (trackId == null) {
            val (p, t) = EditOps.addTrack(project, "Rec")
            applyProject(p)
            trackId = t.id
        }
        try {
            recorder.start()
        } catch (e: Throwable) {
            toast("녹음 시작 실패: ${e.message}")
            return
        }
        recordTrackId = trackId
        isRecording = true
        engine.stopAtEnd = false
        if (engine.playing.value) {
            recordStart = position.value
            recordPreRoll = 0
        } else {
            val start = position.value
            val preRoll = if (countIn) project.framesPerBar.toLong() else 0L
            recordStart = start
            recordPreRoll = preRoll
            engine.countInUntil = start
            engine.play(start - preRoll)
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        engine.stopAtEnd = true
        engine.countInUntil = Long.MIN_VALUE
        val audio = recorder.stop() ?: return
        val trackId = recordTrackId ?: return
        val info = addSource("Recording ${project.sources.size + 1}", audio)
        // the count-in bar was recorded too: hide it by starting the region after the pre-roll
        val skip = recordPreRoll.coerceAtMost(audio.frames.toLong() - 1)
        val region = Region(sourceId = info.id, name = info.name, start = recordStart, sourceOffset = skip, length = audio.frames - skip, color = colorOf(trackId))
        edit("녹음") { TakeOps.addTake(it.copy(sources = it.sources + info), trackId, region) }
        selectedRegions = setOf(region.id)
    }

    private fun addSource(name: String, buffer: AudioBuffer): AudioSourceInfo {
        val id = newId()
        pool.put(id, buffer)
        return AudioSourceInfo(id, name, buffer.frames.toLong(), sampleRate, "$id.wav")
    }

    private fun colorOf(trackId: String) = project.track(trackId)?.color ?: 0

    // ------------------------------------------------------------------ import

    /**
     * Imports an audio file. It goes onto the selected track if that track is empty, otherwise onto
     * a new track. The first file lands at the song start, later ones at the playhead.
     */
    fun importAudio(uri: Uri, matchTempo: Boolean = false) {
        val name = displayName(uri)
        val baseName = name.substringBeforeLast('.')
        runBusy("가져오는 중: $name", cancellable = true) { progress ->
            var buffer = withContext(Dispatchers.Default) { AudioDecoder.decode(getApplication(), uri, sampleRate, progress) }
            if (matchTempo) {
                busy = busy?.copy(message = "루프 템포를 프로젝트에 맞추는 중…")
                val projectTempo = project.tempo
                buffer = withContext(Dispatchers.Default) {
                    var bpm = TempoDetector.detect(buffer).bpm
                    while (bpm / projectTempo > 1.4f) bpm /= 2
                    while (projectTempo / bpm > 1.4f) bpm *= 2
                    TimePitch.stretch(buffer, (bpm / projectTempo).toDouble())
                }
            }
            val info = addSource(baseName, buffer)
            val start = if (project.contentEnd == 0L) 0L else snap(position.value)
            var regionId = ""
            var trackId = ""
            edit("가져오기") { p0 ->
                var p = p0.copy(sources = p0.sources + info)
                val selected = p.track(selectedTrackId ?: "")
                if (selected != null && selected.regions.isEmpty()) {
                    trackId = selected.id
                    if (selected.name.startsWith("Audio ")) p = p.updateTrack(trackId) { it.copy(name = baseName) }
                } else {
                    val (np, t) = EditOps.addTrack(p, baseName)
                    p = np
                    trackId = t.id
                }
                val region = Region(sourceId = info.id, name = baseName, start = start, sourceOffset = 0, length = buffer.frames.toLong(), color = p.track(trackId)?.color ?: 0)
                regionId = region.id
                EditOps.addRegion(p, trackId, region)
            }
            selectedTrackId = trackId
            selectedRegions = setOf(regionId)
            toast("가져오기 완료 (${"%.1f".format(buffer.durationSeconds)}초)")
        }
    }

    private fun displayName(uri: Uri): String {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
    }

    // ------------------------------------------------------------------ region editing

    private fun targetRegions(): Set<String> = selectedRegions

    fun splitAtPlayhead() {
        val at = position.value
        val ids = targetRegions().ifEmpty {
            // no selection → split whatever is under the playhead on the selected track
            project.track(selectedTrackId ?: "")?.regions?.filter { it.contains(at) }?.map { it.id }?.toSet() ?: emptySet()
        }
        if (ids.isEmpty()) return toast("플레이헤드 위치에 자를 리전이 없습니다")
        edit("자르기") { EditOps.split(it, at, ids) }
    }

    fun splitRegionAt(regionId: String, at: Long) = edit("자르기") { EditOps.split(it, at, setOf(regionId)) }

    fun deleteSelection() {
        val r = range
        if (r != null && selectedRegions.isEmpty()) {
            edit("범위 지우기") { EditOps.clearRange(it, r.trackIds, r.start, r.end) }
            return
        }
        val ids = targetRegions()
        if (ids.isEmpty()) return
        edit("삭제") { EditOps.delete(it, ids) }
        selectedRegions = emptySet()
    }

    fun copySelection() {
        val r = range
        clipboard = if (r != null && selectedRegions.isEmpty()) EditOps.copyRange(project, r.trackIds, r.start, r.end)
        else EditOps.copy(project, targetRegions())
        toast(if (clipboard?.isEmpty == false) "복사됨" else "선택된 항목이 없습니다")
    }

    fun cutSelection() {
        val r = range
        if (r != null && selectedRegions.isEmpty()) {
            clipboard = EditOps.copyRange(project, r.trackIds, r.start, r.end)
            edit("범위 잘라내기") { EditOps.clearRange(it, r.trackIds, r.start, r.end) }
            return
        }
        val ids = targetRegions()
        if (ids.isEmpty()) return
        clipboard = EditOps.copy(project, ids)
        edit("잘라내기") { EditOps.delete(it, ids) }
        selectedRegions = emptySet()
    }

    /** Paste at the playhead on the selected track, replacing what is there. */
    fun paste() {
        val clip = clipboard?.takeIf { !it.isEmpty } ?: return toast("클립보드가 비어 있습니다")
        val trackId = selectedTrackId ?: project.tracks.firstOrNull()?.id ?: return
        val at = TimeUtil.snap(position.value, project, grid)
        var ids = emptySet<String>()
        edit("붙여넣기") { p -> EditOps.paste(p, clip, trackId, at).also { ids = it.second }.first }
        selectedRegions = ids
    }

    /** Paste at the playhead and push everything after it to the right. */
    fun pasteInsert() {
        val clip = clipboard?.takeIf { !it.isEmpty } ?: return toast("클립보드가 비어 있습니다")
        val trackId = selectedTrackId ?: project.tracks.firstOrNull()?.id ?: return
        val at = TimeUtil.snap(position.value, project, grid)
        var ids = emptySet<String>()
        edit("삽입 붙여넣기") { p -> EditOps.rippleInsert(p, clip, trackId, at).also { ids = it.second }.first }
        selectedRegions = ids
    }

    fun duplicateSelection() {
        val ids = targetRegions()
        if (ids.isEmpty()) return
        var newIds = emptySet<String>()
        edit("복제") { p -> EditOps.duplicate(p, ids).also { newIds = it.second }.first }
        selectedRegions = newIds
    }

    /** Ripple-delete the selected range from the song (removes the section and closes the gap). */
    fun rippleDeleteRange() {
        val r = range ?: return toast("범위 도구로 구간을 먼저 선택하세요")
        var clip: Clipboard? = null
        val allTracks = r.trackIds.containsAll(project.tracks.map { it.id })
        edit("구간 삭제") { p ->
            (if (allTracks) ArrangementOps.rippleCutAll(p, r.start, r.end) else EditOps.rippleCut(p, r.trackIds, r.start, r.end))
                .also { clip = it.second }.first
        }
        clipboard = clip
        range = null
        seek(r.start)
    }

    fun cropToRange() {
        val r = range ?: return toast("범위 도구로 구간을 먼저 선택하세요")
        edit("구간만 남기기") { EditOps.cropToRange(it, r.trackIds, r.start, r.end) }
        range = null
        seek(0)
    }

    fun insertSilenceAtRange() {
        val r = range ?: return toast("범위 도구로 구간을 먼저 선택하세요")
        edit("무음 삽입") { EditOps.insertSilence(it, r.trackIds, r.start, r.length) }
    }

    /** Splits regions at both range edges and selects the pieces inside the range. */
    fun splitAtRange() {
        val r = range ?: return
        fun idsOn(p: Project) = p.tracks.filter { it.id in r.trackIds }.flatMap { t -> t.regions.map { it.id } }.toSet()
        edit("범위로 자르기") { p ->
            val a = EditOps.split(p, r.start, idsOn(p))
            EditOps.split(a, r.end, idsOn(a))
        }
        selectedRegions = project.tracks.filter { it.id in r.trackIds }
            .flatMap { t -> t.regions.filter { it.start >= r.start && it.end <= r.end } }.map { it.id }.toSet()
        range = null
    }

    /**
     * Joins the selected regions per track. Pieces that are still contiguous in their source are
     * glued losslessly; anything else is rendered into a new audio file (Logic's "Join").
     */
    fun joinSelection() {
        val ids = targetRegions()
        if (ids.size < 2) return toast("합칠 리전을 2개 이상 선택하세요")
        val involved = project.tracks.filter { t -> t.regions.any { it.id in ids } }
        val glued = EditOps.glueContiguous(project, ids)
        val before = involved.sumOf { it.regions.size }
        val after = involved.sumOf { t -> glued.track(t.id)?.regions?.size ?: 0 }
        if (before - after == ids.size - involved.size) {
            edit("합치기") { glued }
            return
        }
        runBusy("리전 합치는 중") {
            val (p, _) = withContext(Dispatchers.Default) { OfflineRenderer.join(project, pool, ids) }
            edit("합치기") { p }
            selectedRegions = emptySet()
        }
    }

    fun reverseSelection() {
        val ids = targetRegions()
        if (ids.isNotEmpty()) edit("리버스") { EditOps.reverse(it, ids) }
    }

    fun toggleMuteSelection() {
        val ids = targetRegions()
        if (ids.isNotEmpty()) edit("리전 뮤트") { EditOps.toggleMute(it, ids) }
    }

    fun normalizeSelection() {
        val ids = targetRegions()
        if (ids.isEmpty()) return
        edit("노멀라이즈") { p ->
            ids.fold(p) { acc, id ->
                val region = acc.findRegion(id)?.second ?: return@fold acc
                val (_, audio) = OfflineRenderer.renderRegionsDry(pool, listOf(region.copy(gainDb = 0f, fadeIn = 0, fadeOut = 0)), sampleRate)
                val peak = audio.peak()
                if (peak <= 0f) acc else EditOps.setGain(acc, setOf(id), -20f * log10(peak) - 0.1f)
            }
        }
    }

    fun setRegionGain(id: String, db: Float) = live { EditOps.setGain(it, setOf(id), db) }
    fun setRegionFades(id: String, fadeIn: Long?, fadeOut: Long?) = live { EditOps.setFades(it, id, fadeIn, fadeOut) }
    fun renameRegion(id: String, name: String) = edit("이름 변경") { EditOps.rename(it, id, name) }
    fun commitRegionChange(label: String, before: Project) {
        if (before != project) {
            undoStack.push(label, before)
            canUndo = true
        }
    }

    fun snap(frame: Long): Long = TimeUtil.snap(frame, project, grid)

    // ------------------------------------------------------------------ tracks

    fun addTrack() {
        var id = ""
        edit("트랙 추가") { p -> EditOps.addTrack(p).also { id = it.second.id }.first }
        selectedTrackId = id
    }

    fun deleteTrack(id: String) = edit("트랙 삭제") { EditOps.removeTrack(it, id) }
    fun duplicateTrack(id: String) = edit("트랙 복제") { EditOps.duplicateTrack(it, id) }
    fun moveTrack(id: String, delta: Int) = edit("트랙 이동") { EditOps.moveTrack(it, id, delta) }
    fun renameTrack(id: String, name: String) = edit("트랙 이름") { p -> p.updateTrack(id) { it.copy(name = name) } }
    fun setTrackColor(id: String, color: Int) = live { p -> p.updateTrack(id) { it.copy(color = color) } }
    private fun groupOf(id: String): Set<String> {
        val g = project.track(id)?.groupId ?: return setOf(id)
        return project.tracks.filter { it.groupId == g }.map { it.id }.toSet()
    }

    /** Volume; members of the same group follow by the same dB delta (Logic groups). */
    fun setVolume(id: String, db: Float) {
        val delta = db - (project.track(id)?.volumeDb ?: db)
        val ids = groupOf(id)
        live { p -> p.copy(tracks = p.tracks.map { t -> if (t.id in ids) t.copy(volumeDb = if (t.id == id) db else (t.volumeDb + delta).coerceIn(-120f, 6f)) else t }) }
        touchAutomation(id, Automation.VOLUME, FaderLaw.dbToPos(db))
    }

    fun setPan(id: String, pan: Float) {
        live { p -> p.updateTrack(id) { it.copy(pan = pan.coerceIn(-1f, 1f)) } }
        touchAutomation(id, Automation.PAN, (pan.coerceIn(-1f, 1f) + 1f) / 2f)
    }

    fun toggleMute(id: String) {
        val ids = groupOf(id)
        val v = !(project.track(id)?.muted ?: false)
        live { p -> p.copy(tracks = p.tracks.map { if (it.id in ids) it.copy(muted = v) else it }) }
    }

    fun toggleSolo(id: String) {
        val ids = groupOf(id)
        val v = !(project.track(id)?.solo ?: false)
        live { p -> p.copy(tracks = p.tracks.map { if (it.id in ids) it.copy(solo = v) else it }) }
    }

    fun setGroup(id: String, group: Int?) = edit("그룹") { p -> p.updateTrack(id) { it.copy(groupId = group) } }

    /** Routes a track into an aux (summing stack) or back to the Stereo Out (null). */
    fun setTrackOutput(id: String, busId: String?) = edit("출력 변경") { p -> p.updateTrack(id) { it.copy(outputBusId = busId) } }

    /** Logic "Create Summing Stack": a new aux fed by the given tracks. */
    fun createSummingStack(trackIds: Set<String>) {
        if (trackIds.isEmpty()) return
        val bus = AuxBus(name = "Stack ${project.buses.count { it.name.startsWith("Stack") } + 1}", color = 6)
        edit("서밍 스택") { p ->
            p.copy(buses = p.buses + bus, tracks = p.tracks.map { if (it.id in trackIds) it.copy(outputBusId = bus.id) else it })
        }
        toast("'${bus.name}' 생성: 트랙 ${trackIds.size}개가 스택으로 합쳐집니다")
    }

    /** Tracks involved in the current selection (selected track + tracks of selected regions). */
    fun selectedTrackIds(): Set<String> =
        (project.tracks.filter { t -> t.regions.any { it.id in selectedRegions } }.map { it.id } + listOfNotNull(selectedTrackId)).toSet()
    fun toggleArm(id: String) = live { p -> p.copy(tracks = p.tracks.map { if (it.id == id) it.copy(armed = !it.armed) else it.copy(armed = false) }) }

    // ------------------------------------------------------------------ plugins

    fun pluginsOf(ownerId: String): List<PluginSlot> = when {
        ownerId == MASTER_ID -> project.masterPlugins
        project.bus(ownerId) != null -> project.bus(ownerId)!!.plugins
        else -> project.track(ownerId)?.plugins ?: emptyList()
    }

    fun ownerName(ownerId: String): String = when {
        ownerId == MASTER_ID -> "Stereo Out"
        else -> project.bus(ownerId)?.name ?: project.track(ownerId)?.name ?: ""
    }

    private fun updatePlugins(p: Project, ownerId: String, f: (List<PluginSlot>) -> List<PluginSlot>): Project = when {
        ownerId == MASTER_ID -> p.copy(masterPlugins = f(p.masterPlugins))
        p.bus(ownerId) != null -> p.updateBus(ownerId) { it.copy(plugins = f(it.plugins)) }
        else -> p.updateTrack(ownerId) { it.copy(plugins = f(it.plugins)) }
    }

    /** Opens (inserting if needed) a Channel EQ on the owner — Logic's EQ thumbnail click. */
    fun ensureChannelEq(ownerId: String): String? =
        pluginsOf(ownerId).firstOrNull { it.pluginId == "channel_eq" }?.id ?: addPlugin(ownerId, "channel_eq")

    // ------------------------------------------------------------------ aux buses & sends

    /** Creates a new aux (optionally with a plugin, e.g. a reverb return) and returns its id. */
    fun addBus(withPlugin: String? = null): String {
        val n = project.buses.size + 1
        val d = withPlugin?.let { PluginRegistry.find(it) }
        val slots = if (d != null) listOf(PluginSlot(pluginId = d.id, params = d.defaults() + ("mix" to 100f))) else emptyList()
        val bus = AuxBus(name = if (d != null) "Aux $n (${d.name})" else "Aux $n", plugins = slots)
        edit("Aux 추가") { it.copy(buses = it.buses + bus) }
        return bus.id
    }

    fun removeBus(busId: String) = edit("Aux 삭제") { p ->
        p.copy(buses = p.buses.filterNot { it.id == busId }, tracks = p.tracks.map { t -> t.copy(sends = t.sends.filterNot { it.busId == busId }) })
    }

    fun setBusVolume(busId: String, db: Float) = live { p -> p.updateBus(busId) { it.copy(volumeDb = db) } }
    fun setBusPan(busId: String, pan: Float) = live { p -> p.updateBus(busId) { it.copy(pan = pan.coerceIn(-1f, 1f)) } }
    fun toggleBusMute(busId: String) = live { p -> p.updateBus(busId) { it.copy(muted = !it.muted) } }

    fun addSend(trackId: String, busId: String) = edit("센드 추가") { p ->
        p.updateTrack(trackId) { t -> if (t.sends.any { it.busId == busId }) t else t.copy(sends = t.sends + Send(busId)) }
    }

    fun setSendLevel(trackId: String, busId: String, db: Float) = live { p ->
        p.updateTrack(trackId) { t -> t.copy(sends = t.sends.map { if (it.busId == busId) it.copy(levelDb = db) else it }) }
    }

    fun toggleSendPre(trackId: String, busId: String) = live { p ->
        p.updateTrack(trackId) { t -> t.copy(sends = t.sends.map { if (it.busId == busId) it.copy(preFader = !it.preFader) else it }) }
    }

    fun removeSend(trackId: String, busId: String) = edit("센드 삭제") { p ->
        p.updateTrack(trackId) { t -> t.copy(sends = t.sends.filterNot { it.busId == busId }) }
    }

    // ------------------------------------------------------------------ arrangement & markers

    private val sectionNames = listOf("Intro", "Verse", "Pre-Chorus", "Chorus", "Bridge", "Solo", "Outro")

    /** Adds a section from the range selection, the cycle, or 8 bars at the playhead. */
    fun addSection(name: String? = null) {
        val p = project
        val (s, e) = range?.let { it.start to it.end }
            ?: if (p.cycleEnd > p.cycleStart) p.cycleStart to p.cycleEnd
            else {
                val st = TimeUtil.snap(position.value, p, GridSize.BAR)
                st to st + (p.framesPerBar * 8).toLong()
            }
        val label = name ?: sectionNames[p.sections.size % sectionNames.size]
        edit("섹션 추가") { ArrangementOps.addSection(it, label, s, e) }
    }

    fun selectSection(id: String?) {
        selectedSectionId = id
        val s = project.sections.firstOrNull { it.id == id } ?: return
        selectRange(RangeSelection(s.start, s.end, project.tracks.map { it.id }.toSet()))
        clearSelection()
    }

    fun renameSection(id: String, name: String) = edit("섹션 이름") { ArrangementOps.renameSection(it, id, name) }
    fun deleteSection(id: String) { edit("섹션 삭제") { ArrangementOps.deleteSection(it, id) }; range = null; selectedSectionId = null }
    fun duplicateSection(id: String) = edit("섹션 복제") { ArrangementOps.duplicateSection(it, id) }
    fun moveSection(id: String, direction: Int) { edit("섹션 이동") { ArrangementOps.moveSection(it, id, direction) }; selectSection(id) }
    fun removeSectionMarker(id: String) { edit("섹션 표시 삭제") { ArrangementOps.removeSectionMarker(it, id) }; selectedSectionId = null }
    fun cycleSection(id: String) {
        val s = project.sections.firstOrNull { it.id == id } ?: return
        live { it.copy(cycleEnabled = true, cycleStart = s.start, cycleEnd = s.end) }
        seek(s.start)
    }
    fun setSectionColor(id: String, color: Int) = live { p -> p.copy(sections = p.sections.map { if (it.id == id) it.copy(color = color) else it }) }

    fun addMarker() = edit("마커 추가") { ArrangementOps.addMarker(it, snap(position.value)) }
    fun renameMarker(id: String, name: String) = edit("마커 이름") { ArrangementOps.renameMarker(it, id, name) }
    fun removeMarker(id: String) = edit("마커 삭제") { ArrangementOps.removeMarker(it, id) }

    fun gotoMarker(direction: Int) {
        val cur = position.value
        val points = (project.markers.map { it.position } + project.sections.map { it.start } + 0L).distinct().sorted()
        val target = if (direction > 0) points.firstOrNull { it > cur + sampleRate / 20 } else points.lastOrNull { it < cur - sampleRate / 5 }
        target?.let { seek(it) }
    }

    fun addPlugin(ownerId: String, pluginId: String): String? {
        val d = PluginRegistry.find(pluginId) ?: return null
        val slot = PluginSlot(pluginId = pluginId, params = d.defaults())
        edit("${d.name} 추가") { updatePlugins(it, ownerId) { list -> list + slot } }
        return slot.id
    }

    fun removePlugin(ownerId: String, slotId: String) = edit("플러그인 삭제") { updatePlugins(it, ownerId) { l -> l.filterNot { s -> s.id == slotId } } }

    fun movePlugin(ownerId: String, slotId: String, delta: Int) = edit("플러그인 순서") {
        updatePlugins(it, ownerId) { l ->
            val i = l.indexOfFirst { s -> s.id == slotId }
            val j = (i + delta).coerceIn(0, l.size - 1)
            if (i < 0 || i == j) l else l.toMutableList().apply { add(j, removeAt(i)) }
        }
    }

    fun setPluginParam(ownerId: String, slotId: String, paramId: String, value: Float) {
        live { updatePlugins(it, ownerId) { l -> l.map { s -> if (s.id == slotId) s.copy(params = s.params + (paramId to value)) else s } } }
        val slot = pluginsOf(ownerId).firstOrNull { it.id == slotId } ?: return
        val spec = PluginRegistry.find(slot.pluginId)?.params?.firstOrNull { it.id == paramId } ?: return
        touchAutomation(ownerId, Automation.pluginTarget(slotId, paramId), spec.toNormalized(value))
    }

    fun setSidechain(ownerId: String, slotId: String, trackId: String?) = edit("사이드체인") {
        updatePlugins(it, ownerId) { l -> l.map { s -> if (s.id == slotId) s.copy(sidechainTrackId = trackId) else s } }
    }

    // ------------------------------------------------------------------ automation

    fun automationTarget(trackId: String): String = automationTargets[trackId] ?: Automation.VOLUME

    fun setAutomationTarget(trackId: String, target: String) {
        automationTargets = automationTargets + (trackId to target)
    }

    /** Parameters of a track that can be automated: (target, label). */
    fun automatableTargets(trackId: String): List<Pair<String, String>> {
        val t = project.track(trackId) ?: return emptyList()
        val list = mutableListOf(Automation.VOLUME to "Volume", Automation.PAN to "Pan")
        t.sends.forEach { s -> list += Automation.sendTarget(s.busId) to "Send → ${project.bus(s.busId)?.name ?: "?"}" }
        t.plugins.forEach { slot ->
            val d = PluginRegistry.find(slot.pluginId) ?: return@forEach
            d.params.filterNot { it.isDiscrete }.forEach { spec -> list += Automation.pluginTarget(slot.id, spec.id) to "${d.name}: ${spec.name}" }
        }
        return list
    }

    fun automationLabel(trackId: String, target: String): String =
        project.track(trackId)?.let { t -> Automation.label(t, target) { project.bus(it)?.name } } ?: target

    private fun updateLane(p: Project, trackId: String, target: String, f: (List<AutoPoint>) -> List<AutoPoint>): Project =
        p.updateTrack(trackId) { t ->
            val existing = t.automation.firstOrNull { it.target == target }
            val lanes = if (existing != null) {
                t.automation.map { if (it.target == target) it.copy(points = f(it.points)) else it }
            } else t.automation + AutomationLane(target = target, points = f(emptyList()))
            t.copy(automation = lanes.filter { it.points.isNotEmpty() })
        }

    fun addAutomationPoint(trackId: String, target: String, frame: Long, value: Float) = edit("오토메이션 점") { p ->
        updateLane(p, trackId, target) { Automation.withPoint(it, AutoPoint(frame.coerceAtLeast(0), value.coerceIn(0f, 1f))) }
    }

    fun removeAutomationPoint(trackId: String, target: String, index: Int) = edit("오토메이션 점 삭제") { p ->
        updateLane(p, trackId, target) { pts -> pts.filterIndexed { i, _ -> i != index } }
    }

    /** Live move of a point during a gesture (call between beginGesture / endGesture). */
    fun moveAutomationPoint(base: Project, trackId: String, target: String, index: Int, frame: Long, value: Float): Project =
        updateLane(base, trackId, target) { pts ->
            if (index !in pts.indices) pts
            else pts.mapIndexed { i, pt -> if (i == index) AutoPoint(frame.coerceAtLeast(0), value.coerceIn(0f, 1f)) else pt }.sortedBy { it.frame }
        }

    fun clearAutomation(trackId: String, target: String) = edit("오토메이션 지우기") { p ->
        p.updateTrack(trackId) { t -> t.copy(automation = t.automation.filterNot { it.target == target }) }
    }

    /** Latch-style writing: fader / knob moves during playback are recorded as points. */
    private fun touchAutomation(trackId: String, target: String, value: Float) {
        if (!automationWrite || !engine.playing.value || project.track(trackId) == null) return
        val frame = position.value
        live { p -> updateLane(p, trackId, target) { Automation.withPoint(it, AutoPoint(frame, value), mergeFrames = sampleRate / 20L) } }
    }

    fun setPluginBypass(ownerId: String, slotId: String, bypassed: Boolean) = live {
        updatePlugins(it, ownerId) { l -> l.map { s -> if (s.id == slotId) s.copy(bypassed = bypassed) else s } }
    }

    fun applyPreset(ownerId: String, slotId: String, preset: Map<String, Float>) = edit("프리셋") {
        updatePlugins(it, ownerId) { l ->
            l.map { s ->
                if (s.id != slotId) s else {
                    val defaults = PluginRegistry.find(s.pluginId)?.defaults() ?: emptyMap()
                    s.copy(params = defaults + preset)
                }
            }
        }
    }

    // ------------------------------------------------------------------ AI stem separation

    fun modelFor(engine: SeparationEngine): MdxModelSpec? = when (engine) {
        SeparationEngine.AI_VOC_FT -> MdxModelSpec.VOC_FT
        SeparationEngine.AI_KIM -> MdxModelSpec.KIM_VOCAL_2
        SeparationEngine.AI_DEMUCS_6S, SeparationEngine.DSP -> null
    }

    fun demucsModelFor(engine: SeparationEngine): HtDemucsModelSpec? =
        if (engine == SeparationEngine.AI_DEMUCS_6S) HtDemucsModelSpec.SIX_STEM_FP16 else null

    fun separationModelSizeMb(engine: SeparationEngine): Int? =
        demucsModelFor(engine)?.approxSizeMb ?: modelFor(engine)?.approxSizeMb

    fun isSeparationModelInstalled(engine: SeparationEngine): Boolean = when {
        demucsModelFor(engine) != null -> models.isInstalled(demucsModelFor(engine)!!)
        modelFor(engine) != null -> models.isInstalled(modelFor(engine)!!)
        else -> true
    }

    fun separateSelected(options: SeparationOptions, muteOriginal: Boolean, engine: SeparationEngine = SeparationEngine.AI_VOC_FT) {
        if (engine == SeparationEngine.AI_DEMUCS_6S) {
            separateSelectedInstruments(muteOriginal)
            return
        }
        val id = selectedRegions.singleOrNull() ?: return toast("분리할 리전을 하나 선택하세요")
        val (track, region) = project.findRegion(id) ?: return
        runBusy("보컬 / 반주 분리 준비 중…", cancellable = true) { progress ->
            val spec = modelFor(engine)
            val stemSeparator: StemSeparator = if (spec == null) separator else {
                if (!models.isInstalled(spec)) {
                    busy = busy?.copy(message = "AI 모델 다운로드 중 (약 ${spec.approxSizeMb}MB, 처음 한 번만)…")
                    withContext(Dispatchers.IO) { models.download(spec, progress) { isBusyCancelled() } }
                }
                MdxNetSeparator(models.file(spec), spec, threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
            }
            busy = busy?.copy(message = if (spec == null) "보컬 / 반주 분리 중…" else "AI 보컬 분리 중… (곡 길이에 따라 수 분)", progress = 0f)
            val stems = withContext(Dispatchers.Default) {
                val (_, audio) = OfflineRenderer.renderRegionsDry(pool, listOf(region.copy(muted = false)), sampleRate)
                stemSeparator.separate(audio, options, progress) { isBusyCancelled() }
            }
            val vocal = addSource("${region.name} - Vocals", stems.vocals)
            val inst = addSource("${region.name} - Instrumental", stems.instrumental)
            edit("보컬 분리") { p0 ->
                var p = p0.copy(sources = p0.sources + vocal + inst)
                val idx = p.tracks.indexOfFirst { it.id == track.id }
                val (p1, vt) = EditOps.addTrack(p, "${track.name} Vocals", idx + 1)
                val (p2, instTrack) = EditOps.addTrack(p1, "${track.name} Inst", idx + 2)
                p = p2.updateTrack(vt.id) { t -> t.copy(color = 5) }.updateTrack(instTrack.id) { t -> t.copy(color = 2) }
                // The stems already contain the region's gain, fades and direction.
                val stemRegion = region.copy(sourceOffset = 0, reversed = false, muted = false, gainDb = 0f, fadeIn = 0, fadeOut = 0)
                p = EditOps.addRegion(p, vt.id, stemRegion.copy(id = newId(), sourceId = vocal.id, name = vocal.name, color = 5))
                p = EditOps.addRegion(p, instTrack.id, stemRegion.copy(id = newId(), sourceId = inst.id, name = inst.name, color = 2))
                if (muteOriginal) p = EditOps.updateRegion(p, region.id) { r -> r.copy(muted = true) }
                p
            }
            toast("분리 완료: Vocals / Instrumental 트랙이 추가되었습니다")
        }
    }

    private fun separateSelectedInstruments(muteOriginal: Boolean) {
        val id = selectedRegions.singleOrNull() ?: return toast("악기별로 분리할 리전을 하나 선택하세요")
        val (track, region) = project.findRegion(id) ?: return
        val spec = HtDemucsModelSpec.SIX_STEM_FP16
        runBusy("6-Stem AI 분리 준비 중…", cancellable = true) { progress ->
            var inferenceBase = 0f
            if (!models.isInstalled(spec)) {
                busy = busy?.copy(message = "HT-Demucs 모델 다운로드 중 (약 ${spec.approxSizeMb}MB, 처음 한 번만)…")
                withContext(Dispatchers.IO) {
                    models.download(spec, { progress(it * 0.15f) }) { isBusyCancelled() }
                }
                inferenceBase = 0.15f
            }
            busy = busy?.copy(
                message = "보컬 · 드럼 · 베이스 · 기타 · 피아노 · Other 분리 중…",
                progress = inferenceBase,
            )
            val stems = withContext(Dispatchers.Default) {
                val (_, audio) = OfflineRenderer.renderRegionsDry(pool, listOf(region.copy(muted = false)), sampleRate)
                HtDemucsSeparator(
                    models.file(spec),
                    spec,
                    threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
                ).separate(
                    audio,
                    StemKind.entries.toSet(),
                    { p -> progress(inferenceBase + p * (1f - inferenceBase)) },
                ) { isBusyCancelled() }
            }
            val order = listOf(StemKind.VOCALS, StemKind.DRUMS, StemKind.BASS, StemKind.GUITAR, StemKind.PIANO, StemKind.OTHER)
            val colors = mapOf(
                StemKind.VOCALS to 5,
                StemKind.DRUMS to 1,
                StemKind.BASS to 2,
                StemKind.GUITAR to 6,
                StemKind.PIANO to 4,
                StemKind.OTHER to 3,
            )
            val created = order.mapNotNull { kind ->
                stems[kind]?.let { kind to addSource("${region.name} - ${kind.label}", it) }
            }
            edit("악기별 6-Stem 분리") { p0 ->
                var p = p0.copy(sources = p0.sources + created.map { it.second })
                var insertAt = p.tracks.indexOfFirst { it.id == track.id } + 1
                val stemRegion = region.copy(sourceOffset = 0, reversed = false, muted = false, gainDb = 0f, fadeIn = 0, fadeOut = 0)
                for ((kind, source) in created) {
                    val color = colors[kind] ?: 0
                    val (next, stemTrack) = EditOps.addTrack(p, "${track.name} ${kind.label}", insertAt++)
                    p = next.updateTrack(stemTrack.id) { t -> t.copy(color = color) }
                    p = EditOps.addRegion(
                        p,
                        stemTrack.id,
                        stemRegion.copy(id = newId(), sourceId = source.id, name = source.name, length = source.frames, color = color),
                    )
                }
                if (muteOriginal) p = EditOps.updateRegion(p, region.id) { r -> r.copy(muted = true) }
                p
            }
            toast("6-Stem 분리 완료: Vocals / Drums / Bass / Guitar / Piano / Other")
        }
    }

    // ------------------------------------------------------------------ crossfades, takes

    fun crossfadeSelection(ms: Int = 20) {
        val ids = selectedRegions
        if (ids.size < 2) return toast("이어진 리전을 2개 이상 선택하세요")
        edit("크로스페이드") { EditOps.crossfade(it, ids, sampleRate * ms / 1000L) }
    }

    fun compTake(trackId: String, laneId: String, useRange: Boolean) {
        val r = range
        edit("테이크 컴핑") { p ->
            if (useRange && r != null) TakeOps.compRange(p, trackId, laneId, r.start, r.end) else TakeOps.compWholeTake(p, trackId, laneId)
        }
    }

    fun deleteTake(trackId: String, laneId: String) = edit("테이크 삭제") { TakeOps.deleteTake(it, trackId, laneId) }
    fun flattenTakes(trackId: String) = edit("테이크 정리") { TakeOps.flatten(it, trackId) }

    // ------------------------------------------------------------------ Flex Time / Pitch

    /** Renders the selected region through [process] and replaces it with the result. */
    private fun processRegion(label: String, process: suspend (AudioBuffer, (Float) -> Unit) -> AudioBuffer) {
        val id = selectedRegions.singleOrNull() ?: return toast("리전을 하나 선택하세요")
        val (track, region) = project.findRegion(id) ?: return
        runBusy("$label…", cancellable = true) { progress ->
            val out = withContext(Dispatchers.Default) {
                val (_, audio) = OfflineRenderer.renderRegionsDry(pool, listOf(region.copy(muted = false)), sampleRate)
                process(audio, progress)
            }
            val info = addSource("${region.name} ($label)", out)
            val newRegion = region.copy(id = newId(), sourceId = info.id, name = info.name, sourceOffset = 0, length = out.frames.toLong(), gainDb = 0f, fadeIn = 0, fadeOut = 0, reversed = false)
            edit(label) { p ->
                val removed = EditOps.delete(p.copy(sources = p.sources + info), setOf(region.id))
                EditOps.addRegion(removed, track.id, newRegion)
            }
            selectedRegions = setOf(newRegion.id)
        }
    }

    /** Flex Time: change length by [ratio] (pitch kept) or varispeed (pitch follows). */
    fun stretchSelection(ratio: Double, varispeed: Boolean) = processRegion(if (varispeed) "Varispeed" else "타임 스트레치") { a, prog ->
        if (varispeed) TimePitch.varispeed(a, 1.0 / ratio) else TimePitch.stretch(a, ratio, prog) { isBusyCancelled() }
    }

    /** Transposes the whole region, keeping its length. */
    fun transposeSelection(semitones: Double) = processRegion("음정 %+.1f".format(semitones)) { a, prog ->
        TimePitch.pitchShift(a, semitones, prog) { isBusyCancelled() }
    }

    /** Detects the tempo of the selected region (or the whole song) and optionally applies it. */
    fun detectTempo(apply: Boolean) {
        val id = selectedRegions.singleOrNull()
        runBusy("템포 분석 중…") {
            val audio = withContext(Dispatchers.Default) {
                if (id != null) {
                    val r = project.findRegion(id)?.second ?: return@withContext null
                    OfflineRenderer.renderRegionsDry(pool, listOf(r.copy(muted = false)), sampleRate).second
                } else OfflineRenderer.bounce(project, pool, 0, minOf(project.contentEnd, sampleRate * 60L))
            } ?: return@runBusy
            val est = withContext(Dispatchers.Default) { TempoDetector.detect(audio) }
            if (apply) setTempo(est.bpm)
            toast("감지된 템포: %.1f BPM%s".format(est.bpm, if (apply) " (프로젝트 템포로 설정)" else ""))
        }
    }

    /** Stretches the selected region from its detected tempo to the project tempo (Flex "follow tempo"). */
    fun matchRegionToProjectTempo() {
        if (selectedRegions.size != 1) return toast("리전을 하나 선택하세요")
        val projectTempo = project.tempo
        processRegion("템포 맞춤") { a, prog ->
            val est = TempoDetector.detect(a)
            var bpm = est.bpm
            while (bpm / projectTempo > 1.4f) bpm /= 2
            while (projectTempo / bpm > 1.4f) bpm *= 2
            TimePitch.stretch(a, (bpm / projectTempo).toDouble(), prog) { isBusyCancelled() }
        }
    }

    /** Flex Pitch: detected monophonic notes of the selected region. */
    suspend fun analyzeNotes(regionId: String): List<PitchNote> = withContext(Dispatchers.Default) {
        val r = project.findRegion(regionId)?.second ?: return@withContext emptyList()
        val (_, audio) = OfflineRenderer.renderRegionsDry(pool, listOf(r.copy(muted = false)), sampleRate)
        val mono = FloatArray(audio.frames) { (audio.left[it] + audio.right[it]) * 0.5f }
        NoteSegmenter.detectNotes(mono, sampleRate)
    }

    /** Applies per-note transpositions (semitones, by note index) and renders a new region. */
    fun applyNoteShifts(notes: List<PitchNote>, shifts: Map<Int, Float>) {
        if (shifts.values.all { kotlin.math.abs(it) < 0.01f }) return
        processRegion("Flex Pitch") { a, prog ->
            val outL = a.left.copyOf(); val outR = a.right.copyOf()
            val fade = 256
            var done = 0
            for ((i, note) in notes.withIndex()) {
                val st = shifts[i] ?: continue
                if (kotlin.math.abs(st) < 0.01f) continue
                val s0 = (note.start - fade).coerceAtLeast(0)
                val s1 = (note.end + fade).coerceAtMost(a.frames)
                val seg = a.copyRange(s0, s1)
                val shifted = TimePitch.pitchShift(seg, st.toDouble())
                for (k in 0 until shifted.frames) {
                    val idx = s0 + k
                    if (idx >= a.frames) break
                    val w = when {
                        k < fade -> k.toFloat() / fade
                        k >= shifted.frames - fade -> (shifted.frames - k).toFloat() / fade
                        else -> 1f
                    }
                    outL[idx] = outL[idx] * (1 - w) + shifted.left[k] * w
                    outR[idx] = outR[idx] * (1 - w) + shifted.right[k] * w
                }
                done++
                prog(done.toFloat() / shifts.size)
            }
            AudioBuffer(outL, outR, a.sampleRate)
        }
    }

    // ------------------------------------------------------------------ Match EQ learning

    private suspend fun trackAudio(trackId: String): AudioBuffer? = withContext(Dispatchers.Default) {
        val t = project.track(trackId) ?: return@withContext null
        if (t.regions.isEmpty()) return@withContext null
        OfflineRenderer.renderRegionsDry(pool, t.regions.map { it.copy(muted = false) }, sampleRate).second
    }

    /** Learns the reference ("template") spectrum from the selected region. */
    fun learnMatchTemplate(slotId: String) {
        val id = selectedRegions.singleOrNull() ?: return toast("레퍼런스(템플릿)로 쓸 리전을 선택하세요")
        runBusy("레퍼런스 스펙트럼 분석 중…") {
            val bands = withContext(Dispatchers.Default) {
                val r = project.findRegion(id)?.second ?: return@withContext null
                val a = OfflineRenderer.renderRegionsDry(pool, listOf(r.copy(muted = false)), sampleRate).second
                SpectrumMatch.thirdOctaveLevels(a, THIRD_OCTAVE)
            } ?: return@runBusy
            matchTemplates[slotId] = bands
            matchTemplateReady = matchTemplateReady + slotId
            toast("템플릿 학습 완료. 이제 '현재 학습 + 매치'를 누르세요")
        }
    }

    /** Learns the current material of the owner track and writes the matching curve into the EQ. */
    fun learnMatchCurrent(ownerId: String, slotId: String) {
        val template = matchTemplates[slotId] ?: return toast("먼저 템플릿을 학습하세요")
        runBusy("현재 소리 분석 및 매칭 중…") {
            val audio = if (ownerId == MASTER_ID || project.bus(ownerId) != null) {
                withContext(Dispatchers.Default) { OfflineRenderer.bounce(project, pool, 0, minOf(project.contentEnd, sampleRate * 90L)) }
            } else trackAudio(ownerId)
            if (audio == null) { toast("분석할 오디오가 없습니다"); return@runBusy }
            val current = withContext(Dispatchers.Default) { SpectrumMatch.thirdOctaveLevels(audio, THIRD_OCTAVE) }
            val curve = SpectrumMatch.matchCurve(template, current)
            edit("Match EQ") { p ->
                updatePlugins(p, ownerId) { l ->
                    l.map { s -> if (s.id != slotId) s else s.copy(params = s.params + curve.withIndex().associate { (i, g) -> "g$i" to g }) }
                }
            }
            toast("매칭 커브 적용 완료")
        }
    }

    // ------------------------------------------------------------------ media / loop browser

    fun openBrowserFolder(tree: Uri) {
        browser.persist(tree)
        browserTree = tree
        browserPath = emptyList()
        refreshBrowser()
    }

    fun browseInto(item: BrowserItem) {
        browserPath = browserPath + (browser.docId(item.uri) to item.name)
        refreshBrowser()
    }

    fun browseUp() {
        browserPath = browserPath.dropLast(1)
        refreshBrowser()
    }

    private fun refreshBrowser() {
        val tree = browserTree ?: return
        viewModelScope.launch {
            browserItems = runCatching { withContext(Dispatchers.IO) { browser.list(tree, browserPath.lastOrNull()?.first) } }
                .getOrElse { toast("폴더를 읽을 수 없습니다: ${it.message}"); emptyList() }
        }
    }

    fun togglePreview(item: BrowserItem) {
        if (previewing == item.uri) {
            browser.stop(); previewing = null
        } else runCatching {
            browser.preview(item.uri) { previewing = null }
            previewing = item.uri
        }.onFailure { toast("미리듣기 실패: ${it.message}") }
    }

    fun stopPreview() { browser.stop(); previewing = null }

    // ------------------------------------------------------------------ export & Google Drive

    private fun exportRangeFrames(which: ExportRange): Pair<Long, Long>? {
        val p = project
        return when (which) {
            ExportRange.SONG -> if (p.contentEnd > 0) 0L to p.contentEnd else null
            ExportRange.CYCLE -> if (p.cycleEnd > p.cycleStart) p.cycleStart to p.cycleEnd else null
            ExportRange.SELECTION -> range?.let { it.start to it.end }
        }
    }

    private suspend fun renderToFile(format: ExportFormat, which: ExportRange, tail: Boolean, loudness: LoudnessTarget, progress: (Float) -> Unit): File {
        val (from, to) = exportRangeFrames(which) ?: error("내보낼 구간이 없습니다")
        val snapshot = project
        return withContext(Dispatchers.Default) {
            var audio = OfflineRenderer.bounce(snapshot, pool, from, to, if (tail) 2f else 0f, { progress(it * 0.7f) }) { isBusyCancelled() }
            val measured = LoudnessMeter.measure(audio)
            loudness.lufs?.let { target ->
                val g = LoudnessMeter.dbToLinear(LoudnessMeter.normalizationGain(measured, target, -1f))
                audio = AudioBuffer(FloatArray(audio.frames) { audio.left[it] * g }, FloatArray(audio.frames) { audio.right[it] * g }, audio.sampleRate)
            }
            lastLoudness = LoudnessMeter.measure(audio)
            val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "${ProjectStore.sanitize(snapshot.name)}.${format.extension}")
            when (format) {
                ExportFormat.M4A -> AacEncoder.encode(audio, file) { progress(0.7f + it * 0.3f) }
                ExportFormat.MP3_320, ExportFormat.MP3_192 -> file.outputStream().buffered().use {
                    Mp3Encoder.encode(audio, it, if (format == ExportFormat.MP3_320) 320 else 192, { p -> progress(0.7f + p * 0.3f) }) { isBusyCancelled() }
                }
                else -> {
                    val wf = when (format) {
                        ExportFormat.WAV24 -> WavFormat.PCM24
                        ExportFormat.WAV32F -> WavFormat.FLOAT32
                        else -> WavFormat.PCM16
                    }
                    file.outputStream().buffered().use { WavCodec.write(audio, it, wf) }
                }
            }
            progress(1f)
            file
        }
    }

    /**
     * Renders the mix and delivers it. [deviceUri] is required for [ExportTarget.DEVICE];
     * [driveToken] for [ExportTarget.DRIVE]; [onShare] receives the file for [ExportTarget.SHARE].
     */
    /** Loudness of the last bounce (shown after export). */
    var lastLoudness: com.musiccut.core.dsp.LoudnessResult? = null
        private set

    fun export(
        format: ExportFormat,
        which: ExportRange,
        target: ExportTarget,
        tail: Boolean,
        loudness: LoudnessTarget = LoudnessTarget.OFF,
        deviceUri: Uri? = null,
        driveToken: (suspend () -> String)? = null,
        onShare: (File, String) -> Unit = { _, _ -> },
    ) {
        runBusy("믹스다운 중…", cancellable = true) { progress ->
            val file = renderToFile(format, which, tail, loudness) { progress(if (target == ExportTarget.DRIVE) it * 0.5f else it) }
            val loud = lastLoudness?.let { " · %.1f LUFS / %.1f dBTP".format(it.integratedLufs, it.truePeakDb) } ?: ""
            when (target) {
                ExportTarget.DEVICE -> {
                    val uri = deviceUri ?: error("저장 위치가 없습니다")
                    withContext(Dispatchers.IO) {
                        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                            ?: error("파일을 열 수 없습니다")
                    }
                    toast("저장 완료$loud")
                }
                ExportTarget.DRIVE -> {
                    busy = busy?.copy(message = "Google Drive 로그인…")
                    val token = driveToken?.invoke() ?: error("Drive 인증을 사용할 수 없습니다")
                    busy = busy?.copy(message = "Google Drive에 업로드 중…")
                    val result: DriveFile = withContext(Dispatchers.IO) {
                        drive.upload(token, file, file.name, format.mime) { progress(0.5f + it * 0.5f) }
                    }
                    toast("Drive 업로드 완료: ${DriveUploader.DEFAULT_FOLDER}/${result.name}$loud")
                }
                ExportTarget.SHARE -> onShare(file, format.mime)
            }
        }
    }

    fun suggestedExportName(format: ExportFormat) = "${ProjectStore.sanitize(project.name)}.${format.extension}"

    /** Uploads the whole project (JSON + audio) as individual files into a Drive sub-folder. */
    fun backupProjectToDrive(driveToken: suspend () -> String) {
        runBusy("프로젝트를 Drive에 백업 중…") { progress ->
            saveNow()
            val dir = store.dirFor(project.name)
            val token = driveToken()
            val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: emptyList()
            withContext(Dispatchers.IO) {
                files.forEachIndexed { i, f ->
                    val mime = if (f.name.endsWith(".json")) "application/json" else "audio/wav"
                    drive.upload(token, f, f.name, mime, folderName = "${DriveUploader.DEFAULT_FOLDER} - ${project.name}")
                    progress((i + 1f) / files.size)
                }
            }
            toast("Drive 백업 완료 (${files.size}개 파일)")
        }
    }

    // ------------------------------------------------------------------ project files

    fun refreshProjects() {
        savedProjects = store.list()
    }

    private suspend fun saveNow() = withContext(Dispatchers.IO) {
        store.save(project, pool)
        dirty = false
    }

    fun save(asName: String? = null) {
        if (asName != null && asName.isNotBlank()) live { it.copy(name = asName.trim()) }
        runBusy("저장 중…") {
            saveNow()
            refreshProjects()
            toast("'${project.name}' 저장됨")
        }
    }

    fun open(name: String) {
        stop()
        runBusy("프로젝트 여는 중…") { progress ->
            val p = withContext(Dispatchers.IO) { store.load(name, pool, progress) }
            undoStack.clear()
            selectedRegions = emptySet()
            range = null
            selectedSectionId = null
            applyProject(p)
            selectedTrackId = p.tracks.firstOrNull()?.id
            dirty = false
            engine.seek(0)
        }
    }

    fun deleteProject(name: String) {
        store.delete(name)
        refreshProjects()
    }

    fun newProject() {
        stop()
        pool.clear()
        undoStack.clear()
        selectedRegions = emptySet()
        range = null
        selectedSectionId = null
        clipboard = null
        val (p, t) = EditOps.addTrack(Project(name = uniqueName("새 프로젝트"), sampleRate = sampleRate), "Audio 1")
        applyProject(p)
        selectedTrackId = t.id
        dirty = false
        engine.seek(0)
    }

    private fun uniqueName(base: String): String {
        if (!store.exists(base)) return base
        var i = 2
        while (store.exists("$base $i")) i++
        return "$base $i"
    }

    override fun onCleared() {
        browser.stop()
        engine.release()
        recorder.stop()
        super.onCleared()
    }
}
