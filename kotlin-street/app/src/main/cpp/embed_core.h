// Text embeddings for the people's memories: Qwen3-Embedding (last-token pooling, normalized), so a memory that bears on
// what the player says now can be found among everything a person remembers.
#pragma once

#include <string>
#include <vector>

struct llama_model;
struct llama_context;

namespace llm {

class Embedder {
public:
    Embedder() = default;
    ~Embedder();
    Embedder(const Embedder &) = delete;
    Embedder & operator=(const Embedder &) = delete;

    bool load(const std::string & model, int threads);
    bool loaded() const { return ctx_ != nullptr; }
    void unload();
    int dim() const { return dim_; }

    /** The unit-length embedding of [text] (empty on failure, see error()). A query should carry its instruction
     *  ("Instruct: ...\nQuery: ..."); a stored memory is plain text. */
    std::vector<float> embed(const std::string & text);
    const std::string & error() const { return error_; }

private:
    llama_model * model_ = nullptr;
    llama_context * ctx_ = nullptr;
    int dim_ = 0;
    std::string error_;
};

/**
 * Whether a memory bears on what is said: Qwen3-Reranker reads both and answers yes or no; the score is the chance of yes.
 * Sharper than likeness of vectors for telling a real reference from small talk.
 */
class Reranker {
public:
    Reranker() = default;
    ~Reranker();
    Reranker(const Reranker &) = delete;
    Reranker & operator=(const Reranker &) = delete;

    bool load(const std::string & model, int threads);
    bool loaded() const { return ctx_ != nullptr; }
    void unload();
    /** The chance (0..1) that [doc] bears on [query] under [instruction]; < 0 on failure. */
    float score(const std::string & instruction, const std::string & query, const std::string & doc);
    const std::string & error() const { return error_; }

private:
    llama_model * model_ = nullptr;
    llama_context * ctx_ = nullptr;
    bool rank_ = false;            // converted with the yes/no head (RANK pooling); else the LM head's yes and no
    int yes_ = -1, no_ = -1;
    std::string error_;
};

}  // namespace llm
