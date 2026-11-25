// validate()-aware sampling (docs/ENGINE_TAILORING.md, idea 2): the rules of the app's reply check
// (app/src/main/kotlin/llm/Dialogue.kt, a port of validate() in src/llm/factsheet.ts) that can be decided token by token,
// made impossible to break while the model writes instead of found afterwards (a retry, or the template line).
// validate() stays the judge: this only takes away tokens that would certainly fail it (or nearly so, see each rule).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "llama.h"

namespace llm {

/**
 * The rules for one reply, from its prompt (the fact sheet and the player's words, as validate()'s `known`), the
 * speaker's full name and their last replies. Text is UTF-8; the rules, like validate()'s, look at ASCII only.
 * Lists that must match Dialogue.kt: COMMON, PLACES, the STAGE / AI / sheet phrases (guide.cpp).
 */
class GuideRules {
public:
    void build(const std::string & prompt, const std::string & self, const std::vector<std::string> & recent);
    /** Why [text] + [piece] breaks a rule that [text] alone did not ("" when it does not). [text]: the reply so far. */
    const char * check(const std::string & text, const std::string & piece) const;

    // (for the host test)
    const std::vector<std::string> & names() const { return names_; }
    const std::vector<std::string> & banned_places() const { return places_; }

private:
    std::vector<std::string> phrases_;  // lowercase, whole words (stage directions, AI talk, the sheet read back)
    std::string self_;                  // lowercase full name (anywhere, as validate's contains())
    std::vector<std::string> names_;    // capitalized words a reply may use mid-sentence, sorted
    std::vector<std::string> places_;   // place and event nouns the facts never mention (lowercase)
    std::vector<std::string> grams_;    // the last replies' 4-word sequences, sorted
};

/** What the guide did in one reply. */
struct GuideCounts {
    int32_t banned = 0;     // candidate tokens taken away
    int32_t fallbacks = 0;  // steps where every candidate broke a rule (left unguided)
    std::string last_why;   // the last rule that took a token away
};

/**
 * A llama_sampler that removes the candidates [rules] rejects, given the reply so far (it follows the tokens the chain
 * accepts). Put it after top-k (it checks each remaining candidate's text: ~40 a step). End-of-generation tokens are
 * always kept; other control tokens (<think>, <|im_start|>) are always removed. Never leaves nothing to sample: if every
 * candidate breaks a rule, the step is left as it was (counted). [rules] and [counts] must outlive it.
 */
llama_sampler * guide_sampler(const llama_vocab * vocab, const GuideRules * rules, GuideCounts * counts);

}  // namespace llm
