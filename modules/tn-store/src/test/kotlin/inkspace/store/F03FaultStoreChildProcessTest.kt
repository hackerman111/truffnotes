package inkspace.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class F03FaultStoreChildProcessTest {

    @Test
    fun fault_store_1000_seeded_crash_schedules(@TempDir tempDir: File) {
        val seed = System.getProperty("faultStore.seed")?.toLongOrNull() ?: 20261105L
        val runs = System.getProperty("faultStore.runs")?.toIntOrNull() ?: 1000

        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val reportFile = File(tempDir, "fault-report.json")

        // Spawn a separate child JVM process to execute the seeded crash schedules and reopen verification
        val process = ProcessBuilder(
            javaBin,
            "-cp",
            classpath,
            "inkspace.store.FaultStoreChildMain",
            tempDir.absolutePath,
            seed.toString(),
            runs.toString(),
            reportFile.absolutePath
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        println(output)

        assertEquals(0, exitCode, "Child process fault runner failed with exit $exitCode:\n$output")
        assertTrue(reportFile.exists(), "Expected fault report file at ${reportFile.absolutePath}")

        val evidenceReport = File("../../docs/evidence/F03/fault-schedules-raw.json")
        evidenceReport.parentFile?.mkdirs()
        reportFile.copyTo(evidenceReport, overwrite = true)
    }
}
