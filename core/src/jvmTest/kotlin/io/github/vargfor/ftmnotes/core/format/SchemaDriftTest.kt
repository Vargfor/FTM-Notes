package io.github.vargfor.ftmnotes.core.format

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.vargfor.ftmnotes.core.model.*
import java.io.File
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalSerializationApi::class)
class SchemaDriftTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val mapper = ObjectMapper()
    private fun resource(name: String): String {
        val source = File(root, "schemas/ftm/v1/" + name).readText()
        assertEquals(source, File(root, "shared/src/commonMain/composeResources/files/" + name).readText(), name)
        return source
    }
    private fun properties(schema: JsonObject, descriptor: SerialDescriptor, discriminator: Boolean = false) {
        val expected = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet() + if (discriminator) setOf("type") else emptySet()
        assertEquals(expected, schema["properties"]!!.jsonObject.keys, descriptor.serialName)
    }

    @Test fun schemasDescribeDocumentsAndNestedTypes() {
        val note = Json.parseToJsonElement(resource("note.schema.json")).jsonObject
        val recipe = Json.parseToJsonElement(resource("recipe.schema.json")).jsonObject
        val shopping = Json.parseToJsonElement(resource("shopping_list.schema.json")).jsonObject
        properties(note, NoteDocument.serializer().descriptor, true)
        properties(recipe, RecipeDocument.serializer().descriptor, true)
        properties(shopping, ShoppingListDocument.serializer().descriptor, true)
        val defs = recipe["\$defs"]!!.jsonObject
        properties(defs["ingredient"]!!.jsonObject, Ingredient.serializer().descriptor)
        properties(defs["ingredientGroup"]!!.jsonObject, IngredientGroup.serializer().descriptor)
        properties(defs["step"]!!.jsonObject, RecipeStep.serializer().descriptor)
        properties(defs["attachment"]!!.jsonObject, AttachmentRef.serializer().descriptor)
        val shoppingDefs = shopping["\$defs"]!!.jsonObject
        properties(shoppingDefs["item"]!!.jsonObject, ShoppingItem.serializer().descriptor)
        properties(shoppingDefs["source"]!!.jsonObject, ShoppingSource.serializer().descriptor)
        properties(note["properties"]!!.jsonObject["items"]!!.jsonObject["items"]!!.jsonObject, ChecklistItem.serializer().descriptor)
    }

    @Test fun noteAndRecipePromptsValidateAndRoundTrip() {
        for (type in listOf("note", "recipe", "shopping_list", "exercise", "workout_template", "workout_session", "training_plan")) {
            val prompt = resource("AI_PROMPT_" + type + ".md")
            val validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(resource(type + ".schema.json"))
            val fence = 96.toChar().toString().repeat(3)
            val examples = Regex(fence + "json\\s*([\\s\\S]*?)" + fence).findAll(prompt).map { it.groupValues[1].trim() }.toList()
            assertEquals(2, examples.size, type)
            examples.forEach { example ->
                val read = FtmReader.read(example)
                assertTrue(read.errors.isEmpty(), read.issues.toString())
                Json.parseToJsonElement(example).jsonObject["documents"]!!.jsonArray.forEach { doc ->
                    val documentValidator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(resource(doc.jsonObject["type"]!!.jsonPrimitive.content + ".schema.json"))
                    val errors = documentValidator.validate(mapper.readTree(doc.toString()))
                    assertTrue(errors.isEmpty(), errors.toString() + ": " + doc.toString())
                }
                assertEquals(read.documents, FtmReader.read(FtmFormat.write(read.documents)).documents)
            }
        }
    }

    @Test fun schemasAcceptCanonicalDefaultsAndAllSerializedFields() {
        val full = Json(FtmFormat.writer) { encodeDefaults = true; explicitNulls = true }
        val documents = listOf(NoteDocument(), RecipeDocument(), ShoppingListDocument(),
            RecipeDocument(title = "Soup", servings = Servings(4.0, "bowls"),
                ingredientGroups = listOf(IngredientGroup(items = listOf(Ingredient(quantity = Quantity(2.0, 3.0), packageQuantity = Quantity(400.0))))),
                steps = listOf(RecipeStep(text = "Simmer", timerSeconds = 1200)),
                images = listOf(AttachmentRef("a".repeat(64), "image/png", width = 32, height = 32)), nutrition = mapOf("Energy" to "100 kcal"), rating = 4),
            ShoppingListDocument(items = listOf(ShoppingItem(quantity = Quantity(2.0, 3.0), sources = listOf(ShoppingSource("recipe", "ingredient"))))))
        documents.forEach { doc ->
            val validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(resource(doc.documentType.wire + ".schema.json"))
            for (encoded in listOf(FtmFormat.encodeDocument(doc), full.encodeToString(FtmDocument.serializer(), doc))) {
                assertTrue(validator.validate(mapper.readTree(encoded)).isEmpty(), encoded)
            }
        }
    }

    @Test fun trainingSchemasDescribeAllDocumentsAndNestedFields() {
        val documents = listOf(ExerciseDocument(), WorkoutTemplateDocument(), WorkoutSessionDocument(), TrainingPlanDocument())
        val full = Json(FtmFormat.writer) { encodeDefaults = true; explicitNulls = true }
        documents.forEach { doc ->
            val text = resource(doc.documentType.wire + ".schema.json")
            val schema = Json.parseToJsonElement(text).jsonObject
            val descriptor = when(doc) {
                is ExerciseDocument -> ExerciseDocument.serializer().descriptor
                is WorkoutTemplateDocument -> WorkoutTemplateDocument.serializer().descriptor
                is WorkoutSessionDocument -> WorkoutSessionDocument.serializer().descriptor
                else -> TrainingPlanDocument.serializer().descriptor
            }
            properties(schema, descriptor, true)
            val validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(text)
            val encoded = full.encodeToString(FtmDocument.serializer(), doc)
            assertTrue(validator.validate(mapper.readTree(encoded)).isEmpty(), encoded)
        }
        val defs = Json.parseToJsonElement(resource("exercise.schema.json")).jsonObject["\$defs"]!!.jsonObject
        listOf("field" to CustomTrainingField.serializer().descriptor, "exerciseRef" to ExerciseRef.serializer().descriptor,
            "templateRef" to TemplateRef.serializer().descriptor, "load" to LoadTarget.serializer().descriptor,
            "target" to SetTarget.serializer().descriptor, "snapshot" to ExerciseSnapshot.serializer().descriptor,
            "weekTarget" to WeekTarget.serializer().descriptor, "block" to WorkoutBlock.serializer().descriptor,
            "performed" to PerformedSet.serializer().descriptor, "sessionExercise" to SessionExercise.serializer().descriptor,
            "day" to PlanDay.serializer().descriptor, "override" to WeekOverride.serializer().descriptor,
            "rule" to ProgressionRule.serializer().descriptor, "exerciseRule" to ExerciseProgression.serializer().descriptor).forEach { (key, descriptor) -> properties(defs[key]!!.jsonObject, descriptor) }
        val bundle = FtmReader.read(resource("example_training_program.ftm.json"))
        assertTrue(bundle.errors.isEmpty(), bundle.issues.toString())
        assertTrue(io.github.vargfor.ftmnotes.core.training.TrainingBundles.resolve(bundle.documents, emptyList()).questions.isEmpty())
    }

    @Test fun schemasRejectInvalidFoodValues() {
        val invalid = mapOf("recipe" to listOf(
            """{"type":"recipe","servings":{"amount":0}}""",
            """{"type":"recipe","steps":[{"timerSeconds":-1}]}""",
            """{"type":"recipe","rating":6}""",
            """{"type":"recipe","invented":true}"""),
            "shopping_list" to listOf("""{"type":"shopping_list","items":[{"quantity":-2}]}"""))
        invalid.forEach { (type, examples) ->
            val validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(resource(type + ".schema.json"))
            examples.forEach { assertTrue(validator.validate(mapper.readTree(it)).isNotEmpty(), it) }
        }
    }
}
