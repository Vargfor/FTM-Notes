package io.github.vargfor.ftmnotes.core.format

import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.model.DocumentType
import io.github.vargfor.ftmnotes.core.model.FtmDocument
import io.github.vargfor.ftmnotes.core.model.NoteDocument
import io.github.vargfor.ftmnotes.core.model.SettingsDocument
import io.github.vargfor.ftmnotes.core.model.RecipeDocument
import io.github.vargfor.ftmnotes.core.model.ShoppingListDocument
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `{"ftm": 1, "documents": [...]}` — the top-level shape of every FTM file, backup and AI output. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FtmEnvelope(
    @EncodeDefault val ftm: Int = FtmFormat.VERSION,
    val documents: List<FtmDocument> = emptyList(),
)

object FtmFormat {
    const val VERSION = 1
    const val DISCRIMINATOR = "type"

    /** Canonical, strict output: what the app writes for export, backup and sync. */
    val writer: Json = Json {
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false
        classDiscriminator = DISCRIMINATOR
    }

    /** Compact variant for DB payloads and sync bodies. */
    val compact: Json = Json(writer) { prettyPrint = false }

    /** Forgiving input: tolerates what humans, other apps and LLMs produce. */
    internal val lenient: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        decodeEnumsCaseInsensitive = true
        allowTrailingComma = true
        allowComments = true
        classDiscriminator = DISCRIMINATOR
    }

    /** Serializers for document types this app version can read. Grows as feature phases land. */
    internal val supported: Map<DocumentType, KSerializer<out FtmDocument>> = mapOf(
        DocumentType.Exercise to ExerciseDocument.serializer(),
        DocumentType.WorkoutTemplate to WorkoutTemplateDocument.serializer(),
        DocumentType.WorkoutSession to WorkoutSessionDocument.serializer(),
        DocumentType.TrainingPlan to TrainingPlanDocument.serializer(),
        DocumentType.Note to NoteDocument.serializer(),
        DocumentType.Settings to SettingsDocument.serializer(),
        DocumentType.Recipe to RecipeDocument.serializer(),
        DocumentType.ShoppingList to ShoppingListDocument.serializer(),
    )

    fun write(documents: List<FtmDocument>): String =
        writer.encodeToString(FtmEnvelope.serializer(), FtmEnvelope(VERSION, documents))

    fun encodeDocument(document: FtmDocument): String =
        compact.encodeToString(FtmDocument.serializer(), document)

    fun decodeDocument(json: String): FtmDocument =
        lenient.decodeFromString(FtmDocument.serializer(), json)
}
