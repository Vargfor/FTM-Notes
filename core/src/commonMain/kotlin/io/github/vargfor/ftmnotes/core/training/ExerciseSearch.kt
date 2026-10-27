package io.github.vargfor.ftmnotes.core.training

import io.github.vargfor.ftmnotes.core.model.ExerciseDocument

/** One search rule for browsing the library and adding exercises to a routine or workout. */
data class ExerciseSearch(val query: String = "", val category: String? = null, val muscle: String? = null, val equipment: String? = null) {
    fun apply(catalog: List<ExerciseDocument>): List<ExerciseDocument> {
        val words = query.lowercase().trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return catalog.filter { exercise ->
            !exercise.archived && (category == null || category == exercise.category) &&
                (muscle == null || muscle in exercise.muscleGroups) && (equipment == null || equipment == exercise.equipment) &&
                words.all { word -> (exercise.aliases + exercise.title + exercise.category + exercise.equipment + exercise.muscleGroups)
                    .any { it.contains(word, ignoreCase = true) } }
        }.sortedBy { it.title.lowercase() }
    }
}
