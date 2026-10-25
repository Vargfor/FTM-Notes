package io.github.vargfor.ftmnotes.settings

import androidx.room3.Room
import io.github.vargfor.ftmnotes.core.model.Hlc
import io.github.vargfor.ftmnotes.core.model.NoteDocument
import io.github.vargfor.ftmnotes.core.model.SettingValue
import io.github.vargfor.ftmnotes.core.model.SettingsDocument
import io.github.vargfor.ftmnotes.core.settings.Flags
import io.github.vargfor.ftmnotes.core.settings.SectionId
import io.github.vargfor.ftmnotes.core.settings.ThemeMode
import io.github.vargfor.ftmnotes.data.DeviceClock
import io.github.vargfor.ftmnotes.data.DocumentRepository
import io.github.vargfor.ftmnotes.data.Transactor
import io.github.vargfor.ftmnotes.data.db.AppDatabase
import io.github.vargfor.ftmnotes.data.db.DevicePrefEntity
import io.github.vargfor.ftmnotes.data.db.buildFtmDatabase
import io.github.vargfor.ftmnotes.di.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder<AppDatabase>().buildFtmDatabase()
    private val dao = db.documentDao()
    private val prefs = db.devicePrefDao()
    private val clock = DeviceClock(dao)
    private val documents = DocumentRepository(dao, Transactor(db), clock)
    private val scope = AppScope()
    private val settings = SettingsRepository(documents, prefs, clock, scope)

    @AfterTest
    fun close() {
        scope.cancel()
        db.close()
    }

    private suspend fun storedSettings(): SettingsDocument? = documents.get(SettingsDocument.ID)?.document as? SettingsDocument

    // Room emits on its own threads, so wait in real time rather than runTest's virtual time.
    private suspend fun <T> realTimeout(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(5_000) { block() } }

    /** Waits until the eagerly collected snapshot has caught up with a write. */
    private suspend fun <T> awaitValue(read: () -> T, expected: T) = realTimeout {
        settings.snapshot.first { read() == expected }
    }

    @Test
    fun startsWithDefaultsWhenNothingIsStored() = runTest {
        realTimeout { settings.awaitLoaded() }
        assertEquals(ThemeMode.System, settings.snapshot.value[Flags.theme])
        assertNull(storedSettings(), "reading settings must not create the document")
    }

    @Test
    fun syncedFlagsGoToTheSettingsDocumentWithAnHlcPerKey() = runTest {
        settings.set(Flags.startTab, SectionId.Food)
        settings.set(Flags.swipeToArchive, false)

        val doc = assertNotNull(storedSettings())
        assertEquals(JsonPrimitive("food"), doc.values.getValue(Flags.startTab.id).v)
        assertEquals(JsonPrimitive(false), doc.values.getValue(Flags.swipeToArchive.id).v)
        assertTrue(Hlc.parse(doc.values.getValue(Flags.swipeToArchive.id).hlc) > Hlc.parse(doc.values.getValue(Flags.startTab.id).hlc))
        assertNull(prefs.get(Flags.startTab.id))

        awaitValue({ settings.snapshot.value[Flags.startTab] }, SectionId.Food)
        assertEquals(false, settings.observe(Flags.swipeToArchive).first())
    }

    @Test
    fun deviceFlagsStayInDevicePrefs() = runTest {
        settings.set(Flags.theme, ThemeMode.Dark)

        assertEquals("\"dark\"", prefs.get(Flags.theme.id))
        assertNull(storedSettings()?.values?.get(Flags.theme.id))
        awaitValue({ settings.snapshot.value[Flags.theme] }, ThemeMode.Dark)
    }

    @Test
    fun settingTheSameValueAgainKeepsItsTimestamp() = runTest {
        settings.set(Flags.startTab, SectionId.Training)
        val first = assertNotNull(storedSettings()).values.getValue(Flags.startTab.id)
        settings.set(Flags.startTab, SectionId.Training)
        assertEquals(first, assertNotNull(storedSettings()).values.getValue(Flags.startTab.id))
    }

    @Test
    fun keysFromNewerVersionsSurviveWrites() = runTest {
        documents.save(SettingsDocument(values = mapOf("food.future_flag" to SettingValue(JsonPrimitive(3), "000000000000001-00000-x"))))
        settings.set(Flags.foodTab, false)

        val doc = assertNotNull(storedSettings())
        assertEquals(JsonPrimitive(3), doc.values.getValue("food.future_flag").v)
        assertEquals(JsonPrimitive(false), doc.values.getValue(Flags.foodTab.id).v)
    }

    @Test
    fun nonJsonDevicePrefsAreIgnored() = runTest {
        prefs.put(DevicePrefEntity("backup.folder", "content://not json"))
        prefs.put(DevicePrefEntity(Flags.pureBlack.id, "true"))
        awaitValue({ settings.snapshot.value[Flags.pureBlack] }, true)
    }

    @Test
    fun settingsNeverShowUpInSearchOrNoteLists() = runTest {
        settings.set(Flags.startTab, SectionId.Food)
        documents.save(NoteDocument(title = "Settings to change on my router"))

        assertEquals(listOf("Settings to change on my router"), documents.search("settings").first().map { it.title })
    }
}
