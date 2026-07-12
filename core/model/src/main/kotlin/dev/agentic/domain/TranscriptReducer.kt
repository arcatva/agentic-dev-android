package dev.agentic.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Stateful transcript accumulator wrapping buildFromLog/applyEvent/groupTools/interleaveShared for incremental UI/ViewModel use.
 *  display() = full-pass groupTools ∘ interleaveShared ∘ markAnsweredAsks (correctness-first; phase 6 can narrow to tail-incremental).
 *  ─── Streaming-text (O(L) total, not O(L²)) ─── A length-L streamed answer arrives as many small `text`/`thinking` deltas. Pure appendText/appendThinking do `last.text + delta` = O(current length) per token → O(L²) total char copy. Without changing the node model, this reducer intercepts a run of consecutive same-kind deltas (same routing scope) into a [StringBuilder] ([streamBuf]) in amortised O(1) per token. Trailing node holds cheap placeholder; real text materialised (`streamBuf.toString()`, O(L)) lazily when [display]/[raw] reads via [materialize] BEFORE the transform pipeline runs, so output is byte-for-byte identical to old per-token concat. First delta of a run (and every non-streaming frame) still goes through [applyEvent]; run flushes the instant anything breaks it (different kind/scope, any non-text frame, reseed, setShared).
 *  ─── Thread-safety ─── Plain mutable state (var nodes/streamBuf/agentResults). Repository tries ONE coroutine per id, but forced reconnect can briefly overlap a still-finishing stream loop and concurrent followUp can open a second stream in the same window. Rather than depend on that invariant, every public entry point is @Synchronized on the instance so a reseed can't interleave a live frame fold (which would corrupt the node list / throw CME). Each id owns its own reducer → lock is per-session, never contended across sessions. */
class TranscriptReducer {
    private var nodes: List<Node> = emptyList()
    private var shared: List<AttachmentNode> = emptyList()
    /** Subagent results (toolUseId → returned text) seen on LIVE `agentResult` frames — absent from persisted log; remember to re-attach after every seedFromLog so the card stays stable across reseeds ("appear then disappear" fix). */
    private val agentResults = HashMap<String, String>()

    // Active streaming run — non-null only while a run of consecutive same-kind delta frames is open.
    // [streamBuf] = O(1) amortised accumulation; [streamKind] = "text"/"thinking" (other kind/frame ends run);
    // [streamParent] = routing scope (null/blank=top level, else SpawnNode.id whose children hold it — different scope ends run);
    // [streamNode] = exact parked instance in [nodes], tracked by identity for [materialize] (always tail of its scope; any structural change flushes first).
    private var streamBuf: StringBuilder? = null
    private var streamKind: String? = null
    private var streamParent: String? = null
    private var streamNode: Node? = null
    // True once this turn emitted a text/thinking token delta (partials ON). While set, SEALED complete-message text/thinking = redundant reassembly (deltas already built the node) → dropped. Reset per user `prompt`. Mirrors [buildFromLog]'s flag.
    private var streamedThisTurn = false

    /** One-shot build from persisted log (terminal sessions, history reload). Re-attaches any known live-only subagent results so the agent card keeps its body across the reseed.
     *  @deprecated Use [seedFromEvents] with Discord-style cursor-paginated events. */
    @Deprecated("Use seedFromEvents with structured events from GET /events endpoint")
    @Synchronized fun seedFromLog(log: List<String>) {
        // Reseed replaces whole node list — open run is gone; drop builder so a later delta starts fresh.
        resetStream()
        nodes = reattachResults(buildFromLog(log))
        // Carry last turn's streamed state forward so a live SEALED frame arriving right after this reseed (mid-stream reconnect) is still recognised as redundant: true iff tail turn (since last user prompt) had any `stream_event` delta line.
        streamedThisTurn = log.asReversed().asSequence()
            .takeWhile { !it.contains("\"type\":\"agentic_prompt\"") }
            .any { it.contains("\"type\":\"stream_event\"") }
    }

    /** One-shot build from structured events (Discord-style REST). Each = pre-parsed ClaudeEvent::to_wire() JSON; no raw JSONL re-parse. Re-attaches live-only subagent results. */
    @Synchronized fun seedFromEvents(events: List<JsonElement>) {
        resetStream()
        var list = emptyList<Node>()
        for (el in events) {
            val o = el.jsonObject
            val (next, _) = applyEvent(list, o)
            list = next
        }
        nodes = reattachResults(markAnsweredAsks(list))
        // Structured events are sealed (no deltas) — no streaming dedup needed.
        streamedThisTurn = false
    }

    /** Result of folding one live frame: [ended] (engineExit?) and [busy] turn-state signal — both derived from a SINGLE parse of the frame inside [applyEvent], so the caller need not re-parse. */
    data class FrameResult(val ended: Boolean, val busy: Boolean?)

    /** Apply one live WS frame; returns true when session ended (engineExit). Thin wrapper over [applyFrameWithBusy] for callers that only need the ended flag. */
    @Synchronized fun applyFrame(frame: String): Boolean = applyFrameWithBusy(frame).ended

    /** Apply one live WS frame, returning BOTH ended flag and busy signal from one parse. */
    @Synchronized fun applyFrameWithBusy(frame: String): FrameResult {
        // Fast path: a `text`/`thinking` delta that CONTINUES the open run is appended to the builder in O(1) amortised, never touched by applyEvent (no `oldText + delta` allocation). Returns same (ended=false, busy=true) FrameResult applyEvent would have for that kind.
        streamDelta(frame)?.let { return it }
        // Any other frame ends the run first (builder text must be source of truth again before applyEvent reads/copies these nodes), then folds normally.
        flushStream()
        // Streaming dedup + turn tracking (partials ON): track whether this turn streamed token deltas; DROP a SEALED (delta:false) text/thinking frame that merely restates an already-streamed block. First delta of a run (no open run yet) → mark streamed, fall through to applyEvent which creates the node. Non-streamed turn (old log / parked before any token) renders its sealed block normally.
        run {
            val o = try { STREAM_J.parseToJsonElement(frame).jsonObject } catch (e: Exception) { return@run }
            val kind = o["kind"]?.jsonPrimitive?.contentOrNull
            val isDelta = o["delta"]?.jsonPrimitive?.booleanOrNull == true
            when {
                kind == "prompt" -> streamedThisTurn = false
                (kind == "text" || kind == "thinking") && isDelta -> streamedThisTurn = true
                (kind == "text" || kind == "thinking") && streamedThisTurn ->
                    return CONTINUE_DELTA   // sealed reassembly of streamed block — drop, nodes untouched
            }
        }
        val (next, ended, busy) = applyEvent(nodes, frame)
        nodes = next
        // Remember subagent results now attached live (the log lacks them) so they survive a reseed.
        for (n in next) if (n is SpawnNode && n.id.isNotEmpty() && n.result != null) agentResults[n.id] = n.result!!
        // Start (or restart) a streaming run on the FIRST delta of a run: applyEvent just created the trailing Text/ThinkingNode; seed the builder from its text so subsequent deltas of the same run accumulate cheaply. Only when the frame really produced a streaming node at the routed tail.
        beginStreamIfDelta(frame)
        return FrameResult(ended, busy)
    }

    /** Replace the outbox attachment list (fetched separately). */
    @Synchronized fun setShared(shared: List<AttachmentNode>) {
        this.shared = shared
    }

    /** Raw node list before view transforms (ungrouped; persistence/debugging). Materialises any open streaming run first so the parked node carries its real accumulated text. */
    @get:Synchronized val raw: List<Node> get() { materialize(); return nodes }

    /** View-ready list: dedupePrompts ∘ markAnsweredAsks ∘ groupTools ∘ interleaveShared. dedupePrompts FIRST (innermost) so every fold (applyFrame append + seedFromLog replace) converges to one node per logical user turn, regardless of which ingestion path (live/log replay/reseed/backfill) re-materialized it. Materialises any open streaming run BEFORE the pipeline so displayed text = full delta concatenation. */
    @Synchronized fun display(): List<Node> {
        materialize()
        return interleaveShared(groupTools(markAnsweredAsks(dedupePrompts(nodes))), shared)
    }

    /** Re-attach remembered live-only subagent results onto matching SpawnNodes the log rebuilt without one. Only fills a NULL result on an EXISTING card (keyed by tool_use id) — never injects nodes. */
    private fun reattachResults(ns: List<Node>): List<Node> =
        if (agentResults.isEmpty()) ns
        else ns.map { n ->
            if (n is SpawnNode && n.result == null) agentResults[n.id]?.let { n.copy(result = it) } ?: n else n
        }

    // ─── Streaming-run helpers ───

    /** If [frame] is a `text`/`thinking` delta that continues the OPEN run (same kind + same routing scope), append delta text to builder and return the FrameResult applyEvent would have produced (ended=false, busy=true). Null if no open run or frame doesn't continue it (caller flushes + delegates to applyEvent). */
    private fun streamDelta(frame: String): FrameResult? {
        val buf = streamBuf ?: return null
        val o = try { STREAM_J.parseToJsonElement(frame).jsonObject } catch (e: Exception) { return null }
        // Only `delta:true` continues the run; SEALED (delta absent/false) text/thinking falls through to applyFrameWithBusy, which drops it when this turn already streamed.
        if (o["delta"]?.jsonPrimitive?.booleanOrNull != true) return null
        val kind = o["kind"]?.jsonPrimitive?.contentOrNull
        if (kind != streamKind) return null
        if (norm(o["parentToolUseId"]?.jsonPrimitive?.contentOrNull) != norm(streamParent)) return null
        // Identity guard: parked node must still be the tail of its scope. Any structural change would have flushed first, so this normally holds; if it doesn't, bail to safe path.
        if (tailOfScope(streamParent) !== streamNode) return null
        buf.append(o["text"]?.jsonPrimitive?.contentOrNull ?: "")
        streamedThisTurn = true
        return CONTINUE_DELTA
    }

    /** After applyEvent folds a frame, if it was a `text`/`thinking` delta that started a fresh run, open the builder seeded from the just-created trailing node's text. */
    private fun beginStreamIfDelta(frame: String) {
        val o = try { STREAM_J.parseToJsonElement(frame).jsonObject } catch (e: Exception) { return }
        val kind = o["kind"]?.jsonPrimitive?.contentOrNull
        if (kind != "text" && kind != "thinking") return
        val parent = o["parentToolUseId"]?.jsonPrimitive?.contentOrNull
        val tail = tailOfScope(parent)
        // applyEvent only coalesces into a Text/ThinkingNode at the routed tail; if delta was empty (no node change) or routing fell back elsewhere, no run to track.
        val isStreamTail = (kind == "text" && tail is TextNode) || (kind == "thinking" && tail is ThinkingNode)
        if (!isStreamTail) return
        streamKind = kind
        streamParent = parent
        streamNode = tail
        streamBuf = StringBuilder(textOf(tail!!))
    }

    /** Write open run's accumulated text back into its parked node and drop the builder — making the materialised String node source of truth again. Called before any non-streaming fold. */
    private fun flushStream() {
        if (streamBuf == null) return
        materialize()
        resetStream()
    }

    /** Drop the open run without writing back (nodes were already replaced wholesale, e.g. reseed). */
    private fun resetStream() {
        streamBuf = null
        streamKind = null
        streamParent = null
        streamNode = null
    }

    /** Replace the parked streaming node in [nodes] with one carrying `streamBuf.toString()` (O(L), run once per snapshot — not per token). Keeps builder open so streaming can continue after a snapshot. No-op when no run is open or node hasn't changed. */
    private fun materialize() {
        val buf = streamBuf ?: return
        val node = streamNode ?: return
        val full = buf.toString()
        if (textOf(node) == full) return  // already current — avoid a needless node copy
        val updated = withText(node, full)
        nodes = replaceInScope(nodes, streamParent, node, updated)
        streamNode = updated  // keep tracking the live instance so further deltas stay attached
    }

    /** Tail node of routing scope [parent] (null/blank = top level; else children of last SpawnNode with that id). Null if scope empty/missing. */
    private fun tailOfScope(parent: String?): Node? =
        scopeList(nodes, parent)?.lastOrNull()

    private fun scopeList(ns: List<Node>, parent: String?): List<Node>? {
        if (parent.isNullOrBlank()) return ns
        val spawn = ns.lastOrNull { it is SpawnNode && it.id == parent } as? SpawnNode ?: return null
        return spawn.children
    }

    /** Return [ns] with [old] (matched by identity at the tail of scope [parent]) replaced by [new]. */
    private fun replaceInScope(ns: List<Node>, parent: String?, old: Node, new: Node): List<Node> {
        if (parent.isNullOrBlank()) {
            val i = ns.indexOfLast { it === old }
            if (i < 0) return ns
            return ArrayList<Node>(ns).also { it[i] = new }
        }
        val si = ns.indexOfLast { it is SpawnNode && it.id == parent }
        if (si < 0) return ns
        val spawn = ns[si] as SpawnNode
        val ci = spawn.children.indexOfLast { it === old }
        if (ci < 0) return ns
        val newChildren = ArrayList<Node>(spawn.children).also { it[ci] = new }
        return ArrayList<Node>(ns).also { it[si] = spawn.copy(children = newChildren) }
    }

    private fun textOf(n: Node): String = when (n) {
        is TextNode -> n.text
        is ThinkingNode -> n.text
        else -> ""
    }

    private fun withText(n: Node, t: String): Node = when (n) {
        is TextNode -> n.copy(text = t)
        is ThinkingNode -> n.copy(text = t)
        else -> n
    }

    private fun norm(s: String?): String = if (s.isNullOrBlank()) "" else s

    private companion object {
        val STREAM_J = Json { ignoreUnknownKeys = true }
        // Every continued delta produces the same FrameResult applyEvent gives a text/thinking frame.
        val CONTINUE_DELTA = FrameResult(ended = false, busy = true)
    }
}
