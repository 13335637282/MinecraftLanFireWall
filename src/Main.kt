import Tools.colorGradient
import Tools.parseMcStyle
import com.github.ffalcinelli.jdivert.Packet
import com.github.ffalcinelli.jdivert.WinDivert
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.mozilla.universalchardet.UniversalDetector
import java.nio.charset.Charset
import kotlin.system.exitProcess
import org.fusesource.jansi.AnsiConsole

fun detectCharset(bytes: ByteArray): String? {
    val detector = UniversalDetector {
    }
    detector.handleData(bytes, 0, bytes.size)
    detector.dataEnd()
    return detector.detectedCharset
}

fun extractLastContent(text: String, tag: String): String {
    // 使用 [\s\S]* 贪婪匹配任意字符（包括换行），直到最后一个闭标签
    val regex = Regex("""\[$tag]([\s\S]*)\[/$tag]""")
    val result = regex.find(text)?.groupValues?.get(1)
    return result ?: ""
}

fun decodePacket(packet: Packet): Map<String, String> {


    val r = hashMapOf<String, String>()

    packet.udp.ifPresent { packet ->

        val text: String = try {
            String(packet.data, Charsets.UTF_8)
        } catch (_: Exception) {
            val encoding = detectCharset(packet.data)
            if (encoding != null) {
                String(packet.data, Charset.forName(encoding))
            } else {
                return@ifPresent
            }
        }


        val motdContent = extractLastContent(text, "MOTD")
        val adContent   = extractLastContent(text, "AD")

        r["motd"] = motdContent
        r["ad"] = adContent
        r["srcPort"] = packet.srcPort.toString()
        r["dstPort"] = packet.dstPort.toString()
    }
    packet.ip.ifPresent {  ip ->
        r["srcIP"] = ip.srcAddr.toString().replace("/","")
        r["dstIP"] = ip.dstAddr.toString().replace("/","")
    }

    return r
}

@Volatile
private var running = true

const val S_PPM_THRESHOLD = 30
const val IP_PPM_THRESHOLD = 80
const val S_PP_1_5_THRESHOLD = 8
const val IP_PP_1_5_THRESHOLD = 20

fun main(args: Array<String>) {
    Elevate.init(args)
    AnsiConsole.systemInstall()

    val logger: Log = LogFactory.getLog("Main")
    if (!Elevate.elevate(force = true)) {
        logger.error("没有管理员权限无法启动防火墙")
        return
    }
    logger.info("管理员权限请求成功")

    val serverPPMMap = hashMapOf<String, PPTCounter>()
    val ipPPMMap = hashMapOf<String, PPTCounter>()
    val filter = "udp.DstPort == 4445"

    // 使用 use 确保资源释放
    WinDivert(filter).open().use { w ->
        // 注册关闭钩子，以防程序异常退出
        Runtime.getRuntime().addShutdownHook(Thread {
            running = false
        })

        while (running) {
            try {
                val packet: Packet = w.recv()
                var allow = true

                val packetData = decodePacket(packet)

                if (packetData["srcIP"] == null
                    || packetData["dstIP"] == null
                    || packetData["srcPort"] == null
                    || packetData["dstPort"] == null
                    || packetData["motd"] == null
                    || packetData["ad"] == null)
                {
                    throw NullPointerException("Packet has null data!!!")
                }

                val sid = "${packetData["srcIP"]}/${packetData["srcPort"]}/${packetData["dstIP"]}"

                val counter = serverPPMMap.getOrPut(sid) { PPTCounter(60.0) }
                counter.trig()
                val ipCounter = ipPPMMap.getOrPut("${packetData["srcIP"]}") { PPTCounter(60.0) }
                ipCounter.trig()

                if (counter.getPerTime(60.0) > S_PPM_THRESHOLD
                    || counter.getPerTime(1.5) > S_PP_1_5_THRESHOLD
                    || ipCounter.getPerTime(60.0) > IP_PPM_THRESHOLD
                    || ipCounter.getPerTime(1.5) > IP_PP_1_5_THRESHOLD){
                    allow = false
                }

                val showFilter = true

                if (showFilter) {
                    println("src: ${packetData["srcIP"]}:${packetData["srcPort"]}")
                    println("server: ${packetData["srcIP"]}:${packetData["ad"]}")
                    println(packetData["motd"]?.let { "Motd: ${parseMcStyle(it)}" })
                    println("dst: ${packetData["dstIP"]}:${packetData["dstPort"]}")
                    println("S-PPM: ${colorGradient(counter.getPerTime(60.0),S_PPM_THRESHOLD)}")
                    println("IP-PPM: ${colorGradient(ipCounter.getPerTime(60.0),IP_PPM_THRESHOLD)}")
                    println("S-PP1.5: ${colorGradient(counter.getPerTime(1.5),S_PP_1_5_THRESHOLD)}")
                    println("IP-PP1.5: ${colorGradient(ipCounter.getPerTime(1.5),IP_PP_1_5_THRESHOLD)}")

                }

                if (allow) {
                    w.send(packet)
                }
            } catch (_: InterruptedException) {
                // 如果线程被中断，退出循环
                logger.warn("捕获中断信号，退出防火墙")
                break
            } catch (e: Exception) {
                logger.error("处理包时发生异常", e)
                // 根据需要决定是否继续循环
            }
        }
        exitProcess(0)
    }
}