package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.HighlightColor
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import burp.api.montoya.ui.editor.EditorOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessType
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val DEFAULT_HIGHLIGHT = "MAGENTA"
private const val DEFAULT_WIDTH = 1600
private const val DEFAULT_HEIGHT = 900
private const val MAX_WIDTH = 4000
private const val MAX_HEIGHT = 8000
private const val MAX_IMAGE_BYTES = 256L * 1024 * 1024
private const val MAX_RETURNED_IMAGE_EDGE = 7900

/**
 * Directory that proxy history snapshots are written to. The location is fixed so an MCP
 * client can only choose a file name, never an arbitrary path on disk.
 */
val SNAPSHOT_DIRECTORY: Path = Paths.get(System.getProperty("user.home"), "BurpMCP", "screenshots")

fun Server.registerProxyEvidenceTools(api: MontoyaApi, config: McpConfig) {

    mcpTool<HighlightProxyHistoryItems>(
        "Highlights one or more Proxy HTTP history items by their '#' id (the 'id' field returned by " +
            "get_proxy_http_history) and optionally sets their notes, so the user can find evidence quickly in " +
            "Burp's UI. color is one of RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PINK, MAGENTA, GRAY, or NONE to " +
            "clear (default MAGENTA). notes: omit to leave unchanged, empty string to clear."
    ) {
        if (!checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")) {
            return@mcpTool "HTTP history access denied by Burp Suite"
        }
        val highlight = parseHighlightColor(color)
        val wanted = ids.toSet()
        val items = api.proxy().history { it.id() in wanted }.associateBy { it.id() }

        ids.joinToString("\n") { id ->
            val item = items[id] ?: return@joinToString "#$id: not found in proxy history"
            item.annotations().setHighlightColor(highlight)
            notes?.let { item.annotations().setNotes(it) }
            "#$id: highlighted ${item.annotations().highlightColor()}, notes=\"${item.annotations().notes() ?: ""}\""
        }
    }

    mcpTool<ScreenshotProxyHistoryItem>(
        "Renders a Proxy HTTP history item's request and response with Burp's own message editors and saves a " +
            "PNG to ${SNAPSHOT_DIRECTORY}, for use as finding evidence. id is the '#' id from get_proxy_http_history. " +
            "searchTerm highlights matches in both panes (like Burp's search bar) and scrolls each pane to its first " +
            "match, use it to bring a specific part of a long response into view. fullHeight=true grows the image " +
            "to show the whole message (capped at $MAX_HEIGHT px). layout is SIDE_BY_SIDE (default, like Burp) or " +
            "STACKED (request above response, better for long lines). useOriginal=true shows the original instead of " +
            "the edited request/response for intercepted items. fileName (optional) is a plain file name, saved " +
            "inside the fixed screenshot directory. returnImage=true also returns the PNG inline."
    ) {
        if (!checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")) {
            return@mcpTool listOf(TextContent("HTTP history access denied by Burp Suite"))
        }
        val item = api.proxy().history { it.id() == id }.firstOrNull()
            ?: return@mcpTool listOf(TextContent("#$id: not found in proxy history"))

        if (GraphicsEnvironment.isHeadless()) {
            return@mcpTool listOf(TextContent("Screenshots need Burp's UI; Burp is running headless."))
        }

        val request = if (useOriginal == true) item.request() else item.finalRequest()
        val response = when {
            !item.hasResponse() -> null
            useOriginal == true -> item.originalResponse()
            else -> item.response()
        }

        val options = SnapshotOptions(
            width = (width ?: DEFAULT_WIDTH).coerceIn(400, MAX_WIDTH),
            height = (height ?: DEFAULT_HEIGHT).coerceIn(300, MAX_HEIGHT),
            stacked = parseLayout(layout),
            searchTerm = searchTerm?.takeIf { it.isNotBlank() },
            fullHeight = fullHeight == true
        )

        val snapshot = MessageSnapshotRenderer.render(api, request, response, options)

        Files.createDirectories(SNAPSHOT_DIRECTORY)
        val target = SNAPSHOT_DIRECTORY.resolve(snapshotFileName(fileName, item))
        Files.write(target, snapshot.png)
        api.logging().logToOutput("MCP saved proxy history #$id snapshot to $target")

        val summary = buildString {
            appendLine("Saved: $target")
            appendLine("Proxy #$id ${item.finalRequest().method()} ${item.finalRequest().url()}")
            appendLine("Image: ${snapshot.width}x${snapshot.height}px")
            snapshot.notes.forEach { appendLine(it) }
        }.trimEnd()

        val content = mutableListOf<ContentBlock>(TextContent(summary))
        if (returnImage == true) {
            if (snapshot.width > MAX_RETURNED_IMAGE_EDGE || snapshot.height > MAX_RETURNED_IMAGE_EDGE) {
                content += TextContent("Image too large to return inline; read it from the saved path.")
            } else {
                content += ImageContent(Base64.getEncoder().encodeToString(snapshot.png), "image/png")
            }
        }
        content
    }
}

internal fun parseHighlightColor(color: String?): HighlightColor {
    val name = color?.trim()?.uppercase()?.ifEmpty { null } ?: DEFAULT_HIGHLIGHT
    return HighlightColor.values().firstOrNull { it.name == name }
        ?: throw IllegalArgumentException(
            "Unknown color '$color'. Use one of ${HighlightColor.values().joinToString { it.name }}"
        )
}

internal fun parseLayout(layout: String?): Boolean =
    when (layout?.trim()?.uppercase()?.ifEmpty { null } ?: "SIDE_BY_SIDE") {
        "SIDE_BY_SIDE" -> false
        "STACKED" -> true
        else -> throw IllegalArgumentException("Unknown layout '$layout'. Use SIDE_BY_SIDE or STACKED")
    }

internal fun snapshotFileName(requested: String?, item: ProxyHttpRequestResponse): String {
    val base = requested
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.removeSuffix(".png")
        ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        ?.trim('.', '_')
        ?.take(120)
        ?.ifEmpty { null }
        ?: "proxy-${item.id()}-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}"
    return "$base.png"
}

internal data class SnapshotOptions(
    val width: Int,
    val height: Int,
    val stacked: Boolean,
    val searchTerm: String?,
    val fullHeight: Boolean
)

internal class Snapshot(val png: ByteArray, val width: Int, val height: Int, val notes: List<String>)

/**
 * Renders Burp's own HTTP message editors to an image.
 *
 * Burp's editors only paint their text once they sit inside a window that has been shown, so the
 * panes are placed in an undecorated, non-focusable window far off-screen, given time to lay out and
 * syntax-highlight, then painted into a buffer.
 */
internal object MessageSnapshotRenderer {
    private const val SETTLE_MS = 1_000L
    private const val RESETTLE_MS = 400L
    private const val OFFSCREEN = -20_000

    suspend fun render(
        api: MontoyaApi, request: HttpRequest, response: HttpResponse?, options: SnapshotOptions
    ): Snapshot {
        var frame: JFrame? = null
        try {
            val panes = onEdt {
                val requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY)
                val responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY)
                requestEditor.setRequest(request)
                response?.let { responseEditor.setResponse(it) }
                options.searchTerm?.let {
                    requestEditor.setSearchExpression(it)
                    responseEditor.setSearchExpression(it)
                }

                val split = JSplitPane(
                    if (options.stacked) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT,
                    requestEditor.uiComponent(),
                    responseEditor.uiComponent()
                ).apply { resizeWeight = 0.5 }
                api.userInterface().applyThemeToComponent(split)

                frame = JFrame("Burp MCP snapshot").apply {
                    isUndecorated = true
                    type = Window.Type.UTILITY
                    focusableWindowState = false
                    isAutoRequestFocus = false
                    contentPane.add(split)
                    setSize(options.width, options.height)
                    location = Point(OFFSCREEN, OFFSCREEN)
                    isVisible = true
                    validate()
                }
                split.dividerLocation = (if (options.stacked) options.height else options.width) / 2
                split.validate()
                Triple(split, requestEditor.uiComponent(), responseEditor.uiComponent())
            }
            val (split, requestPane, responsePane) = panes
            delay(SETTLE_MS)

            val notes = mutableListOf<String>()

            if (options.fullHeight) {
                val resized = onEdt { growToFit(frame!!, split, requestPane, responsePane, options, notes) }
                if (resized) delay(RESETTLE_MS)
            }

            options.searchTerm?.let { term ->
                onEdt {
                    notes += "Request: ${scrollToTerm(requestPane, term, request.toString())}"
                    notes += "Response: ${scrollToTerm(responsePane, term, response?.toString())}"
                }
                delay(RESETTLE_MS)
            }

            return onEdt { capture(split, notes) }
        } finally {
            withContext(NonCancellable) { onEdt { frame?.dispose() } }
        }
    }

    private fun growToFit(
        frame: JFrame, split: JSplitPane, requestPane: Component, responsePane: Component,
        options: SnapshotOptions, notes: MutableList<String>
    ): Boolean {
        val requestHeight = requestPane.height + hiddenHeight(requestPane)
        val responseHeight = responsePane.height + hiddenHeight(responsePane)

        val wanted = if (options.stacked) requestHeight + responseHeight + split.dividerSize
        else maxOf(requestHeight, responseHeight)
        val newHeight = wanted.coerceIn(options.height, MAX_HEIGHT)
        if (wanted > MAX_HEIGHT) {
            notes += "Message taller than $MAX_HEIGHT px; image is cut off. Use searchTerm to focus on the evidence."
        }
        if (newHeight == frame.height) return false

        frame.setSize(frame.width, newHeight)
        frame.validate()
        if (options.stacked) {
            split.dividerLocation = (requestHeight * newHeight / wanted.coerceAtLeast(1))
                .coerceAtLeast(options.height / 4)
        }
        split.validate()
        return true
    }

    /** How many pixels of the visible editor's content are scrolled out of view. */
    private fun hiddenHeight(pane: Component): Int {
        val viewport = visibleViewport(pane) ?: return 0
        val view = viewport.view ?: return 0
        return (view.preferredSize.height - viewport.extentSize.height).coerceAtLeast(0)
    }

    /**
     * Scrolls the visible editor so the first match of [term] sits about a third of the way down.
     * Uses the editor's own text when it is a Swing text component (exact, and correct for the
     * Pretty view); otherwise estimates the position from the raw message's line numbers.
     */
    private fun scrollToTerm(pane: Component, term: String, raw: String?): String {
        val textComponent = descendants(pane).filterIsInstance<JTextComponent>()
            .firstOrNull { it.isShowing && SwingUtilities.getAncestorOfClass(JViewport::class.java, it) != null }

        if (textComponent != null) {
            val text = textComponent.document.getText(0, textComponent.document.length)
            val index = text.indexOf(term, ignoreCase = true)
            if (index < 0) return "'$term' not found"
            val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, textComponent) as JViewport
            val match = textComponent.modelToView2D(index)?.bounds
                ?: return "'$term' found but could not be located"
            return scrollViewport(viewport, match.y, term, approximate = false)
        }

        val viewport = visibleViewport(pane) ?: return "no scrollable view found"
        val view = viewport.view ?: return "no scrollable view found"
        val message = raw ?: return "'$term' not found"
        val index = message.indexOf(term, ignoreCase = true)
        if (index < 0) return "'$term' not found"
        val totalLines = message.count { it == '\n' } + 1
        val matchLine = message.substring(0, index).count { it == '\n' }
        return scrollViewport(viewport, view.preferredSize.height * matchLine / totalLines, term, approximate = true)
    }

    private fun scrollViewport(viewport: JViewport, matchY: Int, term: String, approximate: Boolean): String {
        val viewHeight = viewport.view?.preferredSize?.height ?: 0
        val maxY = (viewHeight - viewport.extentSize.height).coerceAtLeast(0)
        val y = (matchY - viewport.extentSize.height / 3).coerceIn(0, maxY)
        viewport.viewPosition = Point(viewport.viewPosition.x, y)
        val how = if (approximate) " (approximate position)" else ""
        return if (y == 0) "'$term' visible$how" else "scrolled to '$term'$how"
    }

    private fun visibleViewport(pane: Component): JViewport? =
        descendants(pane).filterIsInstance<JScrollPane>()
            .filter { it.isShowing }
            .maxByOrNull { it.width * it.height }
            ?.viewport

    private fun descendants(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) root.components.forEach { yieldAll(descendants(it)) }
    }

    private fun capture(component: JSplitPane, notes: List<String>): Snapshot {
        val width = component.width
        val height = component.height
        check(width > 0 && height > 0) { "Editor did not lay out; nothing to capture" }

        val scale = if (width.toLong() * height * 4 * 4 <= MAX_IMAGE_BYTES) 2 else 1
        val image = BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.scale(scale.toDouble(), scale.toDouble())
            component.printAll(graphics)
        } finally {
            graphics.dispose()
        }

        val png = ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
        return Snapshot(png, image.width, image.height, notes)
    }

    private suspend fun <T> onEdt(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        SwingUtilities.invokeLater {
            if (!continuation.isActive) return@invokeLater
            runCatching(block)
                .onSuccess { continuation.resume(it) }
                .onFailure { continuation.resumeWithException(it) }
        }
    }
}

@Serializable
data class HighlightProxyHistoryItems(
    val ids: List<Int>,
    val color: String? = null,
    val notes: String? = null
)

@Serializable
data class ScreenshotProxyHistoryItem(
    val id: Int,
    val searchTerm: String? = null,
    val fullHeight: Boolean? = null,
    val layout: String? = null,
    val useOriginal: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fileName: String? = null,
    val returnImage: Boolean? = null
)
