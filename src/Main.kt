
import Tools.colorGradient
import Tools.parseMcStyle
import com.github.ffalcinelli.jdivert.Packet
import com.github.ffalcinelli.jdivert.WinDivert
import com.github.ffalcinelli.jdivert.headers.Udp
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.mozilla.universalchardet.CharsetListener
import org.mozilla.universalchardet.UniversalDetector
import java.nio.charset.Charset
import kotlin.system.exitProcess
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.Ansi.ansi
import org.fusesource.jansi.AnsiConsole
import kotlin.math.abs
import kotlin.math.roundToInt

fun detectCharset(bytes: ByteArray): String? {
    val detector = UniversalDetector {
        fun report(s: String) {
            println(s)
        }
    }
    detector.handleData(bytes, 0, bytes.size)
    detector.dataEnd()
    return detector.detectedCharset
}

fun extractLastContent(text: String, tag: String): String {
    // 使用 [\s\S]* 贪婪匹配任意字符（包括换行），直到最后一个闭标签
    val regex = Regex("""\[$tag\]([\s\S]*)\[/$tag\]""")
    val result = regex.find(text)?.groupValues?.get(1)
    if (result != null) {
        return result
    } else {
        return ""
    }
}

fun decodePacket(packet: Packet): Map<String, String> {


    val r = hashMapOf<String, String>()

    packet.udp.ifPresent { packet ->

        var text: String

        try {
            text = String(packet.data, Charsets.UTF_8)
        } catch (e: Exception) {
            val encoding = detectCharset(packet.data)
            if (encoding != null) {
                text = String(packet.data, Charset.forName(encoding))
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

val S_PPM_THRESHOLD = 30
val IP_PPM_THRESHOLD = (30*1.5).roundToInt()
val S_PP_1_5_THRESHOLD = (30 * 0.14).roundToInt()
val IP_PP_1_5_THRESHOLD = (30 * 1.5 * 2.5).roundToInt()

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
    val IP_PPMMap = hashMapOf<String, PPTCounter>()
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
                    throw NullPointerException("packetData is NNNNUUUUULLLLLLLLLLLL!!!")
                }

                val sid = "${packetData["srcIP"]}/${packetData["srcPort"]}/${packetData["dstIP"]}"

                val counter = serverPPMMap.getOrPut(sid) { PPTCounter(60.0) }
                counter.trig()
                val IP_counter = IP_PPMMap.getOrPut("${packetData["srcIP"]}") { PPTCounter(60.0) }
                IP_counter.trig()

                if (counter.getPerTime(60.0) > S_PPM_THRESHOLD
                    || counter.getPerTime(1.5) > S_PP_1_5_THRESHOLD
                    || IP_counter.getPerTime(60.0) > IP_PPM_THRESHOLD
                    || IP_counter.getPerTime(1.5) > IP_PP_1_5_THRESHOLD){
                    allow = false
                }

                if (true) {
                    println("src: ${packetData["srcIP"]}:${packetData["srcPort"]}")
                    println("server: ${packetData["srcIP"]}:${packetData["ad"]}")
                    println(packetData["motd"]?.let { "Motd: ${parseMcStyle(it)}" })
                    println("dst: ${packetData["dstIP"]}:${packetData["dstPort"]}")
                    println("S-PPM: ${colorGradient(counter.getPerTime(60.0),S_PPM_THRESHOLD)}")
                    println("IP-PPM: ${colorGradient(IP_counter.getPerTime(60.0),IP_PPM_THRESHOLD)}")
                    println("S-PP1.5: ${colorGradient(counter.getPerTime(1.5),S_PP_1_5_THRESHOLD)}")
                    println("IP-PP1.5: ${colorGradient(IP_counter.getPerTime(1.5),IP_PP_1_5_THRESHOLD)}")

                }

                if (allow) {
                    w.send(packet)
                }
            } catch (e: InterruptedException) {
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