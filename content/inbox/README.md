# Content from ChatGPT

Batches written by the scheduled ChatGPT task (prompt: docs/CHATGPT_CONTENT_PROMPT.md). Each run gives a file
`timerpg-content-YYYY-MM-DD.json` and the same JSON in a code block: drop the file here, attach it in the chat with
Claude, or paste the code block's text to Claude. Claude validates each batch, rejects what does not fit the simulation, and
turns the rest into game data (story templates, secrets, rumors, legends, dialogue lines, era flavor); mechanic
proposals are reviewed with the owner before anything is built.

## Time-travel scenarios

`docs/CHATGPT_TIMETRAVEL_PROMPT.md` is the prompt for a second scheduled ChatGPT task: scenarios the player can change in
the past (who, when, which verb), the chain of ripples over the decades, and how the present looks afterwards. Batches
arrive in `content/inbox/timetravel/<YYYY-MM-DD-HHMM>/timerpg-timetravel-<date>.json`, committed by ChatGPT to the
`content-inbox` branch (so it never pushes onto the development branch) and merged from there.
