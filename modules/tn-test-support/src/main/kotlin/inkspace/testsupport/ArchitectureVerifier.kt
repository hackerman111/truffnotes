package inkspace.testsupport

import java.io.File

object ArchitectureVerifier {

    data class Violation(
        val moduleName: String,
        val rule: String,
        val detail: String
    )

    private val ALLOWED_INTERNAL_TARGETS_FOR_FEATURES = setOf(":tn-core", ":tn-test-support")
    private val RUNTIME_OR_APP_MODULES = setOf("tn-runtime", "tn-platform", "tn-ui", "app")

    /**
     * Inspects a Gradle workspace directory (either the production root or a temporary fixture project)
     * and detects any forbidden dependency edges or prohibited imports.
     */
    fun verifyWorkspace(workspaceRoot: File): List<Violation> {
        val violations = mutableListOf<Violation>()

        // 1. Check settings.gradle.kts for unauthorized composite build bypasses
        val settingsFile = File(workspaceRoot, "settings.gradle.kts")
        if (settingsFile.exists()) {
            val settingsLines = settingsFile.readLines()
            for (line in settingsLines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("includeBuild(") && !trimmed.contains("\"build-logic\"")) {
                    violations.add(
                        Violation(
                            moduleName = "root",
                            rule = "forbidden-composite-build",
                            detail = "Unauthorized composite build edge: $trimmed"
                        )
                    )
                }
            }
        }

        val modulesDir = File(workspaceRoot, "modules")
        if (!modulesDir.exists()) return violations

        val moduleDirs = modulesDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (modDir in moduleDirs) {
            val modName = modDir.name
            val buildFile = File(modDir, "build.gradle.kts")
            if (buildFile.exists()) {
                val content = buildFile.readLines()
                for (line in content) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//")) continue

                    // Check project(...) dependencies across all configurations:
                    // api, implementation, runtimeOnly, testImplementation, debugImplementation, variant, etc.
                    val projRegex = Regex("""(\w+)\s*\(\s*project\s*\(\s*"([^"]+)"\s*\)\s*\)""")
                    val match = projRegex.find(trimmed)
                    if (match != null) {
                        val configName = match.groupValues[1]
                        val targetProject = match.groupValues[2]

                        if (modName == "tn-core") {
                            violations.add(
                                Violation(
                                    moduleName = modName,
                                    rule = "core-no-project-deps",
                                    detail = ":tn-core cannot depend on $targetProject via $configName"
                                )
                            )
                        } else if (modName !in RUNTIME_OR_APP_MODULES) {
                            // Functional module: may only depend on :tn-core (or :tn-test-support in test configs)
                            val isTestConfig = configName.startsWith("test", ignoreCase = true) ||
                                configName.endsWith("TestImplementation")
                            val allowed = if (isTestConfig) {
                                ALLOWED_INTERNAL_TARGETS_FOR_FEATURES
                            } else {
                                setOf(":tn-core")
                            }
                            if (targetProject !in allowed) {
                                violations.add(
                                    Violation(
                                        moduleName = modName,
                                        rule = "forbidden-module-edge",
                                        detail = "Module $modName has forbidden $configName dependency on $targetProject"
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // If this is tn-core, check source files for prohibited imports
            if (modName == "tn-core") {
                val srcMain = File(modDir, "src/main/kotlin")
                if (srcMain.exists()) {
                    srcMain.walkTopDown().filter { it.extension == "kt" }.forEach { ktFile ->
                        ktFile.readLines().forEachIndexed { idx, line ->
                            val t = line.trim()
                            if (t.startsWith("import android.") || t.startsWith("import java.sql.") || t.startsWith("import javax.sql.")) {
                                violations.add(
                                    Violation(
                                        moduleName = "tn-core",
                                        rule = "prohibited-core-import",
                                        detail = "Prohibited import in ${ktFile.name}:${idx + 1}: $t"
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        return violations
    }
}
