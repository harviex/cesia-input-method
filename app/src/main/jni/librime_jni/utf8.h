// Minimal utf8 helper - replaces boost::utf8 for rime_jni
#pragma once

#include <cstdint>
#include <string_view>

namespace utf8 {
namespace unchecked {

// Count UTF8 code points between two pointers
inline size_t distance(const char* start, const char* end) {
    size_t count = 0;
    const uint8_t* p = reinterpret_cast<const uint8_t*>(start);
    const uint8_t* last = reinterpret_cast<const uint8_t*>(end);
    while (p < last) {
        uint8_t c = *p;
        if (c < 0x80) {
            p += 1;
        } else if ((c & 0xE0) == 0xC0) {
            p += 2;
        } else if ((c & 0xF0) == 0xE0) {
            p += 3;
        } else if ((c & 0xF8) == 0xF0) {
            p += 4;
        } else {
            p += 1; // invalid, skip
        }
        count++;
    }
    return count;
}

} // namespace unchecked
} // namespace utf8
