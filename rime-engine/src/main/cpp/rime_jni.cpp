#include "rime_jni.h"
#include <android/log.h>
#include <string>
#include <vector>
#include <map>

#define LOG_TAG "CesiaRime-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// TODO: 替换为真实 librime 头文件
// #include <rime_api.h>

namespace {

// Session 状态
struct StubSession {
    std::string composing;
    std::vector<std::string> candidates;
    int total_candidates = 0;
    int pageSize = 5;
    int currentPage = 0;

    int getPageCount() const {
        if (total_candidates <= 0) return 0;
        return (total_candidates + pageSize - 1) / pageSize;
    }
};

std::map<jlong, StubSession> sessions;
jlong nextSessionId = 1;
bool initialized = false;

// 简化的拼音→汉字映射（仅用于 stub 演示）
// 键格式: "pinyin" 或 "pinyin+tone" (1-4声调, 0/5轻声)
typedef std::map<std::string, std::vector<std::string>> DictType;

DictType& getDict() {
    static DictType dict;
    static bool populated = false;
    if (!populated) {
        // 单键快速输入（常用字）
        dict["a"] = {"啊", "阿", "爱", "安", "按"};
        dict["b"] = {"不", "爸", "把", "被", "本"};
        dict["c"] = {"从", "才", "次", "此", "错"};
        dict["d"] = {"的", "大", "到", "地", "多"};
        dict["e"] = {"嗯", "饿", "额", "鹅", "恶"};
        dict["f"] = {"发", "放", "非", "分", "方"};
        dict["g"] = {"个", "给", "过", "高", "公"};
        dict["h"] = {"好", "还", "很", "会", "和"};
        dict["i"] = {"一", "有", "要", "也", "以"};
        dict["j"] = {"就", "几", "己", "将", "及"};
        dict["k"] = {"看", "可", "开", "快", "考"};
        dict["l"] = {"了", "来", "里", "两", "老"};
        dict["m"] = {"没", "吗", "么", "每", "美"};
        dict["n"] = {"你", "那", "呢", "年", "能"};
        dict["o"] = {"哦", "噢", "喔"};
        dict["p"] = {"跑", "怕", "平", "片", "旁"};
        dict["q"] = {"去", "请", "前", "且", "其"};
        dict["r"] = {"人", "让", "如", "日", "入"};
        dict["s"] = {"是", "说", "生", "时", "三"};
        dict["t"] = {"他", "她", "它", "听", "天"};
        dict["u"] = {"出", "成", "长", "车", "吃"};
        dict["v"] = {"这", "着", "只", "中", "之"};
        dict["w"] = {"我", "问", "无", "为", "外"};
        dict["x"] = {"下", "想", "小", "新", "心"};
        dict["y"] = {"一", "有", "要", "也", "以"};
        dict["z"] = {"在", "再", "做", "最", "真"};

        // 全拼单字（常用）
        dict["bu"] = {"不", "部", "步", "布", "补"};
        dict["ba"] = {"把", "爸", "吧", "八", "百"};
        dict["bai"] = {"白", "百", "拜", "摆", "败"};
        dict["ban"] = {"半", "班", "办", "板", "般"};
        dict["bao"] = {"报", "保", "包", "宝", "抱"};
        dict["bei"] = {"被", "北", "备", "背", "杯"};
        dict["ben"] = {"本", "奔", "笨"};
        dict["bi"] = {"比", "必", "笔", "毕", "边"};
        dict["bian"] = {"边", "变", "便", "遍", "编"};
        dict["biao"] = {"表", "标"};
        dict["bie"] = {"别"};
        dict["bin"] = {"宾"};
        dict["bing"] = {"并", "病", "兵", "冰"};
        dict["bo"] = {"不", "波", "博", "播", "伯"};
        dict["bu2"] = {"不"};
        dict["bu4"] = {"不", "部", "步", "布"};

        dict["da"] = {"大", "打", "达"};
        dict["dai"] = {"大", "代", "带", "待", "袋"};
        dict["dan"] = {"但", "单", "担", "蛋", "当"};
        dict["dang"] = {"当", "党"};
        dict["dao"] = {"到", "道", "倒", "导"};
        dict["de"] = {"的", "得", "德"};
        dict["de2"] = {"得"};
        dict["de5"] = {"的"};
        dict["di"] = {"地", "第", "低", "底", "敌"};
        dict["dian"] = {"点", "电", "店"};
        dict["ding"] = {"定", "顶"};
        dict["dou"] = {"都", "斗"};
        dict["du"] = {"读", "独", "度"};
        dict["dui"] = {"对", "队"};
        dict["duo"] = {"多"};

        dict["er"] = {"而", "儿", "二", "耳"};

        dict["fa"] = {"发", "法"};
        dict["fan"] = {"反", "饭", "翻", "犯"};
        dict["fang"] = {"方", "放", "房", "访"};
        dict["fei"] = {"非", "飞", "费"};
        dict["fen"] = {"分", "份"};
        dict["feng"] = {"风", "封"};
        dict["fu"] = {"父", "夫", "服", "复", "负"};

        dict["ga"] = {"嘎"};
        dict["gai"] = {"该", "改", "概"};
        dict["gan"] = {"干", "感", "敢"};
        dict["gang"] = {"刚", "钢"};
        dict["gao"] = {"高", "告"};
        dict["ge"] = {"个", "各", "歌", "格"};
        dict["gei"] = {"给"};
        dict["gen"] = {"跟"};
        dict["geng"] = {"更"};
        dict["gong"] = {"公", "工", "功"};
        dict["gou"] = {"够", "狗"};
        dict["gu"] = {"古", "故", "顾"};
        dict["gua"] = {"挂"};
        dict["guai"] = {"怪"};
        dict["guan"] = {"关", "观", "管"};
        dict["guang"] = {"光"};
        dict["gui"] = {"贵", "鬼"};
        dict["guo"] = {"国", "过", "果"};

        dict["ha"] = {"哈"};
        dict["hai"] = {"还", "海", "害"};
        dict["han"] = {"汉", "寒"};
        dict["hang"] = {"行"};
        dict["hao"] = {"好", "号"};
        dict["he"] = {"和", "何", "合", "河"};
        dict["hei"] = {"黑"};
        dict["hen"] = {"很", "恨"};
        dict["heng"] = {"横"};
        dict["hong"] = {"红"};
        dict["hou"] = {"后", "候"};
        dict["hu"] = {"乎", "呼", "胡"};
        dict["hua"] = {"话", "花", "化"};
        dict["huai"] = {"坏"};
        dict["huan"] = {"还", "换", "欢"};
        dict["huang"] = {"黄", "皇"};
        dict["hui"] = {"会", "回", "灰"};
        dict["hun"] = {"婚"};
        dict["huo"] = {"或", "火", "活"};

        dict["ji"] = {"几", "己", "及", "即", "机"};
        dict["jia"] = {"家", "加", "价"};
        dict["jian"] = {"见", "间", "件", "建"};
        dict["jiang"] = {"将", "讲"};
        dict["jiao"] = {"叫", "教", "觉"};
        dict["jie"] = {"结", "接", "节", "姐"};
        dict["jin"] = {"进", "今", "金"};
        dict["jing"] = {"经", "京", "精"};
        dict["jiu"] = {"就", "九", "久"};
        dict["ju"] = {"举", "句", "具"};
        dict["jue"] = {"觉", "决"};
        dict["jun"] = {"军"};

        dict["ka"] = {"卡"};
        dict["kai"] = {"开"};
        dict["kan"] = {"看", "刊"};
        dict["kao"] = {"考"};
        dict["ke"] = {"可", "科", "克"};
        dict["ken"] = {"肯"};
        dict["kong"] = {"空"};
        dict["kou"] = {"口"};
        dict["ku"] = {"苦"};
        dict["kuai"] = {"快"};
        dict["kuan"] = {"宽"};
        dict["kuang"] = {"况"};
        dict["kui"] = {"亏"};
        dict["kun"] = {"困"};
        dict["kuo"] = {"扩"};

        dict["la"] = {"拉"};
        dict["lai"] = {"来"};
        dict["lan"] = {"蓝"};
        dict["lang"] = {"浪"};
        dict["lao"] = {"老"};
        dict["le"] = {"了", "乐"};
        dict["lei"] = {"类"};
        dict["leng"] = {"冷"};
        dict["li"] = {"里", "力", "立", "理"};
        dict["lian"] = {"连", "脸"};
        dict["liang"] = {"两", "亮"};
        dict["liao"] = {"了"};
        dict["lie"] = {"列"};
        dict["lin"] = {"林"};
        dict["ling"] = {"另", "领"};
        dict["liu"] = {"六", "流"};
        dict["long"] = {"龙"};
        dict["lou"] = {"楼"};
        dict["lu"] = {"路"};
        dict["lv"] = {"绿"};
        dict["luan"] = {"乱"};
        dict["lue"] = {"略"};
        dict["lun"] = {"论"};
        dict["luo"] = {"落"};

        dict["ma"] = {"吗", "马", "妈"};
        dict["mai"] = {"买", "卖"};
        dict["man"] = {"满"};
        dict["mang"] = {"忙"};
        dict["mao"] = {"毛"};
        dict["me"] = {"么"};
        dict["mei"] = {"没", "每", "美"};
        dict["men"] = {"们", "门"};
        dict["meng"] = {"梦"};
        dict["mi"] = {"米"};
        dict["mian"] = {"面"};
        dict["miao"] = {"秒"};
        dict["mie"] = {"灭"};
        dict["min"] = {"民"};
        dict["ming"] = {"名", "明"};
        dict["mo"] = {"么"};
        dict["mou"] = {"某"};
        dict["mu"] = {"母", "目"};

        dict["na"] = {"那", "拿"};
        dict["nai"] = {"奶"};
        dict["nan"] = {"男", "南"};
        dict["nang"] = {"囊"};
        dict["nao"] = {"脑"};
        dict["ne"] = {"呢"};
        dict["nei"] = {"内"};
        dict["nen"] = {"嫩"};
        dict["neng"] = {"能"};
        dict["ni"] = {"你"};
        dict["nian"] = {"年"};
        dict["niang"] = {"娘"};
        dict["niao"] = {"鸟"};
        dict["nie"] = {"捏"};
        dict["nin"] = {"您"};
        dict["ning"] = {"宁"};
        dict["niu"] = {"牛"};
        dict["nong"] = {"农"};
        dict["nu"] = {"努"};
        dict["nv"] = {"女"};
        dict["nuan"] = {"暖"};
        dict["nuo"] = {"诺"};

        dict["ou"] = {"偶"};

        dict["pa"] = {"怕"};
        dict["pai"] = {"排"};
        dict["pan"] = {"盘"};
        dict["pang"] = {"旁"};
        dict["pao"] = {"跑"};
        dict["pei"] = {"配"};
        dict["pen"] = {"喷"};
        dict["peng"] = {"朋"};
        dict["pi"] = {"皮"};
        dict["pian"] = {"片"};
        dict["piao"] = {"票"};
        dict["pie"] = {"撇"};
        dict["pin"] = {"品"};
        dict["ping"] = {"平"};
        dict["po"] = {"破"};
        dict["pou"] = {"剖"};
        dict["pu"] = {"普"};

        dict["qi"] = {"起", "其", "七", "气"};
        dict["qia"] = {"恰"};
        dict["qian"] = {"前", "千", "钱"};
        dict["qiang"] = {"强"};
        dict["qiao"] = {"桥"};
        dict["qie"] = {"且"};
        dict["qin"] = {"亲"};
        dict["qing"] = {"请", "情", "清"};
        dict["qiu"] = {"求"};
        dict["qu"] = {"去", "取"};
        dict["quan"] = {"全"};
        dict["que"] = {"却", "确"};
        dict["qun"] = {"群"};

        dict["ran"] = {"然"};
        dict["rang"] = {"让"};
        dict["rao"] = {"绕"};
        dict["re"] = {"热"};
        dict["ren"] = {"人"};
        dict["reng"] = {"仍"};
        dict["ri"] = {"日"};
        dict["rong"] = {"容"};
        dict["rou"] = {"肉"};
        dict["ru"] = {"如"};
        dict["ruan"] = {"软"};
        dict["rui"] = {"瑞"};
        dict["run"] = {"润"};
        dict["ruo"] = {"若"};

        dict["sa"] = {"撒"};
        dict["sai"] = {"赛"};
        dict["san"] = {"三"};
        dict["sang"] = {"桑"};
        dict["sao"] = {"扫"};
        dict["se"] = {"色"};
        dict["sen"] = {"森"};
        dict["seng"] = {"僧"};
        dict["sha"] = {"杀"};
        dict["shai"] = {"晒"};
        dict["shan"] = {"山"};
        dict["shang"] = {"上"};
        dict["shao"] = {"少"};
        dict["she"] = {"社"};
        dict["shei"] = {"谁"};
        dict["shen"] = {"什", "身"};
        dict["sheng"] = {"生", "声"};
        dict["shi"] = {"是", "时", "十", "事"};
        dict["shou"] = {"手"};
        dict["shu"] = {"书"};
        dict["shua"] = {"刷"};
        dict["shuai"] = {"帅"};
        dict["shuan"] = {"栓"};
        dict["shuang"] = {"双"};
        dict["shui"] = {"水"};
        dict["shun"] = {"顺"};
        dict["shuo"] = {"说"};
        dict["si"] = {"四", "死"};
        dict["song"] = {"送"};
        dict["sou"] = {"搜"};
        dict["su"] = {"速"};
        dict["suan"] = {"算"};
        dict["sui"] = {"虽", "岁"};
        dict["sun"] = {"孙"};
        dict["suo"] = {"所"};

        dict["ta"] = {"他", "她", "它"};
        dict["tai"] = {"太"};
        dict["tan"] = {"谈"};
        dict["tang"] = {"汤"};
        dict["tao"] = {"讨"};
        dict["te"] = {"特"};
        dict["teng"] = {"疼"};
        dict["ti"] = {"题"};
        dict["tian"] = {"天"};
        dict["tiao"] = {"条"};
        dict["tie"] = {"铁"};
        dict["ting"] = {"听"};
        dict["tong"] = {"同"};
        dict["tou"] = {"头"};
        dict["tu"] = {"图"};
        dict["tuan"] = {"团"};
        dict["tui"] = {"推"};
        dict["tun"] = {"吞"};
        dict["tuo"] = {"脱"};

        dict["wa"] = {"哇"};
        dict["wai"] = {"外"};
        dict["wan"] = {"完", "万"};
        dict["wang"] = {"王", "往"};
        dict["wei"] = {"为", "位"};
        dict["wen"] = {"文", "问"};
        dict["weng"] = {"翁"};
        dict["wo"] = {"我"};
        dict["wu"] = {"无", "五"};

        dict["xi"] = {"西", "希"};
        dict["xia"] = {"下"};
        dict["xian"] = {"先", "现"};
        dict["xiang"] = {"想", "向"};
        dict["xiao"] = {"小"};
        dict["xie"] = {"写"};
        dict["xin"] = {"新"};
        dict["xing"] = {"行"};
        dict["xiong"] = {"兄"};
        dict["xiu"] = {"修"};
        dict["xu"] = {"需"};
        dict["xuan"] = {"选"};
        dict["xue"] = {"学"};
        dict["xun"] = {"寻"};

        dict["ya"] = {"呀"};
        dict["yan"] = {"言"};
        dict["yang"] = {"样"};
        dict["yao"] = {"要"};
        dict["ye"] = {"也"};
        dict["yi"] = {"一", "以"};
        dict["yin"] = {"因"};
        dict["ying"] = {"应"};
        dict["yo"] = {"哟"};
        dict["yong"] = {"用"};
        dict["you"] = {"有"};
        dict["yu"] = {"与"};
        dict["yuan"] = {"元"};
        dict["yue"] = {"月"};
        dict["yun"] = {"运"};

        dict["za"] = {"杂"};
        dict["zai"] = {"在"};
        dict["zan"] = {"赞"};
        dict["zang"] = {"脏"};
        dict["zao"] = {"早"};
        dict["ze"] = {"则"};
        dict["zei"] = {"贼"};
        dict["zen"] = {"怎"};
        dict["zeng"] = {"增"};
        dict["zha"] = {"炸"};
        dict["zhai"] = {"摘"};
        dict["zhan"] = {"站"};
        dict["zhang"] = {"张"};
        dict["zhao"] = {"找"};
        dict["zhe"] = {"这"};
        dict["zhei"] = {"这"};
        dict["zhen"] = {"真"};
        dict["zheng"] = {"正"};
        dict["zhi"] = {"只", "知"};
        dict["zhong"] = {"中"};
        dict["zhou"] = {"周"};
        dict["zhu"] = {"主"};
        dict["zhua"] = {"抓"};
        dict["zhuai"] = {"拽"};
        dict["zhuan"] = {"转"};
        dict["zhuang"] = {"装"};
        dict["zhui"] = {"追"};
        dict["zhun"] = {"准"};
        dict["zhuo"] = {"着"};
        dict["zi"] = {"子"};
        dict["zong"] = {"总"};
        dict["zou"] = {"走"};
        dict["zu"] = {"组"};
        dict["zuan"] = {"钻"};
        dict["zui"] = {"最"};
        dict["zun"] = {"尊"};
        dict["zuo"] = {"做"};

        // 常用双字词
        dict["women"] = {"我们"};
        dict["nimen"] = {"你们"};
        dict["tamen"] = {"他们"};
        dict["zhege"] = {"这个"};
        dict["nage"] = {"那个"};
        dict["shenme"] = {"什么"};
        dict["zenme"] = {"怎么"};
        dict["weishenme"] = {"为什么"};
        dict["zaijian"] = {"再见"};
        dict["xiexie"] = {"谢谢"};
        dict["duibuqi"] = {"对不起"};
        dict["meiyou"] = {"没有"};
        dict["keyi"] = {"可以"};
        dict["yinggai"] = {"应该"};
        dict["xuyao"] = {"需要"};
        dict["xiang"] = {"想"};
        dict["zhidao"] = {"知道"};
        dict["renshi"] = {"认识"};
        dict["xihuan"] = {"喜欢"};
        dict["ai"] = {"爱"};
        dict["hao"] = {"好"};
        dict["shi"] = {"是"};
        dict["bu"] = {"不"};
        dict["de"] = {"的"};
        dict["le"] = {"了"};
        dict["zai"] = {"在"};
        dict["you"] = {"有"};
        dict["wo"] = {"我"};
        dict["ta"] = {"他"};
        dict["ni"] = {"你"};
        dict["dou"] = {"都"};
        dict["ye"] = {"也"};
        dict["jiu"] = {"就"};
        dict["hui"] = {"会"};
        dict["neng"] = {"能"};
        dict["yao"] = {"要"};
        dict["qu"] = {"去"};
        dict["lai"] = {"来"};
        dict["kan"] = {"看"};
        dict["shuo"] = {"说"};
        dict["zuo"] = {"做"};
        dict["gei"] = {"给"};
        dict["rang"] = {"让"};
        dict["ba"] = {"把"};
        dict["bei"] = {"被"};
        dict["cong"] = {"从"};
        dict["dao"] = {"到"};
        dict["he"] = {"和"};
        dict["gen"] = {"跟"};
        dict["dui"] = {"对"};
        dict["zai"] = {"在"};
        dict["shang"] = {"上"};
        dict["xia"] = {"下"};
        dict["li"] = {"里"};
        dict["zhong"] = {"中"};
        dict["wai"] = {"外"};
        dict["qian"] = {"前"};
        dict["hou"] = {"后"};
        dict["zuo"] = {"左"};
        dict["you4"] = {"右"};
        dict["dong"] = {"东"};
        dict["xi1"] = {"西"};
        dict["nan"] = {"南"};
        dict["bei3"] = {"北"};

        populated = true;
    }
    return dict;
}

// 获取候选词
std::vector<std::string> getCandidatesFor(const std::string &composing) {
    auto& dict = getDict();
    auto it = dict.find(composing);
    if (it != dict.end()) {
        return it->second;
    }
    // 前缀匹配
    std::vector<std::string> result;
    for (const auto& pair : dict) {
        if (pair.first.find(composing) == 0 && pair.first != composing) {
            for (const auto& c : pair.second) {
                result.push_back(c);
            }
        }
    }
    if (result.empty()) {
        result.push_back(composing);
    }
    // 去重
    std::sort(result.begin(), result.end());
    result.erase(std::unique(result.begin(), result.end()), result.end());
    return result;
}

} // anonymous namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeInitialize(JNIEnv *env, jobject thiz,
                                                          jstring data_dir, jstring shared_dir) {
    const char *dataPath = env->GetStringUTFChars(data_dir, nullptr);
    const char *sharedPath = env->GetStringUTFChars(shared_dir, nullptr);
    LOGI("初始化 Rime 引擎: data=%s, shared=%s", dataPath, sharedPath);

    // TODO: 替换为真实 librime 初始化
    // RIME_STRUCT(RimeTraits, traits);
    // traits.shared_data_dir = sharedPath;
    // traits.user_data_dir = dataPath;
    // traits.app_name = "cesia";
    // RimeSetup(&traits);
    // RimeInitialize(&traits);

    initialized = true;
    env->ReleaseStringUTFChars(data_dir, dataPath);
    env->ReleaseStringUTFChars(shared_dir, sharedPath);
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeShutdown(JNIEnv *env, jobject thiz) {
    LOGI("关闭 Rime 引擎");
    sessions.clear();
    initialized = false;
    // RimeFinalize();
}

JNIEXPORT jlong JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeCreateSession(JNIEnv *env, jobject thiz) {
    jlong id = nextSessionId++;
    sessions[id] = StubSession{};
    LOGI("创建 session %lld", (long long)id);
    return id;
    // return (jlong) RimeCreateSession();
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeDestroySession(JNIEnv *env, jobject thiz,
                                                              jlong session_id) {
    sessions.erase(session_id);
    LOGI("销毁 session %lld", (long long)session_id);
    // RimeDestroySession((RimeSessionId) session_id);
}

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeProcessKey(JNIEnv *env, jobject thiz,
                                                          jlong session_id, jstring key) {
    auto it = sessions.find(session_id);
    if (it == sessions.end()) return JNI_FALSE;

    const char *keyStr = env->GetStringUTFChars(key, nullptr);
    std::string k(keyStr);
    env->ReleaseStringUTFChars(key, keyStr);

    if (k == "BackSpace" || k == "Back") {
        if (!it->second.composing.empty()) {
            it->second.composing.pop_back();
        }
    } else if (k == "space" || k == "Space") {
        // 空格选择第一个候选词
        if (it->second.hasCandidates()) {
            it->second.composing.clear();
            it->second.candidates.clear();
        }
        return JNI_TRUE;
    } else if (k.length() == 1 && (k[0] < 'a' || k[0] > 'z') && (k[0] < '0' || k[0] > '9')) {
        // 非字母数字字符，直接提交
        it->second.composing.clear();
        it->second.candidates.clear();
        return JNI_TRUE;
    } else {
        // 追加字符到组合串
        it->second.composing += k;
    }

    // 更新候选词
    if (it->second.composing.empty()) {
        it->second.candidates.clear();
        it->second.total_candidates = 0;
    } else {
        it->second.candidates = getCandidatesFor(it->second.composing);
        it->second.total_candidates = static_cast<int>(it->second.candidates.size());
    }
    it->second.currentPage = 0;

    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetComposingText(JNIEnv *env, jobject thiz,
                                                                 jlong session_id) {
    auto it = sessions.find(session_id);
    if (it == sessions.end()) return env->NewStringUTF("");
    return env->NewStringUTF(it->second.composing.c_str());
}

JNIEXPORT jobjectArray JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetCandidates(JNIEnv *env, jobject thiz,
                                                             jlong session_id) {
    auto it = sessions.find(session_id);
    jclass stringClass = env->FindClass("java/lang/String");

    if (it == sessions.end() || it->second.candidates.empty()) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    // 分页返回当前页的候选词
    int start = it->second.currentPage * it->second.pageSize;
    int end = std::min(start + it->second.pageSize, (int)it->second.candidates.size());
    if (start >= (int)it->second.candidates.size()) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    int count = end - start;
    jobjectArray result = env->NewObjectArray(count, stringClass, nullptr);
    for (int i = 0; i < count; i++) {
        env->SetObjectArrayElement(result, i,
                                   env->NewStringUTF(it->second.candidates[start + i].c_str()));
    }
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeCommitComposition(JNIEnv *env, jobject thiz,
                                                                 jlong session_id) {
    auto it = sessions.find(session_id);
    if (it == sessions.end()) return env->NewStringUTF("");

    std::string committed = it->second.composing;
    it->second.composing.clear();
    it->second.candidates.clear();
    it->second.total_candidates = 0;
    return env->NewStringUTF(committed.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeSelectCandidate(JNIEnv *env, jobject thiz,
                                                               jlong session_id, jint index) {
    auto it = sessions.find(session_id);
    if (it == sessions.end()) return env->NewStringUTF("");

    int pageStart = it->second.currentPage * it->second.pageSize;
    size_t actualIndex = (size_t)(pageStart + index);
    if (actualIndex >= it->second.candidates.size()) {
        return env->NewStringUTF("");
    }

    std::string committed = it->second.candidates[actualIndex];
    it->second.composing.clear();
    it->second.candidates.clear();
    it->second.total_candidates = 0;
    return env->NewStringUTF(committed.c_str());
}

JNIEXPORT void JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeClearComposition(JNIEnv *env, jobject thiz,
                                                                jlong session_id) {
    auto it = sessions.find(session_id);
    if (it != sessions.end()) {
        it->second.composing.clear();
        it->second.candidates.clear();
        it->second.total_candidates = 0;
        it->second.currentPage = 0;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeChangePage(JNIEnv *env, jobject thiz,
                                                          jlong session_id, jboolean backward) {
    auto it = sessions.find(session_id);
    if (it == sessions.end()) return JNI_FALSE;

    int pageCount = it->second.getPageCount();
    if (pageCount <= 1) return JNI_FALSE;

    if (backward) {
        if (it->second.currentPage > 0) {
            it->second.currentPage--;
        }
    } else {
        if (it->second.currentPage < pageCount - 1) {
            it->second.currentPage++;
        }
    }
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetPageCount(JNIEnv *env, jobject thiz,
                                                            jlong session_id) {
    auto it = sessions.find(session_id);
    if (it == sessions.end()) return 0;
    return it->second.getPageCount();
}

JNIEXPORT jint JNICALL
Java_com_cesia_input_engine_rime_RimeJni_nativeGetCurrentPage(JNIEnv *env, jobject thiz,
                                                              jlong session_id) {
    auto it = sessions.find(session_id);
    if (it == sessions.end()) return 0;
    return it->second.currentPage;
}

} // extern "C"
