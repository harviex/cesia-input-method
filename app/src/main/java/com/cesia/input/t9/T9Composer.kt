package com.cesia.input.t9

/**
 * T9 组合生成器：把数字序列展开成「合法拼音组合」，并按拼音规律剪枝。
 *
 * 设计（与用户确认）：
 * - 输入数字序列（如 2345），先取前 2 个数字生成「首层组合」（如 2×3 = 9 种字母组合）
 * - 仅保留「符合拼音规律」的组合（声母+韵母合法音节），非法组合（如 bf、xm）剔除
 * - 每层最多 3×3 = 9 种，超出由 UI 上滑滚动选择，避免组合数爆炸
 * - 选中某一组合后，作为「锁定前缀」，再叠加剩余数字生成下一层
 */
object T9Composer {

    // 数字 → 字母
    private val DIGIT_LETTERS = mapOf(
        2 to "abc", 3 to "def", 4 to "ghi", 5 to "jkl",
        6 to "mno", 7 to "pqrs", 8 to "tuv", 9 to "wxyz"
    )

    // 合法拼音音节表（无声调），用于剪枝
    private val VALID_SYLLABLES = setOf(
        "a","ai","an","ang","ao",
        "ba","bai","ban","bang","bao","bei","ben","beng","bi","bia","bie","biao","bie","bin","bing","bo","bu",
        "ca","cai","can","cang","cao","ce","cen","ceng","cha","chai","chan","chang","chao","che","chen","cheng","chi","chong","chou","chu","chua","chuai","chuan","chuang","chui","chun","chuo","ci","cong","cou","cu","cuan","cui","cun","cuo",
        "da","dai","dan","dang","dao","de","dei","den","deng","di","dia","dian","diao","die","ding","diu","dong","dou","du","duan","dui","dun","duo",
        "e","ei","en","eng","er",
        "fa","fan","fang","fei","fen","feng","fo","fou","fu",
        "ga","gai","gan","gang","gao","ge","gei","gen","geng","gong","gou","gu","gua","guai","guan","guang","gui","gun","guo",
        "ha","hai","han","hang","hao","he","hei","hen","heng","hong","hou","hu","hua","huai","huan","huang","hui","hun","huo",
        "ji","jia","jian","jiang","jiao","jie","jin","jing","jiong","jiu","ju","juan","jue","jun",
        "ka","kai","kan","kang","kao","ke","ken","keng","kong","kou","ku","kua","kuai","kuan","kuang","kui","kun","kuo",
        "la","lai","lan","lang","lao","le","lei","leng","li","lia","lian","liang","liao","lie","lin","ling","liu","long","lou","lu","lv","lvan","lve","luan","lun","luo",
        "ma","mai","man","mang","mao","me","mei","men","meng","mi","mian","miao","mie","min","ming","miu","mo","mou","mu",
        "na","nai","nan","nang","nao","ne","nei","nen","neng","ni","nian","niang","niao","nie","nin","ning","niu","nong","nou","nu","nv","nvan","nve","nuan","nun","nuo",
        "o","ou",
        "pa","pai","pan","pang","pao","pei","pen","peng","pi","pian","piao","pie","pin","ping","po","pou","pu",
        "qi","qia","qian","qiang","qiao","qie","qin","qing","qiong","qiu","qu","quan","que","qun",
        "ra","ran","rang","rao","re","ren","reng","ri","rong","rou","ru","rua","ruan","rui","run","ruo",
        "sa","sai","san","sang","sao","se","sen","seng","sha","shai","shan","shang","shao","she","shen","sheng","shi","shou","shu","shua","shuai","shuan","shuang","shui","shun","shuo","si","song","sou","su","suan","sui","sun","suo",
        "ta","tai","tan","tang","tao","te","teng","ti","tian","tiao","tie","ting","tong","tou","tu","tuan","tui","tun","tuo",
        "wa","wai","wan","wang","wei","wen","weng","wo","wu",
        "xi","xia","xian","xiang","xiao","xie","xin","xing","xiong","xiu","xu","xuan","xue","xun",
        "ya","yai","yan","yang","yao","ye","yi","yin","ying","yo","yong","you","yu","yuan","yue","yun",
        "za","zai","zan","zang","zao","ze","zei","zen","zeng","zha","zhai","zhan","zhang","zhao","zhe","zhen","zheng","zhi","zhong","zhou","zhu","zhua","zhuai","zhuan","zhuang","zhui","zhun","zhuo","zi","zong","zou","zu","zuan","zui","zun","zuo"
    )

    /** 单数字对应的字母（如 2 -> "abc"） */
    fun lettersOf(digit: Char): String = DIGIT_LETTERS[digit.digitToInt()] ?: ""

    /**
     * 给定已锁定的前缀拼音（可能为空）和剩余数字序列，
     * 生成「下一层可选组合」（最多 9 种，合法拼音优先）。
     *
     * 行为：
     * - 若剩余数字 >= 2，取前 2 个数字，笛卡尔积生成组合（最多 3×3=9）
     * - 若剩余数字 == 1，取该数字的字母（最多 4）
     * - 每个组合拼到前缀后，过滤出「整体是合法拼音音节 OR 是某合法音节的前缀」的项
     *   （前缀匹配保证用户还能继续往下选，不会被提前截断）
     * - 返回时合法完整音节排前，纯前缀排后
     */
    fun nextLayer(prefix: String, digits: String): List<String> {
        if (digits.isEmpty()) return emptyList()
        val take = if (digits.length >= 2) digits.take(2) else digits.take(1)
        val letterSets = take.map { lettersOf(it) }
        // 笛卡尔积
        var combos = listOf("")
        for (letters in letterSets) {
            combos = combos.flatMap { prefixStr -> letters.map { prefixStr + it } }
        }
        // 拼上前缀，过滤
        val withPrefix = combos.map { prefix + it }
        val valid = withPrefix.filter { isPinyinPrefix(it) }
        // 合法完整音节优先
        val sorted = valid.sortedWith(compareBy({ !VALID_SYLLABLES.contains(it) }, { it.length }))
        // 最多 9 种
        return sorted.take(9)
    }

    /**
     * 判断 str 是否「合法拼音前缀」：
     * - 本身是合法音节，或
     * - 存在某个合法音节以 str 开头（可继续扩展）
     */
    fun isPinyinPrefix(str: String): Boolean {
        if (str.isEmpty()) return false
        if (VALID_SYLLABLES.contains(str)) return true
        // 检测是否为零声母整体（yi/wu/yu 等）
        if (str.length <= 1) {
            // 单字母：作为声母或零声母起始，允许继续
            return true
        }
        // 是否为某音节的起始子串
        return VALID_SYLLABLES.any { it.startsWith(str) }
    }

    /** 判断是否是完整合法音节 */
    fun isFullSyllable(str: String): Boolean = VALID_SYLLABLES.contains(str)
}
