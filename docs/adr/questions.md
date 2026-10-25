# The agent can ask the user questions

Status: accepted (2026-10, 0.12.0)

## Context

An agent that meets a real choice (two ways to fix something, which of several
files is meant) either guesses or ends its turn with a question in prose, and the
user's reply then starts a new turn. A multiple-choice question inside the turn
is quicker to answer on a phone and keeps the turn going.

opencode has this built in: the `question` tool (checked with opencode 1.19.0).
The model passes one or more questions, each with a short `header`, the
`question`, `options` (`label`, `description`), and `multiple` (several may be
picked) and `custom` (the user may type an answer; on unless the model turns it
off). opencode sends `question.asked` (`id`, `sessionID`, `questions`, `tool`)
and waits. `POST /question/{id}/reply {answers}` (one list of labels or typed
text per question) continues the turn with the answers as the tool's result;
`POST /question/{id}/reject` fails the tool with "The user dismissed this
question", which ends the turn. opencode offers the tool to `serve` clients (or
with `OPENCODE_ENABLE_QUESTION_TOOL`), allows it for its `build` and `plan`
agents and denies it to its subagents. Aborting a turn fails the tool call but
leaves the question pending: no event is sent and `GET /question` still lists it.

Until 0.11 the app did not offer the tool, having no way to answer it.

## Decision

- The question tool is allowed in every approval mode (it asks for an answer,
  not for permission), and `OPENCODE_ENABLE_QUESTION_TOOL=1` is set so a change
  of opencode's default does not take it away. The app's plugin lets it through:
  it touches neither the phone nor the sandbox.
- Subagents do not get it: the app's allow would otherwise reach them, so every
  subagent (opencode's and the plugins') gets `permission.question: deny`, as
  opencode intends.
- The host maps `question.asked` to `question.request` and answers through
  `question.reply`, like approvals ([bridge-protocol](../bridge-protocol.md)).
  When a session goes idle with a question still pending (the turn was
  stopped), the host withdraws it from the UI and rejects it in opencode.
- The UI shows one question at a time in a sheet that cannot be swiped away: its
  options (with their descriptions, ticked for multiple choice), a field for the
  user's own answer when allowed, Back and Next, Send after the last one, and
  "Stop without answering" (the reject). In the chat, the tool's part shows each
  question with its answer.

## Consequences

- A turn can wait on the user in full-auto mode too; it waits as long as the
  question is open, as with an approval.
- A question that arrives while the WebView reloads is not shown again, as for
  approvals; stopping the turn clears it.
- Verified with opencode 1.19.0 and the app's plugin, DeepSeek V4.1 Flash on
  Infron: one question, a typed answer, two questions with multiple choice,
  dismissing, and stopping the turn while a question was open.
