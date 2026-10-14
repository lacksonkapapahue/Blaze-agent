package com.blaze.assistant.ui

import java.io.File

/**
 * v0.23.3: how the Design view serves designs so 3D always draws, even on the Galaxy A12 (PowerVR GE8320,
 * where Chromium's GPU blocklist often turns WebGL2 off and three.js r163+ refuses to start).
 *
 *  - Designs load from a virtual https origin ([ORIGIN]) instead of file://, so the page, its importmap and
 *    the bundled three.js modules are all same-origin (no opaque "null" origin, no CORS):
 *      /designs/…  → the designs folder in Blaze's home (~/designs)
 *      /home/…     → anything else in Blaze's home
 *      /three/…    → three.js r170 bundled in assets/design/three (WebGL2)
 *      /three/r162/… → three.js r162 bundled in assets/design/three-r162 (the last build with WebGL1)
 *    CDN three.js URLs (jsDelivr, unpkg, cdnjs…) are still served from the APK as a fallback ([bundledThree]).
 *  - Every HTML page served gets [HOOK_JS] first in <head>: it catches errors (window.onerror, failed
 *    module/script loads, unhandled rejections), checks WebGL2/WebGL1, picks the three.js build, and
 *    reports gl / error / ready / fail to the Design view through the `BlazeDesign` bridge.
 *  - A three.js importmap is rewritten so the page imports the chosen same-origin build ([prepareHtml]), through
 *    the safety net (v0.23.5, assets/design/three-safe.js: null geometry / material objects are fixed and hidden
 *    instead of killing the scene), and a 3D page's parts are normalised ([com.blaze.assistant.agent.Designs.normalizeSpec]).
 *
 * Pure (no Android classes) so the mapping, rewriting and fallback choice are unit-tested (DesignRenderTest).
 */
object DesignServe {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val R170 = "r170"
    const val R162 = "r162"
    /** How long a 3D page may take to report ready before the Design view falls back (the A12 parses slowly). */
    const val READY_TIMEOUT_MS = 20_000L
    /** After the first error on a 3D page that isn't ready yet, wait this long before falling back. */
    const val ERROR_GRACE_MS = 2_000L
    const val ERROR_FILE = ".last_error.txt"
    /** v0.23.5: what a three.js importmap's "three" points at: the bundled build wrapped with the safety net. */
    const val SAFE_JS = "three.safe.js"
    const val SAFE_ASSET = "design/three-safe.js"

    // ---------------- URL mapping ----------------

    private fun enc(seg: String): String = java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
    private fun dec(seg: String): String = try { java.net.URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { seg }

    /** The virtual https URL for a file:// design URL under [home] (designs first), or [fileUrl] unchanged. */
    fun virtualUrl(fileUrl: String, home: File?): String {
        if (home == null || !fileUrl.startsWith("file://")) return fileUrl
        val f = File(fileUrl.removePrefix("file://"))
        val path = try { f.canonicalFile } catch (_: Exception) { return fileUrl }
        fun under(root: File): String? = try {
            val r = root.canonicalFile
            val rel = path.relativeToOrNull(r)?.path?.takeIf { !it.startsWith("..") && it.isNotEmpty() }
            rel?.split(File.separatorChar)?.joinToString("/") { enc(it) }
        } catch (_: Exception) { null }
        under(File(home, "designs"))?.let { return "$ORIGIN/designs/$it" }
        under(home)?.let { return "$ORIGIN/home/$it" }
        return fileUrl
    }

    /** What a request to the virtual origin maps to: a file in Blaze's home or an APK asset. */
    sealed class Target {
        abstract val mime: String
        data class Disk(val file: File, override val mime: String) : Target()
        data class Asset(val path: String, override val mime: String) : Target()
    }

    /** Maps [url] to a [Target], or null when it isn't ours (other origins pass through to the network). */
    fun resolve(url: String, home: File?): Target? {
        val clean = url.substringBefore('#').substringBefore('?')
        if (!clean.startsWith("$ORIGIN/", ignoreCase = true)) return null
        val path = clean.substring(ORIGIN.length)
        return when {
            path.startsWith("/three/") -> threeAsset(path.removePrefix("/three/"))?.let { Target.Asset(it, "text/javascript") }
            path.startsWith("/designs/") -> home?.let { disk(File(home, "designs"), path.removePrefix("/designs/")) }
            path.startsWith("/home/") -> home?.let { disk(home, path.removePrefix("/home/")) }
            else -> null
        }
    }

    private fun disk(root: File, rel: String): Target.Disk? {
        val parts = rel.split('/').filter { it.isNotEmpty() }.map { dec(it) }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." || it.contains('/') || it.contains('\\') }) return null
        var f = File(root, parts.joinToString(File.separator))
        val ok = try { f.canonicalPath.startsWith(root.canonicalPath + File.separator) || f.canonicalPath == root.canonicalPath } catch (_: Exception) { false }
        if (!ok) return null
        if (f.isDirectory) f = File(f, "index.html")
        return Target.Disk(f, mimeFor(f.name))
    }

    /** The APK asset for a /three/… path ("three.module.js", "addons/controls/OrbitControls.js", "r162/…"). */
    fun threeAsset(rest: String): String? {
        val r162 = rest.startsWith("$R162/")
        val tail = if (r162) rest.removePrefix("$R162/") else rest.removePrefix("$R170/")
        val name = when {
            Regex("^(build/)?three\\.module(\\.min)?\\.js$").matches(tail) -> "three.module.min.js"
            // v0.23.5: the safety net (null geometry / material can't stop a scene); one file for both builds,
            // it imports ./three.module.min.js next to the URL it was loaded from.
            tail == SAFE_JS -> return SAFE_ASSET
            tail.endsWith("/OrbitControls.js") || tail == "OrbitControls.js" -> "OrbitControls.js"
            tail.endsWith("/GLTFExporter.js") || tail == "GLTFExporter.js" -> "GLTFExporter.js"
            r162 && (tail.endsWith("/TextureUtils.js") || tail == "TextureUtils.js") -> "TextureUtils.js"
            else -> return null
        }
        return assetDir(if (r162) R162 else R170) + "/" + name
    }

    /** Where a bundled three.js build lives in the APK. */
    fun assetDir(engine: String): String = if (engine == R162) "design/three-r162" else "design/three"

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "glb" -> "model/gltf-binary"
        "gltf" -> "model/gltf+json"
        "wasm" -> "application/wasm"
        "txt", "md" -> "text/plain"
        "mmd", "mermaid" -> "text/plain"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    // ---------------- engine choice + fallback ----------------

    /** The three.js build for this WebView: r170 needs WebGL2; r162 still runs on WebGL1; null = no WebGL at all. */
    fun engineFor(gl2: Boolean, gl1: Boolean, forceGl1: Boolean = false): String? = when {
        gl2 && !forceGl1 -> R170
        gl1 -> R162
        gl2 -> R170
        else -> null
    }

    enum class Next { RETRY_WEBGL1, SKETCH, BANNER }

    /**
     * What to do when a 3D page fails: try the WebGL1 build once (when WebGL1 exists and it wasn't already
     * used), then the native drawing of the parts when the design has a parts list, else a banner over the page.
     */
    fun onFail(engine: String?, gl1: Boolean, hasParts: Boolean): Next = when {
        engine != R162 && gl1 -> Next.RETRY_WEBGL1
        hasParts -> Next.SKETCH
        else -> Next.BANNER
    }

    /** The URL to load for an attempt (the WebGL1 retry adds bzgl=1, which [HOOK_JS] reads). */
    fun attemptUrl(url: String, forceGl1: Boolean): String =
        if (!forceGl1 || !url.startsWith(ORIGIN)) url else url + (if (url.contains('?')) "&" else "?") + "bzgl=1"

    /** A console message that means the 3D engine failed rather than noise. */
    fun fatalConsole(msg: String): Boolean =
        Regex("(?i)failed to load module script|failed to resolve module specifier|shader error|error creating webgl context|webgl context lost|webgl2? (is )?not supported|cannot use import statement")
            .containsMatchIn(msg)

    // ---------------- HTML rewriting ----------------

    private val importMap = Regex("""<script\s+type\s*=\s*["']importmap["'][^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)

    /** Rewrites three.js entries of an importmap to the same-origin bundled [engine]; other entries stay. */
    fun rewriteImportMap(json: String, engine: String): String {
        val base = "$ORIGIN/three/" + (if (engine == R162) "$R162/" else "")
        return Regex("""("[^"\\]*(?:\\.[^"\\]*)*")\s*:\s*"([^"\\]*)"""").replace(json) { m ->
            val key = m.groupValues[1]; val v = m.groupValues[2]
            val asset = bundledThree(v)
            val nv = when {
                asset == "three.module.min.js" -> base + SAFE_JS
                asset != null -> base + "addons/" + v.substringAfter("/examples/jsm/", v.substringAfter("/addons/"))
                v.endsWith("/") && isThreeCdn(v) && (v.endsWith("/examples/jsm/") || v.endsWith("/addons/")) -> base + "addons/"
                else -> v
            }
            "$key: \"$nv\""
        }
    }

    private fun isThreeCdn(u: String) =
        Regex("^https?://(cdn\\.jsdelivr\\.net|unpkg\\.com|cdnjs\\.cloudflare\\.com|esm\\.sh|ga\\.jspm\\.io)/", RegexOption.IGNORE_CASE).containsMatchIn(u) &&
            u.contains("three", ignoreCase = true)

    /** True when [html] uses three.js through an importmap. */
    fun usesThree(html: String): Boolean = importMap.find(html)?.groupValues?.get(1)?.let { j ->
        Regex("\"([^\"]+)\"").findAll(j).any { isThreeCdn(it.groupValues[1]) }
    } == true

    /** A JavaScript string literal for [s], safe inside an inline <script>. */
    fun jsString(s: String): String {
        val b = StringBuilder("\"")
        for (ch in s) {
            val rep = when (ch) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                '<' -> "\\u003c"
                '>' -> "\\u003e"
                '\u2028' -> "\\u2028"
                '\u2029' -> "\\u2029"
                else -> null
            }
            if (rep != null) b.append(rep) else b.append(ch)
        }
        return b.append('"').toString()
    }

    /**
     * The page as the Design view serves it: [HOOK_JS] first in <head>, the old in-page "didn't load" banner hidden
     * (the Design view shows its own, with the real error and Retry), and a three.js importmap replaced by one
     * that is chosen at load time (r170 with WebGL2, r162 with only WebGL1) and points at the same origin.
     * [is3d] = a parts-based 3D design (expects window.__blazeScene).
     */
    fun prepareHtml(html: String, is3d: Boolean): String {
        var out = html
        // v0.23.5: the parts every renderer gets are normalised (known shapes, unique ids, objects only).
        if (is3d) com.blaze.assistant.agent.Designs.specFrom(out)?.let { out = com.blaze.assistant.agent.Designs.withSpec(out, com.blaze.assistant.agent.Designs.normalizeSpec(it)) }
        importMap.find(out)?.let { m ->
            val json = m.groupValues[1]
            if (usesThree(out)) {
                val tag = { e: String -> "<script type=\"importmap\">" + rewriteImportMap(json, e) + "</script>" }
                val chooser = "<script>document.write(window.__bzEngine===\"$R162\"?" + jsString(tag(R162)) + ":" + jsString(tag(R170)) + ");</script>"
                out = out.replaceRange(m.range, chooser)
            }
        }
        val hook = "<script>" + HOOK_JS.replace("__BZ_IS3D__", if (is3d) "true" else "false") + "</script>" +
            "<style>#bz-fail{display:none!important}</style>"
        val head = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(out)
        val html0 = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(out)
        return when {
            head != null -> out.substring(0, head.range.last + 1) + hook + out.substring(head.range.last + 1)
            html0 != null -> out.substring(0, html0.range.last + 1) + "<head>" + hook + "</head>" + out.substring(html0.range.last + 1)
            else -> hook + out
        }
    }

    /**
     * Injected first into every page the Design view serves. Talks to the `BlazeDesign` bridge:
     * gl(level, hasWebGL1, engine), error(msg), ready(engine), fail(msg). The engine choice mirrors [engineFor].
     */
    val HOOK_JS = """
(function(){
var B=window.BlazeDesign||null, IS3D=__BZ_IS3D__;
var errs=window.__bzErrors=window.__bzErrors||[];
function say(k,a,b,c){try{if(B&&B[k]){if(c!==undefined)B[k](a,b,c);else if(b!==undefined)B[k](a,b);else B[k](a===undefined?"":String(a).slice(0,600));}}catch(e){}}
function err(m){m=String(m||"Unknown error");if(errs.length<20)errs.push(m);say("error",m);}
addEventListener("error",function(e){var t=e.target;
 if(t&&t!==window&&t.tagName){err("Couldn't load "+(t.src||t.href||t.tagName));return;}
 err((e.message||"Script error")+(e.filename?" ("+String(e.filename).split("/").pop()+":"+e.lineno+")":""));},true);
addEventListener("unhandledrejection",function(e){var r=e.reason;err("Unhandled: "+((r&&(r.message||r))||"promise rejected"));});
function has(n){try{var c=document.createElement("canvas");var g=c.getContext(n);if(g){var l=g.getExtension("WEBGL_lose_context");if(l)l.loseContext();return true;}}catch(e){}return false;}
var gl2=has("webgl2"), gl1=has("webgl")||has("experimental-webgl");
var force1=/[?&]bzgl=1/.test(location.search);
var eng=(gl2&&!force1)?"r170":(gl1?"r162":(gl2?"r170":""));
window.__bzEngine=eng;
say("gl",gl2?2:(gl1?1:0),gl1,eng);
if(!IS3D)return;
if(!eng){say("fail","WebGL is turned off in this phone's WebView (no WebGL 1 or 2), so three.js can't draw.");return;}
function blank(cv){if(!cv||!cv.width||!cv.height)return false;var s=24,c=document.createElement("canvas");c.width=s;c.height=s;
 var x=c.getContext("2d");x.drawImage(cv,0,0,s,s);var d=x.getImageData(0,0,s,s).data,r=d[0],g=d[1],b=d[2],n=0;
 for(var i=0;i<d.length;i+=4){if(Math.abs(d[i]-r)>10||Math.abs(d[i+1]-g)>10||Math.abs(d[i+2]-b)>10)n++;}return n<3;}
var t0=Date.now();
var poll=setInterval(function(){var s=window.__blazeScene;if(!s)return;clearInterval(poll);
 var cv=s.renderer&&s.renderer.domElement;
 if(cv)cv.addEventListener("webglcontextlost",function(){err("WebGL context lost");say("fail","The 3D view lost its WebGL context ("+eng+").");});
 var tries=0;(function check(){setTimeout(function(){var bl=false;try{bl=blank(cv);}catch(e){}
  if(!bl){window.__bzReady=true;say("ready",eng);}else if(++tries<3)check();else say("fail","The 3D engine ("+eng+") started but drew nothing.");},1200);})();},100);
})();
""".trim()

    // ---------------- error file ----------------

    /** The text of ~/designs/.last_error.txt: when, which design, which engine, and the last errors (newest last). */
    fun errorReport(design: String, engine: String?, glLevel: Int, errors: List<String>, at: Long): String {
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(at))
        return "Design view render error ($time)\n" +
            "design: $design\n" +
            "engine: ${engine?.ifBlank { null } ?: "none"} (WebGL ${if (glLevel <= 0) "unavailable" else glLevel.toString()})\n" +
            errors.takeLast(10).joinToString("\n") { "- " + it.replace('\n', ' ').take(500) } + "\n"
    }

    // ---------------- Mermaid files (v0.26.1) ----------------
    // Jack's A12: a .mmd file opened in Design showed its raw "flowchart TD / Sun[Sunlight] -->…" source as text on a
    // white page. A Mermaid file (by extension, or text that starts like Mermaid) is now drawn as a picture instead.

    val MERMAID_EXT = setOf("mmd", "mermaid")
    private val mermaidStart = Regex("""^\s*(?:%%[^\n]*\n\s*)*(?:---\n[\s\S]*?\n---\s*\n\s*)?""" +
        """(?:flowchart\b|graph\s+(?:TB|TD|BT|RL|LR)\b|sequenceDiagram\b|classDiagram(?:-v2)?\b|stateDiagram(?:-v2)?\b|erDiagram\b|journey\b|gantt\b|""" +
        """pie(?:\s+(?:title|showData)\b|\s*\n)|mindmap\b|timeline\b|gitGraph\b|quadrantChart\b|requirementDiagram\b|C4(?:Context|Container|Component)\b|""" +
        """sankey-beta\b|xychart-beta\b|block-beta\b)""")
    private val mdFence = Regex("""^\s*```\s*mermaid\s*\n([\s\S]*?)\n\s*```\s*$""")

    /** Does [text] read as Mermaid source (it starts with a diagram keyword such as flowchart, graph TD, sequenceDiagram, mindmap…)? */
    fun looksMermaid(text: String): Boolean = mermaidStart.containsMatchIn(text.take(4000))

    /**
     * The Mermaid source to draw for a previewed file, or null when it isn't Mermaid: a .mmd / .mermaid file; a .md
     * that is just one ```mermaid block; or a .txt / extension-less file whose text starts like Mermaid. HTML, SVG,
     * pictures and the rest are never touched.
     */
    fun mermaidSource(name: String, text: String): String? {
        val ext = name.substringAfterLast('/').let { if (it.contains('.')) it.substringAfterLast('.').lowercase() else "" }
        val t = text.replace("\r\n", "\n").trim()
        if (t.isEmpty()) return null
        return when (ext) {
            in MERMAID_EXT -> mdFence.matchEntire(t)?.groupValues?.get(1)?.trim() ?: t
            "md", "markdown" -> mdFence.matchEntire(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: t.takeIf { looksMermaid(it) }
            "txt", "" -> t.takeIf { looksMermaid(it) }
            else -> null
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * A page that draws [code] as a picture: the mermaid.ink image (as the chat's diagrams), and if that can't load,
     * mermaid.js from jsDelivr. The source sits in a hidden element for mermaid.js and is never shown as text; if
     * neither renderer can be reached the page says so in one plain line.
     */
    fun mermaidHtml(code: String, title: String = "Diagram"): String {
        val img = RichReplies.diagramUrl("mermaid", code)
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>" + esc(title) + "</title><style>html,body{margin:0;background:#fff;font-family:sans-serif}" +
            "#wrap{display:flex;align-items:center;justify-content:center;min-height:100vh;padding:16px;box-sizing:border-box}" +
            "#d{max-width:100%;height:auto;border-radius:12px}#src{display:none}#msg{display:none;color:#5a5a5e;font-size:15px;text-align:center}" +
            ".mermaid svg{max-width:100%;height:auto}</style></head><body><div id=\"wrap\">" +
            "<img id=\"d\" alt=\"" + esc(title) + "\" src=\"" + esc(img) + "\" onerror=\"blazeMermaidFallback()\">" +
            "<div id=\"out\" class=\"mermaid\"></div><p id=\"msg\">Couldn\u2019t draw this diagram without a connection. Tap Reload to try again.</p></div>" +
            "<script type=\"text/plain\" id=\"src\">" + esc(code) + "</script>" +
            "<script>function blazeMermaidFallback(){document.getElementById('d').style.display='none';" +
            "var s=document.createElement('script');s.src='https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js';" +
            "s.onload=function(){try{var t=document.createElement('textarea');t.innerHTML=document.getElementById('src').textContent;" +
            "mermaid.initialize({startOnLoad:false,theme:'default'});mermaid.render('blazeM',t.value).then(function(r){document.getElementById('out').innerHTML=r.svg;})" +
            ".catch(function(){document.getElementById('msg').style.display='block';});}catch(e){document.getElementById('msg').style.display='block';}};" +
            "s.onerror=function(){document.getElementById('msg').style.display='block';};document.head.appendChild(s);}</script></body></html>"
    }
}
