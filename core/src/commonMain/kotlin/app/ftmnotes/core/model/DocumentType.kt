package app.ftmnotes.core.model

/**
 * Every kind of document the app stores. [wire] is the `"type"` value used in the FTM format,
 * the database and sync payloads — never change an existing one.
 */
enum class DocumentType(val wire: String, val aliases: Set<String> = emptySet()) {
    Note("note", setOf("notes", "misc", "memo", "text", "checklist")),
    Recipe("recipe", setOf("recipes")),
    ShoppingList("shopping_list", setOf("shoppinglist", "shopping", "grocery_list", "groceries")),
    Exercise("exercise", setOf("exercises")),
    WorkoutTemplate("workout_template", setOf("template", "routine", "workout")),
    WorkoutSession("workout_session", setOf("session", "logged_workout", "workout_log")),
    TrainingPlan("training_plan", setOf("plan", "program", "programme")),
    Settings("settings");

    companion object {
        private val byName: Map<String, DocumentType> = buildMap {
            for (type in DocumentType.entries) {
                put(type.wire, type)
                type.aliases.forEach { put(it, type) }
            }
        }

        fun fromWire(value: String): DocumentType? = byName[normalise(value)]

        /** Accepts `Shopping List`, `shopping-list`, `SHOPPING_LIST`, … */
        private fun normalise(value: String): String =
            value.trim().lowercase().replace(' ', '_').replace('-', '_')
    }
}
