package com.v5.launch

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

internal object ModLoaderUpdater {
    private const val MOD_LOADER_CANONICAL_FILE_NAME = "V5ModLoader.jar"
    private const val MOD_LOADER_HELPER_TIMEOUT_SECONDS = 90
    private const val MOD_LOADER_REPLACE_RETRY_COUNT = 20

    fun stageUpdateAndRelaunch(
        gameDir: File,
        modLoaderBytes: ByteArray,
        candidates: List<File>
    ): Boolean {
        val updatePaths = prepareUpdatePaths(gameDir, candidates)
        val relaunchCommand = buildRelaunchCommand()

        FileOutputStream(updatePaths.sourceJar).use { it.write(modLoaderBytes) }

        val helperScript = if (isWindows()) {
            writeWindowsUpdateScript(updatePaths, relaunchCommand)
        } else {
            writeUnixUpdateScript(updatePaths, relaunchCommand)
        }

        startUpdateHelper(updatePaths.gameDir, helperScript)
        return relaunchCommand != null
    }

    private fun prepareUpdatePaths(gameDir: File, candidates: List<File>): UpdatePaths {
        val canonicalGameDir = gameDir.canonicalFile
        val modsDir = File(canonicalGameDir, "mods").canonicalFile.apply { mkdirs() }
        val updaterDir = File(canonicalGameDir, ".v5-self-update").canonicalFile.apply { mkdirs() }
        val pid = ProcessHandle.current().pid()
        val sourceJar = File(updaterDir, "V5ModLoader-$pid.jar.new").canonicalFile
        val targetJar = selectModLoaderTarget(modsDir, candidates)
        val backupJar = File(updaterDir, "V5ModLoader-$pid.jar.bak").canonicalFile
        val targetPath = targetJar.toPath().toAbsolutePath().normalize()
        val staleTargets = candidates
            .map { it.toPath().toAbsolutePath().normalize() }
            .distinct()
            .filter { it != targetPath }
            .map { it.toFile() }
        return UpdatePaths(
            gameDir = canonicalGameDir,
            pid = pid,
            sourceJar = sourceJar,
            targetJar = targetJar,
            backupJar = backupJar,
            staleTargets = staleTargets
        )
    }

    private fun selectModLoaderTarget(modsDir: File, candidates: List<File>): File {
        val canonicalModsDir = modsDir.canonicalFile.toPath().normalize()
        val existingTarget = candidates.singleOrNull()
            ?.canonicalFile
            ?.takeIf { it.toPath().normalize().startsWith(canonicalModsDir) }
        return existingTarget ?: File(modsDir, MOD_LOADER_CANONICAL_FILE_NAME).canonicalFile
    }

    private fun buildRelaunchCommand(): RelaunchCommand? {
        val processInfo = ProcessHandle.current().info()
        val command = processInfo.command().orElse(null) ?: return null
        val arguments = processInfo.arguments().orElse(null)?.toList() ?: return null
        return RelaunchCommand(command, arguments)
    }

    private fun startUpdateHelper(gameDir: File, helperScript: File) {
        val command = if (isWindows()) {
            listOf("cmd.exe", "/c", helperScript.absolutePath)
        } else {
            listOf("sh", helperScript.absolutePath)
        }

        ProcessBuilder(command)
            .directory(gameDir)
            .start()
    }

    private fun writeUnixUpdateScript(updatePaths: UpdatePaths, relaunchCommand: RelaunchCommand?): File {
        val script = File(updatePaths.sourceJar.parentFile, "modloader-update-${updatePaths.pid}.sh").canonicalFile
        val lines = mutableListOf<String>()
        lines += "#!/bin/sh"
        lines += "PID=${updatePaths.pid}"
        lines += "WAIT_SECONDS=0"
        lines += "while kill -0 \"\$PID\" 2>/dev/null; do"
        lines += "  sleep 1"
        lines += "  WAIT_SECONDS=\$((WAIT_SECONDS + 1))"
        lines += "  if [ \"\$WAIT_SECONDS\" -ge \"$MOD_LOADER_HELPER_TIMEOUT_SECONDS\" ]; then"
        lines += "    exit 1"
        lines += "  fi"
        lines += "done"
        lines += "ATTEMPT=0"
        lines += "while [ \"\$ATTEMPT\" -lt \"$MOD_LOADER_REPLACE_RETRY_COUNT\" ]; do"
        lines += "  mkdir -p ${shellQuote(updatePaths.targetJar.parentFile.canonicalPath)}"
        lines += "  rm -f ${shellQuote(updatePaths.backupJar.absolutePath)} >/dev/null 2>&1 || true"
        lines += "  if [ -f ${shellQuote(updatePaths.targetJar.absolutePath)} ]; then"
        lines += "    mv -f ${shellQuote(updatePaths.targetJar.absolutePath)} ${shellQuote(updatePaths.backupJar.absolutePath)} >/dev/null 2>&1 || true"
        lines += "  fi"
        lines += "  if mv -f ${shellQuote(updatePaths.sourceJar.absolutePath)} ${shellQuote(updatePaths.targetJar.absolutePath)} >/dev/null 2>&1; then"
        updatePaths.staleTargets.forEach { target ->
            lines += "    rm -f ${shellQuote(target.absolutePath)} >/dev/null 2>&1 || true"
        }
        lines += "    rm -f ${shellQuote(updatePaths.backupJar.absolutePath)} >/dev/null 2>&1 || true"
        lines += "    break"
        lines += "  fi"
        lines += "  if [ -f ${shellQuote(updatePaths.backupJar.absolutePath)} ]; then"
        lines += "    mv -f ${shellQuote(updatePaths.backupJar.absolutePath)} ${shellQuote(updatePaths.targetJar.absolutePath)} >/dev/null 2>&1 || true"
        lines += "  fi"
        lines += "  ATTEMPT=\$((ATTEMPT + 1))"
        lines += "  sleep 1"
        lines += "done"
        lines += "[ -f ${shellQuote(updatePaths.targetJar.absolutePath)} ] || exit 1"
        if (relaunchCommand != null) {
            val relaunchParts = buildList {
                add(shellQuote(relaunchCommand.command))
                addAll(relaunchCommand.arguments.map(::shellQuote))
            }.joinToString(" ")
            lines += "nohup $relaunchParts >/dev/null 2>&1 &"
        }
        lines += "rm -f ${shellQuote(script.absolutePath)} >/dev/null 2>&1 || true"
        lines += "exit 0"
        script.writeText(lines.joinToString("\n") + "\n", StandardCharsets.UTF_8)
        return script
    }

    private fun writeWindowsUpdateScript(updatePaths: UpdatePaths, relaunchCommand: RelaunchCommand?): File {
        val script = File(updatePaths.sourceJar.parentFile, "modloader-update-${updatePaths.pid}.cmd").canonicalFile
        val lines = mutableListOf<String>()
        lines += "@echo off"
        lines += "setlocal enabledelayedexpansion"
        lines += "set \"PID=${updatePaths.pid}\""
        lines += "set \"ATTEMPTS=0\""
        lines += ":wait_for_exit"
        lines += "tasklist /FI \"PID eq %PID%\" | find \"%PID%\" >nul"
        lines += "if not errorlevel 1 ("
        lines += "  timeout /t 1 /nobreak >nul"
        lines += "  set /a ATTEMPTS+=1"
        lines += "  if !ATTEMPTS! geq $MOD_LOADER_HELPER_TIMEOUT_SECONDS exit /b 1"
        lines += "  goto wait_for_exit"
        lines += ")"
        lines += "set \"ATTEMPTS=0\""
        lines += ":replace_loop"
        lines += "del /f /q ${cmdQuote(updatePaths.backupJar.absolutePath)} >nul 2>nul"
        lines += "if not exist ${cmdQuote(updatePaths.targetJar.parentFile.canonicalPath)} mkdir ${cmdQuote(updatePaths.targetJar.parentFile.canonicalPath)}"
        lines += "if exist ${cmdQuote(updatePaths.targetJar.absolutePath)} move /y ${cmdQuote(updatePaths.targetJar.absolutePath)} ${cmdQuote(updatePaths.backupJar.absolutePath)} >nul 2>nul"
        lines += "move /y ${cmdQuote(updatePaths.sourceJar.absolutePath)} ${cmdQuote(updatePaths.targetJar.absolutePath)} >nul 2>nul"
        lines += "if not errorlevel 1 if exist ${cmdQuote(updatePaths.targetJar.absolutePath)} if not exist ${cmdQuote(updatePaths.sourceJar.absolutePath)} goto cleanup"
        lines += "if exist ${cmdQuote(updatePaths.backupJar.absolutePath)} move /y ${cmdQuote(updatePaths.backupJar.absolutePath)} ${cmdQuote(updatePaths.targetJar.absolutePath)} >nul 2>nul"
        lines += "set /a ATTEMPTS+=1"
        lines += "if !ATTEMPTS! geq $MOD_LOADER_REPLACE_RETRY_COUNT exit /b 1"
        lines += "timeout /t 1 /nobreak >nul"
        lines += "goto replace_loop"
        lines += ":cleanup"
        updatePaths.staleTargets.forEach { target ->
            lines += "del /f /q ${cmdQuote(target.absolutePath)} >nul 2>nul"
        }
        lines += "del /f /q ${cmdQuote(updatePaths.backupJar.absolutePath)} >nul 2>nul"
        lines += ":relaunch"
        if (relaunchCommand != null) {
            val psCommand = buildPowerShellRelaunchCommand(relaunchCommand, updatePaths.gameDir)
            lines += "powershell -NoProfile -ExecutionPolicy Bypass -Command ${cmdQuote(psCommand)} >nul 2>nul"
        }
        lines += "del /f /q \"%~f0\" >nul 2>nul"
        lines += "exit /b 0"
        script.writeText(lines.joinToString("\r\n") + "\r\n", StandardCharsets.UTF_8)
        return script
    }

    private fun buildPowerShellRelaunchCommand(
        relaunchCommand: RelaunchCommand,
        workingDirectory: File
    ): String {
        val argsLiteral = relaunchCommand.arguments.joinToString(",") { "'${it.replace("'", "''")}'" }
        val commandLiteral = "'${relaunchCommand.command.replace("'", "''")}'"
        val workingDirLiteral = "'${workingDirectory.absolutePath.replace("'", "''")}'"
        return "Start-Process -FilePath $commandLiteral -ArgumentList @($argsLiteral) -WorkingDirectory $workingDirLiteral"
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").contains("win", ignoreCase = true)
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }

    private fun cmdQuote(value: String): String {
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private data class UpdatePaths(
        val gameDir: File,
        val pid: Long,
        val sourceJar: File,
        val targetJar: File,
        val backupJar: File,
        val staleTargets: List<File>
    )

    private data class RelaunchCommand(
        val command: String,
        val arguments: List<String>
    )
}
