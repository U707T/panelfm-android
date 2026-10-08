package com.u707t.panelfm.core.common

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 编码识别（零依赖）：
 *  BOM（UTF-8 / UTF-16 / UTF-32，手写解码） → 严格 UTF-8 校验 → 东亚编码启发式 → Latin-1 兜底。
 *
 * Android 平台自带 GBK/GB18030/Big5 等 Charset（ICU 支撑），无需额外库；
 * UTF-32 因为部分平台不保证 `Charset.forName("UTF-32LE")` 可用，这里**手写**编解码。
 *
 * 启发式（**只面向中文**，不做日文 / 韩文识别）：
 *  1. GBK ↔ Big5：对双侧解码文本按常用字表评分（逐字节位对齐；符号 / 拼音 / 制表区
 *     不计证），Big5 侧严格占优才切换，平局**保持 GBK**（宁缺勿错，见 COMMON_HANZI 注释）；
 *  2. 都解不出来 → Latin-1（保证任何字节都有可读文本）。
 */
object TextEncodings {

    data class Decoded(val charset: String, val text: String)

    /**
     * ⚠️ `CharsetDecoder` **不是线程安全**的（内部持有可变状态）。
     *
     * 旧实现把它缓存在单例里复用，而解码会被预览 / 搜索 / 缩略图 / 传输多个线程同时调用，
     * 并发时会读到彼此的中间状态（表现为随机判定「不是 UTF-8」而误回退 GBK，或直接抛
     * `IllegalStateException`）。这里改为每次新建：一次解码只建一个 decoder，开销可忽略。
     */
    private fun strictUtf8Decoder() = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    fun decode(bytes: ByteArray): Decoded = when {
        // ⚠️ UTF-32LE 的 BOM（FF FE 00 00）以 FF FE 开头，必须先于 UTF-16LE 判断
        isBomUtf32Le(bytes) -> Decoded("UTF-32LE", decodeUtf32(bytes, 4, littleEndian = true))
        isBomUtf32Be(bytes) -> Decoded("UTF-32BE", decodeUtf32(bytes, 4, littleEndian = false))

        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Decoded("UTF-8 (BOM)", String(bytes, 3, bytes.size - 3, Charsets.UTF_8))

        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Decoded("UTF-16LE", String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE))

        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Decoded("UTF-16BE", String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE))

        isValidUtf8(bytes) -> Decoded("UTF-8", String(bytes, Charsets.UTF_8))

        else -> guessLegacy(bytes)
    }

    /** 非 UTF 系的启发式（见类注释的顺序）。 */
    private fun guessLegacy(bytes: ByteArray): Decoded {
        // 旧行为的口径：GBK 宽松解出且没有替换符 —— 大量简体中文场景直接命中
        val gbkLenient = runCatching { String(bytes, charset("GBK")) }.getOrNull()
            ?.takeIf { !it.contains('\uFFFD') }
        // Big5 候选必须**严格**解出（宽松解码会把任何字节都「解出来」，无法比较）
        val big5 = strictDecode(bytes, "Big5")

        // 双侧常用字评分：Big5 侧必须 ≥2 且**严格多于** GBK 侧才推翻 GBK。
        // 字节碰撞字对（地↔華、拜↔問…）两侧同分 → 平局保持 GBK（宁缺勿错）。
        val (gbkScore, big5Score) = scoreBothWays(gbkLenient, big5)
        if (big5 != null && big5Score >= 2 && big5Score > gbkScore) return Decoded("Big5", big5)
        gbkLenient?.let { return Decoded("GBK", it) }
        // GBK 也解不出来：Big5 的「严格解出」结果仍优于 Latin-1（乱码），最后才是 Latin-1
        big5?.let { return Decoded("Big5", it) }
        return Decoded("ISO-8859-1", String(bytes, Charsets.ISO_8859_1))
    }

    /**
     * 双侧评分（GBK ↔ Big5 判别核心）：
     * 两个解码串按**字节位对齐**后逐字对计常用字命中——GBK 与 Big5 都对 ≥0x80 的字节
     * 吃 2 字节码元，字符边界天然一致，因此可以直接按下标配对。
     *
     * Big5 侧计数时跳过「GBK 侧是符号 / 拼音 / 制表等非汉字」的字节对：那不是汉字
     * 字节，不能给 Big5 作证（否则 BBS 制表符文本、拼音注音会无辜被认成 Big5）。
     */
    private fun scoreBothWays(gbk: String?, big5: String?): Pair<Int, Int> {
        val gFallback = gbk?.let { commonHits(it) } ?: -1
        val bFallback = big5?.let { commonHits(it) } ?: -1
        if (gbk == null || big5 == null || gbk.length != big5.length) return gFallback to bFallback
        var g = 0
        var b = 0
        for (i in gbk.indices) {
            val y = gbk[i]
            val t = big5[i]
            if (y in COMMON_HANZI) g++
            if (t in COMMON_HANZI && !isNonHanziUnit(y)) b++
        }
        return g to b
    }

    private fun commonHits(text: String): Int = text.count { it in COMMON_HANZI }

    /** GBK 侧解出的是符号 / 拼音 / 制表 / 全角等非汉字——这两个字节不该给 Big5 侧作证。 */
    private fun isNonHanziUnit(c: Char): Boolean =
        c in '\u00A0'..'\u2BFF' || c in '\u3000'..'\u303F' || c in '\uFF00'..'\uFFEF'

    private fun strictDecode(bytes: ByteArray, charsetName: String): String? = try {
        val decoder = Charset.forName(charsetName).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        null
    }

    fun isValidUtf8(bytes: ByteArray): Boolean = try {
        strictUtf8Decoder().decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    // ------------------------------------------------------------------ 特定编码的读写
    // 下面两个函数与 decode() 共用同一套编码名；「编码名 → 编解码器」的映射只此一处维护，
    // 编辑器的分段读取 / 保存写回都走这里（避免读 / 写 / 识别三张表各自漂移）。

    /**
     * 按识别出的 [charset] 解码一段字节（分段浏览用）。
     * UTF-8 / UTF-16 / UTF-32 系会剥离**存在时**的 BOM（只有首页分段带 BOM；
     * 非首页段盲目跳过 4 字节会吞掉本页首字符——UTF-32 必须按实际 BOM 判断）。
     */
    fun decodeWith(charset: String, bytes: ByteArray): String = when (charset) {
        "UTF-8 (BOM)" -> {
            val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
            String(bytes, start, bytes.size - start, Charsets.UTF_8)
        }
        "UTF-8" -> String(bytes, Charsets.UTF_8)
        "GBK" -> String(bytes, charset("GBK"))
        "Big5" -> String(bytes, charset("Big5"))
        "UTF-16LE" -> {
            val start = if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) 2 else 0
            String(bytes, start, bytes.size - start, Charsets.UTF_16LE)
        }
        "UTF-16BE" -> {
            val start = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) 2 else 0
            String(bytes, start, bytes.size - start, Charsets.UTF_16BE)
        }
        "UTF-32LE" -> decodeUtf32(bytes, if (isBomUtf32Le(bytes)) 4 else 0, littleEndian = true)
        "UTF-32BE" -> decodeUtf32(bytes, if (isBomUtf32Be(bytes)) 4 else 0, littleEndian = false)
        "ISO-8859-1" -> String(bytes, Charsets.ISO_8859_1)
        else -> String(bytes, Charsets.UTF_8)
    }

    /** [encode] 的产物。[charset] 是**实际写入**的编码名（无法表示时已回退 UTF-8）。 */
    data class Encoded(val bytes: ByteArray, val charset: String)

    /**
     * 按 [charset] 编码写回（保存用）：
     * - `"UTF-8 (BOM)"` / `"UTF-16LE/BE"` / `"UTF-32LE/BE"` **重建 BOM**——这类文件完全靠 BOM 被识别，
     *   丢 BOM 保存后连本应用自己都读不回来（会走 UTF-8 / GBK 启发式 → 乱码）；
     * - GBK / Big5 / ISO-8859-1 做往返校验：无法表示的字符
     *   （编码器会写成 `?`）改为回退 UTF-8 输出，由 [Encoded.charset] 告知调用方实际编码，
     *   状态栏据此提示「已转存」。
     */
    fun encode(text: String, charset: String): Encoded = when (charset) {
        "UTF-8 (BOM)" -> Encoded(BOM_UTF8 + text.toByteArray(Charsets.UTF_8), charset)
        "UTF-8" -> Encoded(text.toByteArray(Charsets.UTF_8), charset)
        "UTF-16LE" -> Encoded(BOM_UTF16LE + text.toByteArray(Charsets.UTF_16LE), charset)
        "UTF-16BE" -> Encoded(BOM_UTF16BE + text.toByteArray(Charsets.UTF_16BE), charset)
        "UTF-32LE" -> Encoded(BOM_UTF32LE + encodeUtf32(text, littleEndian = true), charset)
        "UTF-32BE" -> Encoded(BOM_UTF32BE + encodeUtf32(text, littleEndian = false), charset)
        "GBK" -> encodeChecked(text, charset("GBK"), charset)
        "Big5" -> encodeChecked(text, charset("Big5"), charset)
        "ISO-8859-1" -> encodeChecked(text, Charsets.ISO_8859_1, charset)
        else -> Encoded(text.toByteArray(Charsets.UTF_8), "UTF-8")
    }

    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val BOM_UTF32LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)
    private val BOM_UTF32BE = byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte())

    private fun encodeChecked(text: String, cs: Charset, name: String): Encoded {
        val bytes = text.toByteArray(cs)
        return if (String(bytes, cs) == text) Encoded(bytes, name)
        else Encoded(text.toByteArray(Charsets.UTF_8), "UTF-8")
    }

    // ------------------------------------------------------------------ UTF-32（手写）

    private fun isBomUtf32Le(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()

    private fun isBomUtf32Be(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()

    /** 从 [offset] 起按 4 字节码元解 UTF-32；非法码点（越界 / 代理区 / 截断）落成 U+FFFD。 */
    private fun decodeUtf32(bytes: ByteArray, offset: Int, littleEndian: Boolean): String {
        val sb = StringBuilder((bytes.size - offset).coerceAtLeast(0) / 4)
        var i = offset
        while (i + 3 < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            val b2 = bytes[i + 2].toInt() and 0xFF
            val b3 = bytes[i + 3].toInt() and 0xFF
            val cp = if (littleEndian) {
                b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
            val valid = cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF
            sb.appendCodePoint(if (valid) cp else 0xFFFD)
            i += 4
        }
        return sb.toString()
    }

    private fun encodeUtf32(text: String, littleEndian: Boolean): ByteArray {
        val out = ByteArrayOutputStream(text.length * 4)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (littleEndian) {
                out.write(cp and 0xFF)
                out.write((cp ushr 8) and 0xFF)
                out.write((cp ushr 16) and 0xFF)
                out.write((cp ushr 24) and 0xFF)
            } else {
                out.write((cp ushr 24) and 0xFF)
                out.write((cp ushr 16) and 0xFF)
                out.write((cp ushr 8) and 0xFF)
                out.write(cp and 0xFF)
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ 常用字表（GBK ↔ Big5 双侧评分）

    /**
     * 常用字表 = 简体 + 繁体（约 1500 / 1200 字），只服务于 GBK ↔ Big5 的「双侧评分」。
     *
     * 为什么不再用「简繁特征字计数」：简体字与繁体的对应字存在大量**字节碰撞**——
     * 简体字的 GBK 字节按 Big5 解出，恰好是某个常用繁体字（地/GBK=B5D8 == 華/Big5=B5D8、
     * 临==還、拜==問、瓜==圖……数百组，含大量日常用字）。短 GBK 文本凑够两个碰撞字，
     * 旧计数法就会把它误判成 Big5 显示乱码（v2.0.7 回归）。改双侧评分后碰撞对两侧
     * 同分，平局回落 GBK（宁缺勿错）。
     */
    private const val COMMON_SIMPLIFIED = "的一是了我不人在他有这上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总从无情己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给名法间斯知世什两次使身者被高已亲其进此话常与活正感见明问力理尔点文几定本公特做外孩相西果走将月十实向声车全信重三机工物气每并别真打太新比才便夫再书部水像眼等体却加电主界门利海受听表德少克代员许先口由死安写性马光白或住难望教命花结乐色更拉东神记处让母父应直字场平报友关放至张认接告入笑内英军候民岁往何度山觉路带万男边风解叫任金快原吃妈变通师立象数四失满战远格士音轻目条呢病始达深完今提求清王化空业思切怎非找片罗钱吗语元喜曾离飞科言干流欢约各即指合反题必该论交终林请医晚制球决传画保读运及则房早院量苦火布品近坐产答星精视五连司巴奇管类未朋且婚台夜青北队久乎越观落尽形影红爸百令周吧识步希亚术留市半热送兴造谈容极随演收首根讲整式取照办强石古华拿计您装似足双妻尼转诉米称丽客南领节衣站黑刻统断福城故历惊脸选包紧争另建维绝树系伤示愿持千史谁准联妇纪基买志静阿诗独复痛消社算义竟确酒需单治卡幸兰念举仅钟怕共毛句息功官待究跟穿室易游程号居考突皮哪费倒价图具刚脑永歌响商礼细专黄块脚味灵改据般破引食仍存众注笔甚某沉血备习校默务土微娘须试怀料调广苏显赛查密议底列富梦错座参八除跑亮假印设线温虽掉京初养香停际致阳纸李纳验助激够严证帝饭忘趣支春集丈木研班普导顿睡展跳获艺六波察群皇段急庭创区奥器谢弟店否害草排背止组州朝封睛板角况曲馆育忙质河续哥呼若推境遇雨标姐充围案伦护冷警贝著雪索剧啊船险烟依斗值帮汉慢佛肯闻唱沙层局伯族低玩资屋击速顾泪洲团圣旁堂兵七露园牛哭旅街劳型烈姑陈莫鱼异抱宝权鲁简态级票怪寻杀律胜守派楼桥宣盘腾宇紫慕秋祝祖刘夕探宜恩仪乡县镇村塔课绩努训技网络序件份拜昼晨昏季夏冬霜云雾雷虹霞湖洋岛港岸滩铁铜银钢珠贵厚薄浅短宽窄旧差善恶丑败输赢负亡衰荣辱悲怒哀惧恨仇怨暗弱左右尾菜汤粥饺铺桌椅床窗户柜灯镜洗刷擦扫拖抹拾扔捡搬抬扛拽拔插拧寄托递付欠赚赔借搂砃琄谅砆耇簈栋谋琌琍砍簍堕逗琘簙砛砞耞氟怠氨琩氮砰堵琵吹尺逼尿琿簿硁硂硄摆瑆籇瑈硈硉届遏硑籓籔衡摸籹灿粂粄粇肈璉钉炊璓悔碔璚肚璝碝碞钡璣撤沧钩碭沮钮袱炳钵璶璸確岿粿壁瓁磅泊郎胔瓜惠瓣烦烩铬糶糷約紇紈甉焊褐礚洛礛甝甡產锣甧崩攫锭洱甶礶夹礹贺蔼贾腀祇腊絋畍絏畑镑畒奔祘酚腞赣腨絬腳酵腹膀超馋妓禗醚禜禟冠禣禦疨禫疭禲喷痁緄秆秈跋跌臔秖緗痙痜凝秤秨臩臫淮臮痷秸闽縀丁刁币瘆戈阑縒稟渤瘤舦舧娩稬刮種稰稱舱稲戳临嘶阶嘿虏穓癘穝癟牡穦繦牧繧穨陪虫癬繰癳艶繷癸驹繻承皊骋纐皑隔芖纗皘废芠窥纯骸窾盉竊绑拒竒盞狟狡盡竡盢竤蛤狥狦竧蛮端绰竲狶盽狾盾盿眀挂弄笆霉眎眏伐猔眔眖弘猠匡挡眤弧猧猭笲笴猵笵笷笿獀筁魁杆材荐策坝杠荡靡佩佰睲獴蝴獵獶荷獹獺孽獽筿玂瞅辅羆箇澈瞈箉莉瞏瞒羘羛螟龟玡玥厨辨厩羪玭羭环辰玱莱瞴瞶瞷掸玻讽辽诀埃珇篈篊菌柑毖矗毙诡矪叭矮俱翴珿按个录项压页修径缩置测码换编览滑状删载例预播批辑栏返移侧模搜退择版增操误控滚顶框源染档势签替效渲截盖限触协配释补覆浏含弹审归库键二屏启析闭判滤隐符剪服构冲函储暂避抽贴略核执链零丢恢免逐互绿缓享固顺锁串齐规缀检兜藏描坏频余拦属景套锚防漏横占擎继仓忆跨圆优拆域填循秒浮射添乱扩访赖叠供透授束轮逻适偏抛范匹词钥翻映诊唯缺兼拼勾吞箭缘靠松册损叉忽遍禁稿责帧幕弃询素阅址恒均折款残屉阈降途追倍灰壳契粘蒙硬遮允剩销距阻末竖竞登绘延罩逆恰焦馈航副章卷唤轨聚悬牌拟译针障架央私箱账散既混迁矢减凭泄附纵彻述础诺累敛职塞裁醒舍剥沿敏斜织较柄裸脏采租划驱割驻媒抢括挪毁寸旦幅震圈栈胶冒净凑抓挤升率篇闪闲虚囊省漂坑偶颜委稍稳拢摘嵌踩阴陷暴绪御"

    private const val COMMON_TRADITIONAL = "的一是了我不人在他有這上們來到時大地為子中你說生國年著就那和要她出也得裡後自以會家可下而過天去能對小多然於心學麼之都好看起發當沒成只如事把還用第樣道想作種開美總從無情己面最女但現前些所同日手又行意動方期它頭經長兒回位分愛老因很給名法間斯知世什兩次使身者被高已親其進此話常與活正感見明問力理爾點文幾定本公特做外孩相西果走將月十實向聲車全信重三機工物氣每並別真打太新比才便夫再書部水像眼等體卻加電主界門利海受聽表德少克代員許先口由死安寫性馬光白或住難望教命花結樂色更拉東神記處讓母父應直字場平報友關放至張認接告入笑內英軍候民歲往何度山覺路帶萬男邊風解叫任金快原吃媽變通師立象數四失滿戰遠格士音輕目條呢病始達深完今提求清王化空業思切怎非找片羅錢嗎語元喜曾離飛科言乾流歡約各即指合反題必該論交終林請醫晚制球決傳畫保讀運及則房早院量苦火布品近坐產答星精視五連司巴奇管類未朋且婚台夜青北隊久乎越觀落盡形影紅爸百令周吧識步希亞術留市半熱送興造談容極隨演收首根講整式取照辦強石古華拿計您裝似足雙妻尼轉訴米稱麗客南領節衣站黑刻統斷福城故歷驚臉選包緊爭另建維絕樹系傷示願持千史誰准聯婦紀基買志靜阿詩獨復痛消社算義竟確酒需單治卡幸蘭念舉僅鐘怕共毛句息功官待究跟穿室易遊程號居考突皮哪費倒價圖具剛腦永歌響商禮細專黃塊腳味靈改據般破引食仍存眾注筆甚某沉血備習校默務土微娘須試懷料調廣蘇顯賽查密議底列富夢錯座參八除跑亮假印設線溫雖掉京初養香停際致陽紙李納驗助激夠嚴證帝飯忘趣支春集丈木研班普導頓睡展跳獲藝六波察群皇段急庭創區奧器謝弟店否害草排背止組州朝封睛板角況曲館育忙質河續哥呼若推境遇雨標姐充圍案倫護冷警貝著雪索劇啊船險煙依鬥值幫漢慢佛肯聞唱沙層局伯族低玩資屋擊速顧淚洲團聖旁堂兵七露園牛哭旅街勞型烈姑陳莫魚異抱寶權魯簡態級票怪尋殺律勝守派找樓檔案軟體網路設定資料夾視窗帳號密碼開啟關閉儲存刪除複製貼上搜尋說明幫助問題錯誤成敗下載上傳更新安裝移除選擇確認取消資訊執行預覽電腦歷史紀錄臺灣這裡那裡什麼為怎嗎爺奶奶姑叔伯舅姨嬸嫂侄孫兄弟姐妹夫妻兒女爸媽檔軟體網路設定資料夾視窗帳號密碼開啟關閉儲存刪除複製貼上搜尋說明幫助問題錯誤成敗下載上傳更新安裝移除選擇確認取消資訊執行預覽電腦歷史紀錄臺灣這裡那裡什麼為怎嗎爺奶姑叔伯舅姨嬸嫂侄孫兄弟姐妹夫妻兒女爸媽製帳碼項頁賬廠務職執齊億償僑廢廳縣鄉鄰釋鑰鑽鏡鐘審純證資輯編裝塊歲幾帥燈熱愛話讀寫聽覺課練習試類數據處理變換鍵盤存確認腦歷錄網絡連線號複尋幫錯設預執應資訊歷廠廣東與馬鳥魚龍門問間聞風區醫華圖團園圍漢體鐵銀錢關際還種據萬檔軟體網頁項編輯預覽電器連結號碼週導報導認傑佈亞歐楊趙黃吳鄭劉專簡單係聯絡瀏權費稅價議討論營輸遞郵頻節豐餘黨隊藥構傳圖書館員證書寫計算機程式設計系統檔案夾視窗關閉開啟用選擇取消確認輸入輸出儲存空間記憶體處理器顯示器鍵盤滑鼠網路攝影機麥克風喇叭耳機電池充電器連接線傳輸速度資訊安全密碼保護備份還原更新安裝移除程式軟件硬件數位時代社會經濟政治文化教育體育娛樂藝術音樂電影戲劇節目新聞報導雜誌書籍報紙廣播電視頻道"

    private val COMMON_HANZI: Set<Char> = (COMMON_SIMPLIFIED + COMMON_TRADITIONAL).toSet()
}
