import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.WinNT.*
import com.sun.jna.ptr.IntByReference
import java.io.File
import java.lang.management.ManagementFactory

object Elevate {
    private var mainArgs: Array<String> = emptyArray()
    private var mainClass: String? = null

    fun init(args: Array<String>) {
        mainArgs = args
        if (mainClass == null) {
            mainClass = detectMainClass()
        }
    }

    fun init(args: Array<String>, mainClassName: String) {
        mainArgs = args
        mainClass = mainClassName
    }

    fun isElevated(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) isWindowsElevated() else isUnixElevated()
    }

    fun elevate(showConsole: Boolean = true, graphical: Boolean = true, force: Boolean = false): Boolean {
        // 防止循环：检测是否已经通过提权启动
        if (mainArgs.any { it == "--elevated-token" }) {
            return true
        }
        if (!force && isElevated()) {
            return true
        }
        if (mainClass == null) {
            mainClass = detectMainClass()
            if (mainClass == null) {
                System.err.println("无法自动检测主类，请手动调用 Elevate.init(args, mainClassName)")
                return false
            }
        }
        val os = System.getProperty("os.name").lowercase()
        return if (os.contains("win")) {
            elevateWindows(showConsole)
        } else {
            elevateUnix(graphical)
        }
    }

    // ---------- Windows 权限检测（仅令牌检测，确保准确性） ----------
    private fun isWindowsElevated(): Boolean {
        return try {
            val hToken = HANDLEByReference()
            val processHandle = Kernel32.INSTANCE.GetCurrentProcess()
            val opened = Advapi32.INSTANCE.OpenProcessToken(
                processHandle,
                TOKEN_QUERY,
                hToken
            )
            if (!opened) return false

            val elevation = TOKEN_ELEVATION()
            val size = IntByReference(elevation.size())
            val queried = Advapi32.INSTANCE.GetTokenInformation(
                hToken.value,
                TOKEN_INFORMATION_CLASS.TokenElevation,
                elevation,
                elevation.size(),
                size
            )
            Kernel32.INSTANCE.CloseHandle(hToken.value)
            queried && elevation.TokenIsElevated != 0
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Windows 提权 ----------
    private fun elevateWindows(showConsole: Boolean): Boolean {
        val javaHome = System.getProperty("java.home")
        val javaExe = File(javaHome, "bin\\java.exe").absolutePath

        val vmArgs = ManagementFactory.getRuntimeMXBean().inputArguments
        val classpath = System.getProperty("java.class.path")
        val mainCls = mainClass ?: return false

        // 构建完整的 Java 命令（不包括 chcp）
        val javaCmdArgs = mutableListOf(javaExe)
        javaCmdArgs.addAll(vmArgs)
        // 添加 UTF-8 编码参数（确保 JVM 内部也使用 UTF-8）
        if (!javaCmdArgs.any { it.startsWith("-Dfile.encoding=") }) {
            javaCmdArgs.add("-Dfile.encoding=UTF-8")
        }
        javaCmdArgs.add("-cp")
        javaCmdArgs.add(classpath)
        javaCmdArgs.add(mainCls)
        javaCmdArgs.add("--elevated-token")
        javaCmdArgs.addAll(mainArgs)

        // 将命令转为单字符串，并插入 chcp
        val cmdLine = javaCmdArgs.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
        // 最终执行：cmd.exe /c chcp 65001 >nul && java ...
        val finalCmd = "cmd.exe /c chcp 65001 >nul && $cmdLine"

        return try {
            val shell32 = Shell32.INSTANCE
            val sei = ShellAPI.SHELLEXECUTEINFO()
            sei.cbSize = sei.size()
            sei.lpVerb = "runas"
            sei.lpFile = "cmd.exe"
            sei.lpParameters = "/c chcp 65001 >nul && $cmdLine"
            sei.nShow = if (showConsole) WinUser.SW_SHOWNORMAL else WinUser.SW_HIDE
            sei.fMask = Shell32.SEE_MASK_NOCLOSEPROCESS

            if (!shell32.ShellExecuteEx(sei)) {
                val lastError = Kernel32.INSTANCE.GetLastError()
                System.err.println("UAC提权失败，错误码: $lastError")
                return false
            }

            val hProcess = sei.hProcess
            Kernel32.INSTANCE.WaitForSingleObject(hProcess, -1)
            val exitCodeRef = IntByReference()
            Kernel32.INSTANCE.GetExitCodeProcess(hProcess, exitCodeRef)
            Kernel32.INSTANCE.CloseHandle(hProcess)
            System.exit(exitCodeRef.value)
            true
        } catch (e: Exception) {
            System.err.println("UAC提权异常: ${e.message}")
            false
        }
    }

    // ---------- Unix 权限检测 ----------
    private fun isUnixElevated(): Boolean {
        return try {
            val process = ProcessBuilder("id", "-u").start()
            process.waitFor()
            val uid = process.inputStream.bufferedReader().readText().trim()
            uid == "0"
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Unix 提权 ----------
    private fun elevateUnix(graphical: Boolean): Boolean {
        val javaHome = System.getProperty("java.home")
        val javaExe = File(javaHome, "bin/java").absolutePath

        val vmArgs = ManagementFactory.getRuntimeMXBean().inputArguments
        val classpath = System.getProperty("java.class.path")
        val mainCls = mainClass ?: return false

        val cmdArgs = mutableListOf(javaExe)
        cmdArgs.addAll(vmArgs)
        cmdArgs.add("-cp")
        cmdArgs.add(classpath)
        cmdArgs.add(mainCls)
        cmdArgs.add("--elevated-token")
        cmdArgs.addAll(mainArgs)

        val elevators = if (graphical) {
            listOf("pkexec", "gksudo", "kdesudo", "sudo")
        } else {
            listOf("sudo")
        }

        for (elevator in elevators) {
            if (isCommandAvailable(elevator)) {
                try {
                    val fullCmd = mutableListOf(elevator)
                    fullCmd.addAll(cmdArgs)
                    val pb = ProcessBuilder(fullCmd)
                    pb.inheritIO()
                    val process = pb.start()
                    val exitCode = process.waitFor()
                    System.exit(exitCode)
                    return true
                } catch (e: Exception) {
                    continue
                }
            }
        }
        System.err.println("未找到可用的提权工具")
        return false
    }

    private fun isCommandAvailable(command: String): Boolean {
        return try {
            ProcessBuilder("which", command).start().apply { waitFor() }.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun detectMainClass(): String? {
        val stackTrace = Thread.currentThread().stackTrace
        for (element in stackTrace) {
            if (element.methodName == "main") {
                return element.className
            }
        }
        val cmd = System.getProperty("sun.java.command")
        if (cmd != null) {
            val parts = cmd.split(" ")
            if (parts.isNotEmpty()) {
                val first = parts[0]
                if (!first.endsWith(".jar")) {
                    return first
                }
                // 如果从 jar 启动，尝试从 MANIFEST 读取或返回 null
            }
        }
        return null
    }
}