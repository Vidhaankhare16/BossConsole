package ai.rever.boss.run

import ai.rever.boss.plugin.run.Language
import ai.rever.boss.plugin.run.RunConfiguration
import ai.rever.boss.plugin.run.RunConfigurationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks down the separator handling in run-configuration name disambiguation.
 *
 * A stored [RunConfiguration.filePath] is an OS-native absolute path, because
 * DesktopMainFunctionDetector assigns `file.absolutePath`. On Windows that is
 * backslash-separated, so neither disambiguation pass may assume '/'.
 *
 * Both path shapes are exercised on every CI leg: these are pure string functions
 * with no filesystem access, so the Windows expectations are just as meaningful on
 * a Linux runner as on a Windows one.
 */
class RunConfigurationNameSeparatorTest {
    private fun config(
        name: String,
        filePath: String,
    ) = RunConfiguration(
        id = filePath,
        name = name,
        type = RunConfigurationType.MAIN_FUNCTION,
        filePath = filePath,
        lineNumber = 1,
        language = Language.KOTLIN,
        command = "",
        workingDirectory = "",
    )

    @Test
    fun `windows paths disambiguate two same-named configurations`() {
        val project = """C:\Users\dev\myproject"""
        val configs =
            listOf(
                config("main (Main.kt [myproject])", """C:\Users\dev\myproject\app\Main.kt"""),
                config("main (Main.kt [myproject])", """C:\Users\dev\myproject\lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(
            names.size,
            names.toSet().size,
            "Disambiguation is the whole purpose of makeNamesUnique, but on Windows paths " +
                "both entries stayed identical: $names",
        )
        assertTrue(names.any { it.contains("app/Main.kt") }, "Expected the app parent in $names")
        assertTrue(names.any { it.contains("lib/Main.kt") }, "Expected the lib parent in $names")
    }

    @Test
    fun `windows label carries the project name, not the absolute project path`() {
        val project = """C:\Users\dev\myproject"""
        val configs =
            listOf(
                config("main (Main.kt)", """C:\Users\dev\myproject\app\Main.kt"""),
                config("main (Main.kt)", """C:\Users\dev\myproject\lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        names.forEach { name ->
            assertFalse(
                name.contains("""C:\Users\dev"""),
                "A run-configuration label must not embed the absolute project path: $name",
            )
        }
        assertTrue(names.all { it.contains("[myproject]") }, "Expected the project name in $names")
    }

    @Test
    fun `posix paths keep their existing disambiguation`() {
        val project = "/home/dev/myproject"
        val configs =
            listOf(
                config("main (Main.kt [myproject])", "/home/dev/myproject/app/Main.kt"),
                config("main (Main.kt [myproject])", "/home/dev/myproject/lib/Main.kt"),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(
            listOf("main (app/Main.kt [myproject])", "main (lib/Main.kt [myproject])"),
            names,
        )
    }

    @Test
    fun `a single configuration is never renamed`() {
        val project = """C:\Users\dev\myproject"""
        val configs = listOf(config("main (Main.kt)", """C:\Users\dev\myproject\app\Main.kt"""))

        assertEquals(configs, RunConfigurationManager.makeNamesUnique(configs, project))
    }

    @Test
    fun `stored windows names disambiguate on reload`() {
        val configs =
            listOf(
                config("main (Main.kt)", """C:\Users\dev\myproject\app\Main.kt"""),
                config("main (Main.kt)", """C:\Users\dev\myproject\lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeStoredNamesUnique(configs).map { it.name }

        assertEquals(listOf("main (app/Main.kt)", "main (lib/Main.kt)"), names)
    }

    @Test
    fun `stored posix names keep their existing disambiguation`() {
        val configs =
            listOf(
                config("main (Main.kt)", "/home/dev/myproject/app/Main.kt"),
                config("main (Main.kt)", "/home/dev/myproject/lib/Main.kt"),
            )

        val names = RunConfigurationManager.makeStoredNamesUnique(configs).map { it.name }

        assertEquals(listOf("main (app/Main.kt)", "main (lib/Main.kt)"), names)
    }

    /**
     * A file directly in the project root has no parent segment to disambiguate with, so its
     * name must be left alone. Without the leading-separator trim the relative path keeps its
     * separator, splits to ["", "Main.kt"], clears the `size >= 2` guard on the empty segment
     * and produces a label of "/Main.kt".
     */
    @Test
    fun `a windows file at the project root keeps its name`() {
        val project = """C:\Users\dev\myproject"""
        val configs =
            listOf(
                config("main (Main.kt [myproject])", """C:\Users\dev\myproject\Main.kt"""),
                config("main (Main.kt [myproject])", """C:\Users\dev\myproject\app\Main.kt"""),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(listOf("main (Main.kt [myproject])", "main (app/Main.kt [myproject])"), names)
    }

    @Test
    fun `a posix file at the project root keeps its name`() {
        val project = "/home/dev/myproject"
        val configs =
            listOf(
                config("main (Main.kt [myproject])", "/home/dev/myproject/Main.kt"),
                config("main (Main.kt [myproject])", "/home/dev/myproject/app/Main.kt"),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(listOf("main (Main.kt [myproject])", "main (app/Main.kt [myproject])"), names)
    }

    /** extractFileName and the split both accept either separator, so a mixed path works too. */
    @Test
    fun `mixed separators are handled`() {
        val project = """C:/Users\dev/myproject"""
        val configs =
            listOf(
                config("main (Main.kt)", """C:/Users\dev/myproject\app/Main.kt"""),
                config("main (Main.kt)", """C:/Users\dev/myproject/lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(listOf("main (app/Main.kt [myproject])", "main (lib/Main.kt [myproject])"), names)
    }

    /** A trailing separator leaves no project name, so the bracketed suffix is omitted. */
    @Test
    fun `a project path with a trailing separator omits the project suffix`() {
        val project = """C:\Users\dev\myproject\"""
        val configs =
            listOf(
                config("main (Main.kt)", """C:\Users\dev\myproject\app\Main.kt"""),
                config("main (Main.kt)", """C:\Users\dev\myproject\lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(listOf("main (app/Main.kt)", "main (lib/Main.kt)"), names)
    }
}
