#!/usr/bin/env python3
"""
Rebuild pinyin_dict.json with high-quality char ordering.
Strategy:
1. Use CC-CEDICT for single chars (has proper pinyin + frequency)
2. Limit to top 50 chars per pinyin (most common first)
3. Ensure ultra-common chars are always first
"""
import json
import os
import sys
import re
from collections import defaultdict

# 超高频字（每个拼音前10位必须是这些）
ULTRA_COMMON = {
    'yi': '一已以意义艺依医宜移遗仪疑乙椅亿忆',
    'de': '的地得德底',
    'shi': '是时事十世市识实使始士氏示式势视',
    'you': '有又由友游右油优忧幽悠尤邮',
    'wo': '我握卧沃涡倭',
    'ta': '他她它塔',
    'zai': '在再载',
    'le': '了乐勒',
    'bu': '不部布步',
    'ren': '人任认',
    'ge': '个各革格隔',
    'dou': '都斗豆陡',
    'lai': '来赖',
    'shang': '上商伤尚',
    'dao': '到道导倒',
    'shuo': '说',
    'yao': '要',
    'hui': '会回挥汇',
    'hao': '好号毫',
    'xue': '学血雪',
    'jia': '家加价',
    'xin': '新心信',
    'hua': '化话划华',
    'guo': '国过果',
    'nian': '年',
    'chu': '出处初',
    'shen': '什身深神',
    'zuo': '作做坐',
    'ri': '日',
    'ye': '也业叶夜',
    'mei': '美每妹',
    'wan': '万完晚',
    'zhang': '长张章',
    'fang': '方房防',
    'qian': '前千钱',
    'hou': '后厚',
    'zhong': '中重种众',
    'xiao': '小校笑',
    'wen': '文问闻',
    'ti': '体提题',
    'li': '里力立利',
    'zhe': '这着者',
    'na': '那哪拿',
    'ne': '呢',
    'ma': '吗嘛',
    'ba': '吧把爸罢',
    'a': '啊',
    'kai': '开',
}

def parse_rime_dict(input_file, max_chars_per_pinyin=60):
    """Parse Rime dict.yaml, keep only top N chars per pinyin"""
    pinyin_map = defaultdict(list)
    header_end = False
    
    with open(input_file, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            if line == '...':
                header_end = True
                continue
            if not header_end:
                continue
            parts = line.split('\t')
            if len(parts) < 2:
                continue
            char = parts[0]
            pinyin_str = parts[1]
            weight_str = parts[2] if len(parts) > 2 else "0"
            weight_str = weight_str.replace('%', '')
            try:
                weight = int(float(weight_str))
            except ValueError:
                weight = 0
            if len(char) != 1:
                continue
            pinyin_clean = ''.join(c for c in pinyin_str if not c.isdigit())
            pinyin_map[pinyin_clean].append((weight, char))
    
    return pinyin_map

def sort_and_limit(pinyin, chars_with_weights, max_chars=60):
    """Sort chars: ultra-common first, then by weight, limit to max_chars"""
    # Deduplicate
    seen = {}
    for weight, char in chars_with_weights:
        if char not in seen or weight > seen[char]:
            seen[char] = weight
    
    chars_with_weights = [(w, c) for c, w in seen.items()]
    
    # Get ultra-common order for this pinyin
    ultra_order = ULTRA_COMMON.get(pinyin, '')
    
    def sort_key(item):
        weight, char = item
        if char in ultra_order:
            return (0, ultra_order.index(char), '')
        else:
            return (1, -weight, char)
    
    chars_with_weights.sort(key=sort_key)
    
    # Limit to max_chars
    chars_with_weights = chars_with_weights[:max_chars]
    
    return ''.join(c[1] for c in chars_with_weights)

def main():
    input_file = sys.argv[1] if len(sys.argv) > 1 else '/tmp/luna_pinyin.dict.yaml'
    output_file = sys.argv[2] if len(sys.argv) > 2 else 'app/src/main/assets/pinyin_dict.json'
    max_chars = int(sys.argv[3]) if len(sys.argv) > 3 else 60
    
    print(f"Rebuilding {output_file} (max {max_chars} chars per pinyin)")
    raw = parse_rime_dict(input_file)
    
    result = {}
    for pinyin, chars in raw.items():
        result[pinyin] = sort_and_limit(pinyin, chars, max_chars)
    
    os.makedirs(os.path.dirname(output_file), exist_ok=True)
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, separators=(',', ':'))
    
    total = sum(len(v) for v in result.values())
    print(f"Done: {len(result)} keys, {total} total chars, {os.path.getsize(output_file)} bytes")
    
    # Verify
    for p in ['yi', 'de', 'shi', 'kai', 'ye', 'wo', 'ta', 'zai', 'le', 'bu', 'ren', 'ge', 'dou', 'lai', 'shang', 'dao', 'shuo', 'yao', 'hui', 'hao', 'xue', 'jia', 'xin', 'hua', 'guo', 'nian', 'chu', 'shen', 'zuo', 'ri', 'mei', 'wan', 'zhang', 'fang', 'qian', 'hou', 'zhong', 'xiao', 'wen', 'ti', 'li', 'zhe', 'na', 'ne', 'ma', 'ba', 'a', 'you', 'er', 'san', 'si', 'wu', 'liu', 'qi', 'jiu']:
        chars = result.get(p, '')
        print(f"  {p}: {chars[:15]}")

if __name__ == '__main__':
    main()
