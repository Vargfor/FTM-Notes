package io.github.vargfor.ftmnotes.data

import io.github.vargfor.ftmnotes.core.importer.recipe.*
import io.github.vargfor.ftmnotes.core.format.ReadIssue
import io.github.vargfor.ftmnotes.core.model.Ids
import io.github.vargfor.ftmnotes.platform.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import okio.*

class RecipeImportService(
    private val client: HttpClient?, private val coordinator: ImportCoordinator,
    private val attachments: AttachmentStore,
) {
    suspend fun fromUrl(url: String): ImportPreview {
        val address = Url(url)
        require(address.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS)) { "Use an HTTP or HTTPS recipe URL" }
        val html = fetch(address.toString(), 10 * 1024 * 1024L).decodeToString()
        val result = RecipeParser.parseHtml(html, address.toString())
        return stage(result, "recipe-url", address.toString())
    }
    suspend fun fromPaprika(file: PickedFile): ImportPreview = withContext(Dispatchers.IO) {
        stage(PaprikaParser.parse(FileSystem.SYSTEM, file.path), "paprika", null)
    }
    private suspend fun stage(result: RecipeParseResult, source: String, base: String?): ImportPreview {
        val issues = result.issues.toMutableList()
        val drafts = result.recipes.mapIndexed { index, draft ->
            val refs = draft.imageBytes.map { image -> attachments.put(Buffer().write(image.bytes), image.mime, image.name) }.toMutableList()
            for (raw in draft.imageUrls.take(10)) {
                try {
                    val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw
                        else if (base != null) RecipeParser.absoluteImageUrl(raw, base)
                        else error("Image URL is not absolute")
                    val bytes = fetch(url, 20 * 1024 * 1024L)
                    val mime = if (bytes.take(4) == listOf(0x89.toByte(), 0x50.toByte(), 0x4e.toByte(), 0x47.toByte())) "image/png" else "image/jpeg"
                    refs += attachments.put(Buffer().write(bytes), mime, "recipe-${index + 1}.jpg")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { issues += ReadIssue(ReadIssue.Severity.Warning, "$.recipes[$index].images", "Image was not imported: ${e.message}") }
            }
            ImportDraft(Ids.newId(), draft.document.copy(images = refs.distinctBy { it.sha256 }))
        }
        return coordinator.stage(source, drafts, issues)
    }

    private suspend fun fetch(url: String, limit: Long): ByteArray = withTimeout(30000) {
        val address = Url(url)
        require(address.protocol in listOf(URLProtocol.HTTP, URLProtocol.HTTPS)) { "Unsupported URL" }
        val response = requireNotNull(client) { "Web import is unavailable on this platform" }.get(url)
        require(response.status.isSuccess()) { "Website returned ${response.status.value}" }
        response.contentLength()?.let { require(it <= limit) { "Download is too large" } }
        val input = response.bodyAsChannel()
        val output = Buffer()
        val bytes = ByteArray(64 * 1024)
        try {
            while (!input.isClosedForRead) {
                val count = input.readAvailable(bytes, 0, bytes.size)
                if (count < 0) break
                if (count == 0) { yield(); continue }
                require(output.size + count <= limit) { "Download is too large" }
                output.write(bytes, 0, count)
            }
            output.readByteArray()
        } finally { input.cancel() }
    }
}
