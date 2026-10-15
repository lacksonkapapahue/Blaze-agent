# Bundled BusyBox (app/src/main/jniLibs/*/libbusybox.so)

| ABI | Version | Build | Notes |
|---|---|---|---|
| arm64-v8a | 1.36.1 (2025-11-23) | static, musl | patched, see below |
| armeabi-v7a | 1.31.0 (2019-06-10) | static, musl (busybox.net style) | patched, see below |

## Why the phone tab said "[Process completed (signal 31)]" (fixed in v0.30.1)

Both builds have `CONFIG_FEATURE_SUID=y`. At the start of every applet (`ash`, `ls`, `top`…), BusyBox's
`check_suid()` drops privileges for applets marked `BB_SUID_DROP`: `setgid(getgid()); setuid(getuid())`.
For a normal app these calls change nothing, but Android's seccomp filter for app processes blocks the whole
setuid/setgid family (bionic `libc/SECCOMP_BLOCKLIST_APP.TXT`) and kills the caller with **SIGSYS, signal 31**.
So `ash -l` died before printing anything, and Termux's terminal printed `[Process completed (signal 31) - press Enter]`.
(The agent's `run_shell` uses Android's sh, so it only lost the BusyBox tools; see the next section.)

Termux's own BusyBox build turns `CONFIG_FEATURE_SUID` off for the same reason.

**Fix:** `patch_setid.py` makes the one branch in `check_suid()` that guards the drop unconditional, so the two
calls are jumped over. One 4-byte instruction per binary, nothing else changes. `su`/`login` still refuse ("must be
suid"), as they would anyway without root.

Reproduce / verify on an arm64 Linux box (the CI sandbox is aarch64):

```sh
ln -s $PWD/app/src/main/jniLibs/arm64-v8a/libbusybox.so /tmp/ash
python3 tools/busybox/android_seccomp.py /tmp/ash ash -c 'echo ok'   # before: "Bad system call" (exit 159), after: ok
```

`android_seccomp.py` runs a program under a seccomp filter that traps the same syscalls Android blocks for apps.

Also fixed in v0.30.1: `Terminal.setup()` ran `libbusybox.so --list`, and BusyBox called as `libbusybox.so`
answers "applet not found", so no applet was ever linked into `usr/bin`. It now runs BusyBox through a `busybox`
symlink, probes it (`busybox sh -c 'echo ok'`) and, if the probe fails on some phone, uses Android's own
`/system/bin/sh` + toybox instead (`TermLogic.phoneLaunch`, `PhoneShellTest`).

If you replace a binary: build with `CONFIG_FEATURE_SUID` off (or re-find `check_suid` and update `patch_setid.py`
and `PhoneShellTest`).
