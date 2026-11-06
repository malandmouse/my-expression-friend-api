# Third-party notices

## SillyTavern

Source: https://github.com/SillyTavern/SillyTavern/tree/06bde939fb1e9c4c8d8641d810f0a916b5bce127

Version 1.19.0, commit `06bde939fb1e9c4c8d8641d810f0a916b5bce127`. Copyright belongs to SillyTavern contributors. Licensed under GNU Affero General Public License v3.0; full text in `LICENSE`. The new service is distributed as an AGPL-3.0 derivative, without warranty. Modifications by Codex for this local experiment on 2026-11-05.

Unmodified file: `src/validator/TavernCardValidator.js` → `vendor/sillytavern/TavernCardValidator.js`.

Derived code: source `src/endpoints/backends/chat-completions.js` → `vendor/sillytavern/chat-completion-request.mjs`, original ranges 2641–2670 (JSON Schema mapping/request body), 2676–2685 (HTTP config), 2689 (fetch call). Wrapped as pure functions; custom YAML branch, logging and unrelated provider/UI/secret dependencies omitted. Endpoint fixed to OpenAI; fetch explicitly injected. Original file snapshot is included for provenance. Exact hashes are in `vendor/sillytavern/UPSTREAM.json`.

## User-supplied cards and independent research notes

`cards/준.json` and `cards/나래.json` are unchanged user-provided v0.3.0 cards. They are application data, not a claim of ownership over their underlying character material. Verify rights before external distribution. Research notes in `rag/` are independent summaries/product proposals with references; research articles, proprietary PEERS manuals and assessment items are not included. No assertion is made that this prototype is a clinically validated intervention.
