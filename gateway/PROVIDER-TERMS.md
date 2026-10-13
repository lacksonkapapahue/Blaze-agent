# Free provider terms behind a public app

What each free tier says about serving a public app, as far as could be checked on 2026-10-13.
Not legal advice; terms change, so re-read them before launch. "Unverified" means it wasn't
confirmed from the provider's own page this time.

| Provider | Public app on the free tier? | Data | Limits | Source |
| --- | --- | --- | --- | --- |
| **Google Gemini** (AI Studio key, unpaid quota) | Allowed with conditions: you and your users must be **18+** and the app must not be directed at or likely used by under-18s; **free tier can't serve users in the EEA, Switzerland or the UK** (paid only there); API is "for developers building … for professional or business purposes". | Unpaid: Google uses prompts and responses to improve products; **human reviewers may read them**; "do not submit sensitive, confidential, or personal information". | Per-model RPM/RPD on the free tier. | https://ai.google.dev/gemini-api/terms (effective 2026-03-23) |
| **Groq** (free plan) | The Services Agreement covers integrating the API into customer applications and serving end users; no free-tier production ban found. | Inference inputs/outputs not retained by default; may be logged up to 30 days for reliability/abuse unless you enable **Zero Data Retention** (self-serve in Data Controls). | Per-model rate limits; "preview" models are for evaluation only. | https://console.groq.com/docs/your-data, https://console.groq.com/docs/legal |
| **Cerebras** (free tier) | **No**: pricing page marks the free tier "for development, evaluation, and experimentation; not intended for production use". Keep it as a minor fallback or move to Developer (paid) before launch. | Usage data owned by Cerebras; content terms at cloud.cerebras.ai/terms. | Per-model limits. | https://www.cerebras.ai/pricing |
| **OpenRouter** (`:free` models) | Allowed, but caps are per account: **20 requests/min and 50/day** (1,000/day after a one-time $10 credit purchase); failed attempts count. OpenRouter itself calls free models "usually not suitable for production use". | Some free endpoints log prompts and train on them; OpenRouter has privacy toggles to exclude those providers (set them in account settings). | 20 RPM, 50 or 1,000 RPD. | https://openrouter.ai/docs/api_reference/limits, https://openrouter.ai/docs/faq |
| **Mistral** (free / Experiment plan) | Free mode is a monthly allowance at the lowest rate limits; no explicit public-app ban found, but it's positioned for trying the API. | "API requests made under the Experiment plan may be used to train Mistral's models" unless you opt out. | Low RPS/monthly tokens. | https://help.mistral.ai/en/articles/455206 (Experiment plan), https://help.mistral.ai/en/articles/347617 |
| **GitHub Models** | **Retired**: fully shut down on 2026-07-30 (no inference API). Seeded disabled; don't enable. | — | — | https://docs.github.com/en/github-models |
| **NVIDIA NIM** (build.nvidia.com) | Unverified this time; free API access is generally aimed at prototyping. Seeded disabled. | Unverified. | Unverified. | — |
| **Cloudflare Workers AI** | Yes: it's part of Workers Free/Paid; your Worker serving users is the intended use. | See Cloudflare's Workers AI data-usage page (not re-checked for 0.1.0). | **10,000 Neurons/day** free, resets 00:00 UTC; more needs Workers Paid. A few big models (Kimi K2.6/K2.7, GLM 5.x, DeepSeek V4) need a paid plan. | https://developers.cloudflare.com/workers-ai/platform/pricing/ |

## What this means for Blaze

- Users must be told their messages go to third-party AI providers, some of which (Gemini free,
  Mistral free, some OpenRouter free models) may use them for training or human review. Put that
  in Blaze's privacy policy and sign-up.
- Gemini's free tier forbids serving EEA/UK/Swiss users and anyone under 18. Before Blaze has
  users there, either use paid Gemini or route those users elsewhere (the gateway doesn't
  geo-route yet).
- Cerebras free is development-only by its own terms; GitHub Models is gone. For a real launch,
  the dependable free core is Groq + Gemini (with the caveats above) + Workers AI fallback, and
  the first paid upgrade worth making is whichever provider carries most traffic in `/admin ›
  Health`.
