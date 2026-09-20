#pragma once
#include <cstddef>
#include <string>

// Length of the longest prefix of `s` that does not end in the middle of a UTF-8 sequence.
// Tokens can split multi-byte characters (emoji, CJK, accents); we only hand complete
// characters to Kotlin.
inline size_t utf8_complete_prefix(const std::string& s) {
    const size_t n = s.size();
    size_t i = n;
    for (int back = 0; back < 4 && i > 0; ++back) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) { --i; continue; }          // continuation byte, keep walking back
        size_t need = 1;
        if ((c & 0x80) == 0x00)      need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        const size_t have = n - (i - 1);
        return have >= need ? n : (i - 1);
    }
    return n;   // malformed input: flush everything, Kotlin decodes with replacement chars
}
