package dev.agentic.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Stateful transcript accumulator. Wraps buildFromLog/applyEvent/groupTools/interleaveShared
 *  into a single object that UI or a ViewModel can drive incrementally.
 *
 *  Correctness-first implementation: display() does a full-pass groupTools ∘ interleaveShared ∘
 *  markAnsweredAsks on every call. Phase 6 can narrow this to a tail-incremental path.
 *
 *  ── Streaming-text accumulation (O(L) total, not O(L²)) ──────────────────────────────────────
 *  A length-L streamed answer arrives as many small `text`/`thinking` delta frames. The pure
 *  [appendText]/[appendThinking] coalesce each delta into the trailing node with `last.text + delta`,
 *  an immutable-String concat that is O(current length) per token → O(L²) total character copying for
 *  one answer. To avoid that WITHOUT changing the node model, this reducer intercepts a *run* of
 *  consecutive same-kind deltas (in the same routing scope) and accumulates them into a [StringBuilder]
 *  ([streamBuf]) in amortised O(1) per token. The trailing node it parks in [nodes] holds a cheap
 *  placeholder text; the real text is materialised (`streamBuf.toString()`, O(L)) lazily — only when a
 *  snapshot reader needs it ([display]/[raw]) — via [materialize], BEFORE the transform pipeline runs,
 *  so display() output is byte-for-byte identical to the old per-token concat. The first delta of a run
 *  (and every non-streaming frame) still goes through [applyEvent], so all routing/parsing is unchanged.
 *  The run is flushed (builder text written back into the node, builder dropped) the instant anything
 *  breaks it: a different kind/scope, any non-text frame, a reseed, or setShared. */
//
//  ── Thread-safety ────────────────────────────────────────────────────────────────────────────────
//  This holds plain mutable state (var nodes / streamBuf / agentResults). The repository's design tries
//  to keep ONE coroutine mutating each id's reducer, but a forced reconnect (SessionsRepository.restart)
//  can briefly overlap a still-finishing stream loop, and a concurrent followUp can open a second stream
//  in the same window. Rather than depend on that invariant never slipping, every public entry point that
//  reads or writes this state is @Synchronized on the instance, so a reseed can never interleave a live
//  frame fold (which would corrupt the node list / throw ConcurrentModificationException). Each id owns
//  its own reducer instance, so this lock is per-session and never contended across sessions.
class TranscriptReducer {
    private var nodes: List<Node> = emptyList()
    private var shared: List<AttachmentNode> = emptyList()
    /** Subagent results (toolUseId -> returned text) seen on LIVE `agentResult` frames. These never
     *  appear in the persisted log, so a reseed (turn end / reconnect) would otherwise drop them and an
     *  agent card's body would vanish ("appear then disappear"). Remember them and re-attach after
     *  every seedFromLog so the card stays stable across reseeds. */
    private val agentResults = HashMap<String, String>()

    // ── Active streaming run ─────────────────────────────────────────────────────────────────────
    // Non-null only while a run of consecutive same-kind delta frames is open. [streamBuf] accumulates
    // every delta of the run in O(1) amortised. [streamKind] is "text" or "thinking" (a delta of the
    // other kind, or any other frame, ends the run). [streamParent] is the routing scope the run lives
    // in (null/blank = top level; otherwise the SpawnNode.id whose children hold it) — a delta routed to
    // a different scope ends the run. [streamNode] is the exact node instance parked in [nodes] for this
    // run, tracked by identity so [materialize] can find and replace it (it is always the tail of its
    // scope, since the run is append-only and any structural change flushes first).
    private var streamBuf: StringBuilder? = null
    private var streamKind: String? = null
    private var streamParent: String? = null
    private var streamNode: Node? = null
    // True once the CURRENT turn has emitted a text/thinking token delta (partials ON). While set, a
    // SEALED complete-message text/thinking frame is the redundant reassembly and is dropped (the
    // deltas already built the node). Reset on each user `prompt`. Mirrors the same flag in buildFromLog.
    private var streamedThisTurn = false

    /** One-shot build from the persisted log (terminal sessions, history reload). Re-attaches any known
     *  live-only subagent results so an agent card keeps its body across the reseed.
     *  @deprecated Use [seedFromEvents] with Discord-style cursor-paginated events instead. */
    @Deprecated("Use seedFromEvents with structured events from GET /events endpoint")
    @Synchronized fun seedFromLog(log: List<String>) {
        // A reseed replaces the whole node list — any open streaming run is gone; drop the builder so a
        // later delta starts a fresh run against the rebuilt nodes.
        resetStream()
        nodes = reattachResults(buildFromLog(log))
        // Carry the last turn's streamed state forward so a live SEALED frame arriving right after this
        // reseed (mid-stream reconnect) is still recognised as redundant: true iff the tail turn (since
        // the last user prompt) contained any `stream_event` delta line.
        streamedThisTurn = log.asReversed().asSequence()
            .takeWhile { !it.contains("\"type\":\"agentic_prompt\"") }
            .any { it.contains("\"type\":\"stream_event\"") }
    }

    /** One-shot build from structured events (Discord-style REST endpoint). Each event is a pre-parsed
     *  ClaudeEvent::to_wire() JSON object — no raw JSONL re-parsing needed. Re-attaches any known
     *  live-only subagent results so an agent card keeps its body across the reseed. */
    @Synchronized fun seedFromEvents(events: List<JsonElement>) {
        resetStream()
        var list = emptyList<Node>()
        for (el in events) {
            val o = el.jsonObject
            val (next, _) = applyEvent(list, o)
            list = next
        }
        nodes = reattachResults(markAnsweredAsks(list))
        // Structured events are sealed (no deltas) — no streaming dedup needed. A live stream
        // arriving after this seed won't be confused by a lingering flag.
        streamedThisTurn = false
    }

    /** Result of folding one live frame: did the session end (engineExit), and the frame's turn-state
     *  [busy] signal (true = generating, false = turn finished, null = not a turn-state frame) — both
     *  derived from a SINGLE parse of the frame inside [applyEvent], so the caller need not re-parse it. */
    data class FrameResult(val ended: Boolean, val busy: Boolean?)

    /** Apply one live WS frame; returns true when the session has ended (engineExit).
     *  Thin wrapper over [applyFrameWithBusy] for callers that only need the ended flag. */
    @Synchronized fun applyFrame(frame: String): Boolean = applyFrameWithBusy(frame).ended

    /** Apply one live WS frame, returning BOTH the ended flag and the busy signal from one parse. */
    @Synchronized fun applyFrameWithBusy(frame: String): FrameResult {
        // Fast path: a `text`/`thinking` delta that CONTINUES the open run is appended to the builder in
        // O(1) amortised and never touched by applyEvent (so no `oldText + delta` allocation). It returns
        // exactly the same (ended=false, busy=true) FrameResult applyEvent would have for that kind.
        streamDelta(frame)?.let { return it }
        // Any other frame ends the run first (its builder text must be the source of truth again before
        // applyEvent reads/copies these nodes), then folds normally.
        flushStream()
        // Streaming dedup + turn tracking (partials ON): track whether this turn streamed token deltas,
        // and DROP a SEALED (delta:false) text/thinking frame that merely restates an already-streamed
        // block. The first delta of a run reaches here (no open run yet) → mark streamed, then fall
        // through to applyEvent which creates the node. A non-streamed turn (old log / parked before any
        // token) renders its sealed block normally.
        run {
            val o = try { STREAM_J.parseToJsonElement(frame).jsonObject } catch (e: Exception) { return@run }
            val kind = o["kind"]?.jsonPrimitive?.contentOrNull
            val isDelta = o["delta"]?.jsonPrimitive?.booleanOrNull == true
            when {
                kind == "prompt" -> streamedThisTurn = false
                (kind == "text" || kind == "thinking") && isDelta -> streamedThisTurn = true
                (kind == "text" || kind == "thinking") && streamedThisTurn ->
                    return CONTINUE_DELTA   // sealed reassembly of a streamed block — drop, nodes untouched
            }
        }
        val (next, ended, busy) = applyEvent(nodes, frame)
        nodes = next
        // Remember subagent results now attached live (the log lacks them) so they survive a reseed.
        for (n in next) if (n is SpawnNode && n.id.isNotEmpty() && n.result != null) agentResults[n.id] = n.result!!
        // Start (or restart) a streaming run on the FIRST delta of a run: applyEvent has just created the
        // trailing Text/ThinkingNode; seed the builder from its text so subsequent deltas of the same run
        // accumulate cheaply. Only when the frame really produced a streaming node at the routed tail.
        beginStreamIfDelta(frame)
        return FrameResult(ended, busy)
    }

    /** Replace the outbox attachment list (fetched separately). */
    @Synchronized fun setShared(shared: List<AttachmentNode>) {
        this.shared = shared
    }

    /** Raw node list before view transforms (ungrouped, for persistence / debugging). Materialises any
     *  open streaming run first so the parked node carries its real accumulated text. */
    @get:Synchronized val raw: List<Node> get() { materialize(); return nodes }

    /** View-ready list: dedupePrompts ∘ markAnsweredAsks ∘ groupTools ∘ interleaveShared applied.
     *  dedupePrompts runs FIRST (innermost) so every fold — applyFrame's append and seedFromLog's
     *  replace — converges to exactly one node per logical user turn, regardless of which ingestion
     *  path (live frame, log replay, reseed, backfill) re-materialized it. Materialises any open
     *  streaming run BEFORE the pipeline so the displayed text equals the full delta concatenation. */
    @Synchronized fun display(): List<Node> {
        materialize()
        return interleaveShared(groupTools(markAnsweredAsks(dedupePrompts(nodes))), shared)
    }

    /** Re-attach remembered live-only subagent results onto matching SpawnNodes the log rebuilt without
     *  one. Only fills a NULL result on an EXISTING card (keyed by tool_use id) — never injects nodes. */
    private fun reattachResults(ns: List<Node>): List<Node> =
        if (agentResults.isEmpty()) ns
        else ns.map { n ->
            if (n is SpawnNode && n.result == null) agentResults[n.id]?.let { n.copy(result = it) } ?: n else n
        }

    // ── Streaming-run helpers ────────────────────────────────────────────────────────────────────

    /** If [frame] is a `text`/`thinking` delta that continues the OPEN run (same kind + same routing
     *  scope), append its delta text to the builder and return the FrameResult applyEvent would have
     *  produced for that kind (ended=false, busy=true). Returns null if there is no open run or the
     *  frame does not continue it (caller then flushes + delegates to applyEvent). */
    private fun streamDelta(frame: String): FrameResult? {
        val buf = streamBuf ?: return null
        val o = try { STREAM_J.parseToJsonElement(frame).jsonObject } catch (e: Exception) { return null }
        // Only a `delta:true` frame continues the run; a SEALED (delta absent/false) text/thinking frame
        // falls through to applyFrameWithBusy, which drops it when this turn already streamed.
        if (o["delta"]?.jsonPrimitive?.booleanOrNull != true) return null
        val kind = o["kind"]?.jsonPrimitive?.contentOrNull
        if (kind != streamKind) return null
        if (norm(o["parentToolUseId"]?.jsonPrimitive?.contentOrNull) != norm(streamParent)) return null
        // Identity guard: the parked node must still be the tail of its scope. Any structural change
        // would have flushed first, so this normally holds; if it somehow doesn't, bail to the safe path.
        if (tailOfScope(streamParent) !== streamNode) return null
        buf.append(o["text"]?.jsonPrimitive?.contentOrNull ?: "")
        streamedThisTurn = true
        return CONTINUE_DELTA
    }

    /** After applyEvent folds a frame, if it was a `text`/`thinking` delta that started a fresh run,
     *  open the builder seeded from the just-created trailing node's text. */
    private fun beginStreamIfDelta(frame: String) {
        val o = try { STREAM_J.parseToJsonElement(frame).jsonObject } catch (e: Exception) { return }
        val kind = o["kind"]?.jsonPrimitive?.contentOrNull
        if (kind != "text" && kind != "thinking") return
        val parent = o["parentToolUseId"]?.jsonPrimitive?.contentOrNull
        val tail = tailOfScope(parent)
        // applyEvent only coalesces into a Text/ThinkingNode at the routed tail; if the delta was empty
        // (no node change) or routing fell back elsewhere, there is no run to track.
        val isStreamTail = (kind == "text" && tail is TextNode) || (kind == "thinking" && tail is ThinkingNode)
        if (!isStreamTail) return
        streamKind = kind
        streamParent = parent
        streamNode = tail
        streamBuf = StringBuilder(textOf(tail!!))
    }

    /** Write the open run's accumulated text back into its parked node and drop the builder — making the
     *  materialised String node the source of truth again. Called before any non-streaming fold. */
    private fun flushStream() {
        if (streamBuf == null) return
        materialize()
        resetStream()
    }

    /** Drop the open run without writing back (the nodes were already replaced wholesale, e.g. reseed). */
    private fun resetStream() {
        streamBuf = null
        streamKind = null
        streamParent = null
        streamNode = null
    }

    /** Replace the parked streaming node in [nodes] with one carrying `streamBuf.toString()` (O(L), run
     *  once per snapshot — not per token). Keeps the builder open so streaming can continue after a
     *  snapshot read. No-op when no run is open or the node has not changed. */
    private fun materialize() {
        val buf = streamBuf ?: return
        val node = streamNode ?: return
        val full = buf.toString()
        if (textOf(node) == full) return  // already current — avoid a needless node copy
        val updated = withText(node, full)
        nodes = replaceInScope(nodes, streamParent, node, updated)
        streamNode = updated  // keep tracking the live instance so further deltas stay attached
    }

    /** The tail node of the routing scope [parent] (null/blank = top level; otherwise the children of the
     *  last SpawnNode with that id). Null if the scope is empty or missing. */
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
