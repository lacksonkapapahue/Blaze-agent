---
name: blaze-specialists
description: How Blaze uses its specialists and background agents. Core skill, always on: pick the matching specialist, split big work into parallel briefs, keep small work, steer instead of duplicating, merge into one answer.
tags: [specialist, specialists, delegate, parallel, background agent, compare, split, team, shopper, booker, researcher, coder, tutor, designer]
version: 1
core: true
---

# Blaze specialists

Blaze has specialists (Settings › Agents): Researcher, Shopper, Booker, Coder, Tutor, Designer, Writer, Planner, Inbox manager and Phone operator, plus any the user made. Each has its own prompt, only the tools it needs, and its own skill packs. `delegate` runs several at once and returns all their results; `start_agent` runs long work in the background while the chat continues.

## Pinned
- Before planning, pick the specialist(s) that fit the task (shopping, booking, coding, research, study, design, writing…) and follow their skill packs. They're matched and attached for you each turn ("Specialist: X" on the work card).
- Small or step-by-step work (one lookup, one message, a chain where each step needs the last) stays with you. Don't delegate what you can finish in a few calls.
- Big work that splits into independent pieces goes out in parallel: one delegate call, one task per target (compare 3 shops = 3 tasks, 4 hotels = 4 tasks).
- Every brief is tight and self-contained (they can't see the chat): the goal, the exact thing to return, the limits (budget, dates, country, accounts), and "stop and report after two failed tries".
- Watch running agents (list_agents / check_agent) and steer them with message_agent when they drift; never start a duplicate of a running one.
- Merge their results into one answer: a single pick with the reason, then the short comparison. Check the numbers before you hand them over.

## Writing a brief
```
Goal: price of a Samsung Galaxy A15 128 GB at Game Zambia, delivered to Ndola.
Return: price in ZMW, in stock yes/no, delivery fee and days, product link.
Limits: new only; ignore marketplace resellers; don't buy or sign in.
Stop: if the site fails twice, report what you saw and stop.
```

## When to split
| Request | Plan |
|---|---|
| "Compare the A15 at Game, Shoprite and Jumia" | delegate: 3 Shopper tasks, one per store, then merge |
| "Find me a bus to Lusaka Friday and a cheap lodge" | delegate: 1 Booker (bus) + 1 Booker (lodge) |
| "Research solar options and write a one-page summary" | Researcher first, then write it yourself from the result |
| "Text Gloria I'm late" | do it yourself |
| "Fix this bug in my repo" | do it yourself with blaze-coding-workflow (step by step) |

## Merging
1. Line the results up on the same fields.
2. Drop any that broke the limits.
3. Lead with the pick and the reason; keep the rest to a short list or a card.
4. Say plainly which pieces failed and what you'd try next.
