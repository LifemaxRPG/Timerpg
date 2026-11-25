// validate()-aware sampling: see guide.h. Each rule names the line of Dialogue.kt check() it follows.
#include "guide.h"

#include <algorithm>
#include <cstring>
#include <initializer_list>

namespace llm {

namespace {

bool is_word(char c) {  // JavaScript's ASCII \w (validate()'s \b)
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
}
bool is_space(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\v' || c == '\f'; }
bool is_upper(char c) { return c >= 'A' && c <= 'Z'; }
bool is_lower(char c) { return c >= 'a' && c <= 'z'; }

std::string lower(const std::string & s) {
    std::string o = s;
    for (char & c : o) if (is_upper(c)) c = (char) (c - 'A' + 'a');
    return o;
}

bool starts_with(const std::string & s, const std::string & p) { return s.size() >= p.size() && s.compare(0, p.size(), p) == 0; }

// Dialogue.kt COMMON: capitalized words a reply may use without the facts naming them
const char * const kCommon[] = {"Mr", "Mrs", "Ms", "Miss", "Mister", "Dr", "Sir", "Madam", "God", "Lord", "Monday", "Tuesday",
    "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "January", "February", "March", "April", "May", "June", "July",
    "August", "September", "October", "November", "December", "Christmas", "Easter", "English", "American"};
// Dialogue.kt PLACES (factsheet.ts PLACES)
const char * const kPlaces[] = {"library", "mill", "church", "chapel", "college", "bakery", "factory", "museum", "hospital",
    "station", "depot", "bridge", "hotel", "inn", "tavern", "pub", "saloon", "diner", "cafe", "restaurant", "theater",
    "theatre", "cinema", "market", "mall", "plaza", "courthouse", "jail", "prison", "cemetery", "graveyard", "lighthouse",
    "harbor", "harbour", "pier", "dock", "quarry", "warehouse", "festival", "parade", "carnival", "circus", "pharmacy", "gym",
    "stadium", "arena", "zoo", "castle", "tower", "monument", "statue", "fountain", "lake", "pond", "river", "creek", "farm",
    "ranch", "orchard", "barn", "dam", "canal", "reservoir", "mansion", "manor", "flood", "fire", "storm", "robbery", "murder",
    "scandal", "election"};
// Dialogue.kt STAGE, AI_WORDS and SHEET_WORDS, each alternative spelled out (whole words, any case)
const char * const kPhrases[] = {"i say", "i respond", "i reply", "i smile", "smile", "smiles", "nod", "nods", "my eyes",
    "flicker", "flickers", "let me know", "how can i help", "how can i assist", "anything else", "language model",
    "assistant", "warm and chatty", "gruff and guarded", "talk warmly", "talk curtly"};
// Dialogue.kt AI_START (at the very start of the line, no word boundary after)
const char * const kAiStart[] = {"as an ai", "i am an ai", "i'm an ai", "sorry, i"};
// Dialogue.kt PLACE: the words a place noun follows (at most two words between)
const char * const kDets[] = {"the", "a", "an", "our", "that", "this", "its"};

// Dialogue.kt wordsOf(): lowercase, runs of [a-z0-9']
bool is_gram_char(char c) { return is_lower(c) || (c >= '0' && c <= '9') || c == '\''; }
std::vector<std::string> words_of(const std::string & s) {
    std::vector<std::string> out;
    const std::string l = lower(s);
    std::string w;
    for (char c : l) {
        if (is_gram_char(c)) w += c;
        else if (!w.empty()) { out.push_back(w); w.clear(); }
    }
    if (!w.empty()) out.push_back(w);
    return out;
}

/** [T] has, at [i], an open or whole-word [p] that ends after [n0]: left boundary, and right boundary or the end. */
bool whole_at(const std::string & t, size_t i, size_t len, size_t n0) {
    if (i + len <= n0) return false;
    if (i > 0 && is_word(t[i - 1])) return false;
    return i + len == t.size() || !is_word(t[i + len]);
}

/** A new whole-word occurrence of [p] in [t] (lowercase), one that ends after [n0]. */
bool new_phrase(const std::string & t, const std::string & p, size_t n0) {
    if (p.empty() || t.size() < p.size()) return false;
    size_t from = n0 >= p.size() ? n0 - p.size() + 1 : 0;
    for (size_t i = t.find(p, from); i != std::string::npos; i = t.find(p, i + 1)) {
        if (whole_at(t, i, p.size(), n0)) return true;
    }
    return false;
}

/** At a sentence start (Dialogue.kt: nothing before, or . ! ? " “ ‘ ( before it, spaces aside): a capitalized word is free. */
bool sentence_start(const std::string & t, size_t i) {
    size_t k = i;
    while (k > 0 && is_space(t[k - 1])) --k;
    if (k == 0) return true;
    const char c = t[k - 1];
    if (c == '.' || c == '!' || c == '?' || c == '"' || c == '(') return true;
    if (k >= 3 && (t.compare(k - 3, 3, "\xE2\x80\x9C") == 0 || t.compare(k - 3, 3, "\xE2\x80\x98") == 0)) return true;
    return false;
}

/** A determiner (Dialogue.kt PLACE) at most two words before the word at [i] in [t] (lowercase), spaces between. */
bool det_before(const std::string & t, size_t i) {
    size_t k = i;
    for (int w = 0; w < 3; ++w) {
        if (k == 0 || !is_space(t[k - 1])) return false;
        while (k > 0 && is_space(t[k - 1])) --k;
        const size_t e = k;
        while (k > 0 && (is_lower(t[k - 1]) || t[k - 1] == '\'' || t[k - 1] == '-')) --k;
        if (k == e) return false;
        const std::string word = t.substr(k, e - k);
        if (k == 0 || !is_word(t[k - 1])) {
            for (const char * d : kDets) if (word == d) return true;
        } else {
            return false;  // (glued to a word character: not a word of its own)
        }
    }
    return false;
}

}  // namespace

void GuideRules::build(const std::string & prompt, const std::string & self, const std::vector<std::string> & recent) {
    phrases_.assign(std::begin(kPhrases), std::end(kPhrases));
    self_ = lower(self);
    // validate(): the sheet is the prompt without the player's turn and without "You already said: ..." lines
    static const std::string kUser = "<|im_start|>user\n";
    const size_t u = prompt.find(kUser);
    size_t ue = std::string::npos;
    if (u != std::string::npos) {
        ue = prompt.find("<|im_end|>", u + kUser.size());
        if (ue == std::string::npos) ue = prompt.size();
    }
    std::string sheet = u == std::string::npos ? prompt : prompt.substr(0, u) + prompt.substr(ue);
    const std::string said = u == std::string::npos ? std::string() : prompt.substr(u + kUser.size(), ue - u - kUser.size());
    for (size_t at = sheet.find("You already said: "); at != std::string::npos; at = sheet.find("You already said: ", at)) {
        const size_t e = sheet.find('\n', at);
        sheet.erase(at, e == std::string::npos ? std::string::npos : e - at);
    }
    // names: every word of the sheet and of the player's words that starts with a capital (the player's only count in
    // validate() where the sentence doubts them: left to it)
    names_.assign(std::begin(kCommon), std::end(kCommon));
    for (const std::string * src : std::initializer_list<const std::string *>{&sheet, &said}) {
        const std::string & s = *src;
        for (size_t i = 0; i < s.size();) {
            if (is_upper(s[i]) && (i == 0 || !is_word(s[i - 1]))) {
                size_t e = i;
                while (e < s.size() && is_word(s[e])) ++e;
                names_.push_back(s.substr(i, e - i));
                i = e;
            } else {
                ++i;
            }
        }
    }
    std::sort(names_.begin(), names_.end());
    names_.erase(std::unique(names_.begin(), names_.end()), names_.end());
    // places and events: those neither the sheet nor the player mentions (validate(): sheetLow.contains(noun))
    const std::string sl = lower(sheet), ul = lower(said);
    places_.clear();
    for (const char * p : kPlaces) if (sl.find(p) == std::string::npos && ul.find(p) == std::string::npos) places_.push_back(p);
    grams_.clear();
    for (const auto & r : recent) {
        const std::vector<std::string> ws = words_of(r);
        for (size_t i = 0; i + 4 <= ws.size(); ++i) grams_.push_back(ws[i] + " " + ws[i + 1] + " " + ws[i + 2] + " " + ws[i + 3]);
    }
    std::sort(grams_.begin(), grams_.end());
    grams_.erase(std::unique(grams_.begin(), grams_.end()), grams_.end());
}

const char * GuideRules::check(const std::string & text, const std::string & piece) const {
    if (piece.empty()) return "";
    // ACTION: *...* is cut out of the line; asterisks are never speech
    if (piece.find('*') != std::string::npos) return "an action";
    const std::string t = text + piece;
    const size_t n0 = text.size();
    const std::string tl = lower(t);
    // AI_START
    {
        size_t s = 0;
        while (s < tl.size() && (is_space(tl[s]) || tl[s] == '"' || tl[s] == '\'')) ++s;
        for (const char * p : kAiStart) {
            const size_t len = std::strlen(p);
            if (tl.size() - s >= len && tl.compare(s, len, p) == 0 && s + len > n0) return "sounded like an AI";
        }
    }
    // their own full name (validate(): contains, anywhere)
    if (!self_.empty()) {
        const size_t from = n0 >= self_.size() ? n0 - self_.size() + 1 : 0;
        const size_t at = tl.find(self_, from);
        if (at != std::string::npos && at + self_.size() > n0) return "said their own full name";
    }
    // STAGE, AI_WORDS, SHEET_WORDS
    for (const auto & p : phrases_) if (new_phrase(tl, p, n0)) return "stage directions or AI talk";
    // SHEET_YEAR: ", 1925" (the sheet's "<town>, <year>" tag read back)
    for (size_t c = n0 > 16 ? n0 - 16 : 0; c < tl.size(); ++c) {
        if (tl[c] != ',') continue;
        size_t d = c + 1;
        while (d < tl.size() && is_space(tl[d])) ++d;
        if (d + 4 > tl.size() || d + 4 <= n0) continue;
        const bool year = ((tl[d] == '1' && (tl[d + 1] == '8' || tl[d + 1] == '9')) || (tl[d] == '2' && tl[d + 1] == '0')) &&
                          tl[d + 2] >= '0' && tl[d + 2] <= '9' && tl[d + 3] >= '0' && tl[d + 3] <= '9';
        if (year && (d + 4 == tl.size() || !is_word(tl[d + 4]))) return "read the fact sheet back";
    }
    // CAPITALIZED: a name in mid-sentence that the facts lack (closed: exactly one of names_; still open: a start of one)
    {
        size_t i = n0 > 64 ? n0 - 64 : 0;
        while (i > 0 && is_word(t[i - 1])) --i;
        while (i < t.size()) {
            if (!is_word(t[i])) { ++i; continue; }
            size_t e = i;
            while (e < t.size() && is_word(t[e])) ++e;
            if (e >= n0 && is_upper(t[i]) && !sentence_start(t, i)) {
                bool all_lower = true;
                for (size_t k = i + 1; k < e; ++k) all_lower = all_lower && is_lower(t[k]);
                const bool open = e == t.size();
                const std::string w = t.substr(i, e - i);
                if (all_lower && !open && w.size() >= 2) {
                    if (!std::binary_search(names_.begin(), names_.end(), w)) return "made up a name";
                } else if (all_lower && open && w.size() >= 2) {
                    auto it = std::lower_bound(names_.begin(), names_.end(), w);
                    if (it == names_.end() || !starts_with(*it, w)) return "made up a name";
                }
            }
            i = e;
        }
    }
    // PLACE: a place or event the facts never mention, after "the", "a", "our"... (with "s" or "es" too)
    for (const auto & p : places_) {
        const size_t from = n0 >= p.size() + 2 ? n0 - p.size() - 2 : 0;
        for (size_t i = tl.find(p, from); i != std::string::npos; i = tl.find(p, i + 1)) {
            if (i == 0 || !is_space(tl[i - 1])) continue;
            const size_t j = i + p.size();
            bool word = false;
            for (const char * suf : std::initializer_list<const char *>{"", "s", "es"}) {
                const size_t sl = std::strlen(suf);
                if (tl.compare(j, sl, suf) != 0) continue;
                const size_t e = j + sl;
                if (e > tl.size()) continue;
                if (e > n0 && (e == tl.size() || !is_word(tl[e]))) word = true;
            }
            if (word && det_before(tl, i)) return "made up a place or event";
        }
    }
    // repeats(): four words in a row from one of their last replies (the sentence-overlap half is left to validate())
    if (!grams_.empty()) {
        std::vector<std::pair<size_t, size_t>> ws;  // the last words, from the end
        size_t k = tl.size();
        while (k > 0 && ws.size() < 4) {
            while (k > 0 && !is_gram_char(tl[k - 1])) --k;
            const size_t e = k;
            while (k > 0 && is_gram_char(tl[k - 1])) --k;
            if (e > k) ws.emplace_back(k, e);
        }
        if (ws.size() == 4 && ws[0].second > n0) {
            std::string g;
            for (int w = 3; w >= 0; --w) {
                if (!g.empty()) g += ' ';
                g += tl.substr(ws[w].first, ws[w].second - ws[w].first);
            }
            if (std::binary_search(grams_.begin(), grams_.end(), g)) return "said it again";
        }
    }
    return "";
}

// ---------------------------------------------------------------------------------------------------- the sampler

namespace {

struct GuideState {
    const llama_vocab * vocab;
    const GuideRules * rules;
    GuideCounts * counts;
    std::string text;
};

std::string piece_of(const llama_vocab * vocab, llama_token tok) {
    char buf[128];
    int32_t n = llama_token_to_piece(vocab, tok, buf, sizeof buf, 0, false);
    if (n >= 0) return std::string(buf, (size_t) n);
    std::string s((size_t) -n, '\0');
    n = llama_token_to_piece(vocab, tok, &s[0], (int32_t) s.size(), 0, false);
    s.resize(n > 0 ? (size_t) n : 0);
    return s;
}

const char * g_name(const llama_sampler *) { return "timerpg-guide"; }

void g_accept(llama_sampler * s, llama_token tok) {
    auto * g = static_cast<GuideState *>(s->ctx);
    if (llama_vocab_is_eog(g->vocab, tok) || llama_vocab_is_control(g->vocab, tok)) return;
    g->text += piece_of(g->vocab, tok);
}

void g_apply(llama_sampler * s, llama_token_data_array * cur) {
    auto * g = static_cast<GuideState *>(s->ctx);
    std::vector<char> keep(cur->size, 1);
    size_t kept = 0;
    const char * why = nullptr;
    for (size_t i = 0; i < cur->size; ++i) {
        const llama_token tok = cur->data[i].id;
        if (llama_vocab_is_eog(g->vocab, tok)) { ++kept; continue; }
        const char * w = llama_vocab_is_control(g->vocab, tok) ? "a control token" : g->rules->check(g->text, piece_of(g->vocab, tok));
        if (*w) { keep[i] = 0; why = w; } else { ++kept; }
    }
    if (kept == cur->size) return;
    if (kept == 0) { ++g->counts->fallbacks; return; }  // (nothing left: this step unguided, validate() will judge)
    g->counts->banned += (int32_t) (cur->size - kept);
    if (why) g->counts->last_why = why;
    size_t o = 0;
    for (size_t i = 0; i < cur->size; ++i) if (keep[i]) cur->data[o++] = cur->data[i];  // (order kept: still sorted)
    cur->size = o;
    cur->selected = -1;
}

void g_reset(llama_sampler * s) { static_cast<GuideState *>(s->ctx)->text.clear(); }

llama_sampler * g_clone(const llama_sampler * s);

void g_free(llama_sampler * s) { delete static_cast<GuideState *>(s->ctx); }

llama_sampler_i * g_iface() {
    static llama_sampler_i i = [] {
        llama_sampler_i x;
        std::memset(&x, 0, sizeof x);
        x.name = g_name;
        x.accept = g_accept;
        x.apply = g_apply;
        x.reset = g_reset;
        x.clone = g_clone;
        x.free = g_free;
        return x;
    }();
    return &i;
}

llama_sampler * g_clone(const llama_sampler * s) {
    return llama_sampler_init(g_iface(), new GuideState(*static_cast<const GuideState *>(s->ctx)));
}

}  // namespace

llama_sampler * guide_sampler(const llama_vocab * vocab, const GuideRules * rules, GuideCounts * counts) {
    return llama_sampler_init(g_iface(), new GuideState{vocab, rules, counts, {}});
}

}  // namespace llm
