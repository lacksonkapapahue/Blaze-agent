// Provider keys at rest: AES-256-GCM. The AES key is derived with HKDF-SHA256 from the
// KEY_ENCRYPTION_SECRET Worker secret, so D1 only ever holds ciphertext. Format:
//   "v1." + base64(nonce[12] || ciphertext || tag[16]), AAD "blaze-gateway-key-v1:<provider>"
// Binding the provider id into the AAD means a ciphertext can't be moved to another provider.

import { b64, fromB64 } from "./util";

const enc = new TextEncoder();
const dec = new TextDecoder();
const keyCache = new Map<string, Promise<CryptoKey>>();

export const MIN_SECRET_LEN = 32;

function aesKey(secret: string | undefined): Promise<CryptoKey> {
  if (!secret || secret.length < MIN_SECRET_LEN) {
    throw new Error(`KEY_ENCRYPTION_SECRET is missing or shorter than ${MIN_SECRET_LEN} characters`);
  }
  let k = keyCache.get(secret);
  if (!k) {
    k = (async () => {
      const raw = await crypto.subtle.importKey("raw", enc.encode(secret), "HKDF", false, ["deriveKey"]);
      return crypto.subtle.deriveKey(
        { name: "HKDF", hash: "SHA-256", salt: enc.encode("blaze-gateway"), info: enc.encode("provider-keys-v1") },
        raw,
        { name: "AES-GCM", length: 256 },
        false,
        ["encrypt", "decrypt"],
      );
    })();
    keyCache.set(secret, k);
  }
  return k;
}

const aad = (provider: string) => enc.encode(`blaze-gateway-key-v1:${provider}`);

export async function encryptKey(secret: string | undefined, provider: string, plain: string): Promise<string> {
  const key = await aesKey(secret);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: aad(provider) }, key, enc.encode(plain)));
  const out = new Uint8Array(12 + ct.length);
  out.set(iv, 0);
  out.set(ct, 12);
  return "v1." + b64(out);
}

export async function decryptKey(secret: string | undefined, provider: string, blob: string): Promise<string> {
  if (!blob.startsWith("v1.")) throw new Error("unknown key format");
  const key = await aesKey(secret);
  const raw = fromB64(blob.slice(3));
  if (raw.length < 12 + 16) throw new Error("ciphertext too short");
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: raw.slice(0, 12), additionalData: aad(provider) }, key, raw.slice(12));
  return dec.decode(pt);
}

/** The only form a key is ever shown in: its last 4 characters. */
export function last4(k: string): string {
  return k.trim().slice(-4);
}

export function masked(l4: string): string {
  return "••••" + l4;
}
