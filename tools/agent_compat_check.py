#!/usr/bin/env python3
"""Hoonsor Agent API 破壞性變更檢查（只用標準函式庫，整份複製到各 APP 的 tools/）。

來源（唯一真實來源）：hoonsor/APK-VoiceHub  tools/agent_compat_check.py
規則見 hoonsor/APK-VoiceHub  docs/AGENT_PROTOCOL.md「相容規則」。

用法：
  python tools/agent_compat_check.py <舊 manifest> <新 manifest>
  python tools/agent_compat_check.py --git-baseline docs/agent-manifest.json
      與最近一個 v* tag 的同一個檔案比對；沒有 tag 或舊檔不存在時直接通過

結束碼：0＝相容；1＝有破壞性變更；2＝用法或檔案錯誤。
commit 訊息含 BREAKING-AGENT-API 時只警告不擋（協定升版時由語音總控專案協調後才用）。
"""
from __future__ import annotations

import json
import os
import subprocess
import sys


def _props(action: dict) -> tuple[dict, set]:
    params = action.get("params") or {}
    return params.get("properties") or {}, set(params.get("required") or [])


def compare(old: dict, new: dict) -> tuple[list[str], list[str]]:
    """回傳（破壞性變更、提醒）"""
    breaking: list[str] = []
    notes: list[str] = []

    if old.get("api") != new.get("api"):
        breaking.append(f"協定版本 api 由 {old.get('api')} 改為 {new.get('api')}")
    if old.get("package") and old.get("package") != new.get("package"):
        breaking.append(f"package 由 {old.get('package')} 改為 {new.get('package')}")

    old_actions = {a["name"]: a for a in old.get("actions", []) if "name" in a}
    new_actions = {a["name"]: a for a in new.get("actions", []) if "name" in a}

    for name, oa in old_actions.items():
        na = new_actions.get(name)
        if na is None:
            breaking.append(f"動作 {name} 被刪除（請改成 deprecated: true 保留至少一版）")
            continue
        o_props, o_req = _props(oa)
        n_props, n_req = _props(na)
        for p in sorted(n_req - o_req):
            breaking.append(f"{name}：新增必填參數 {p}（新參數必須選填）")
        for p, op in o_props.items():
            np_ = n_props.get(p)
            if np_ is None:
                breaking.append(f"{name}：參數 {p} 被刪除")
                continue
            if op.get("type") != np_.get("type"):
                breaking.append(f"{name}：參數 {p} 型別由 {op.get('type')} 改為 {np_.get('type')}")
            o_enum, n_enum = op.get("enum"), np_.get("enum")
            if o_enum is not None:
                if n_enum is not None:
                    removed = [v for v in o_enum if v not in n_enum]
                    if removed:
                        breaking.append(f"{name}：參數 {p} 的選項 {removed} 被移除")
            elif n_enum is not None:
                breaking.append(f"{name}：參數 {p} 新增了 enum 限制")
            for bound, worse in (("minimum", lambda o, n: n > o), ("maximum", lambda o, n: n < o)):
                if bound in np_ and (bound not in op or worse(op[bound], np_[bound])):
                    breaking.append(f"{name}：參數 {p} 的 {bound} 範圍變窄")
        risk_rank = {"read": 0, "reversible": 1, "destructive": 2}
        o_risk, n_risk = oa.get("risk"), na.get("risk")
        if risk_rank.get(n_risk, 2) < risk_rank.get(o_risk, 2):
            notes.append(f"{name}：risk 由 {o_risk} 降為 {n_risk}，確認真的不需要先確認")
        if na.get("deprecated") and not oa.get("deprecated"):
            notes.append(f"{name}：標為 deprecated，下一版才可以刪除")

    for name in sorted(set(new_actions) - set(old_actions)):
        notes.append(f"新增動作 {name}")
    return breaking, notes


def _git(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8", check=True).stdout


def _baseline(path: str) -> dict | None:
    try:
        tag = _git("describe", "--tags", "--abbrev=0", "--match", "v*").strip()
    except subprocess.CalledProcessError:
        print("沒有 v* tag，略過相容檢查（第一個正式版之後才開始檢查）")
        return None
    try:
        text = _git("show", f"{tag}:{path}")
    except subprocess.CalledProcessError:
        print(f"{tag} 沒有 {path}，略過相容檢查")
        return None
    print(f"比對基準：{tag}:{path}")
    return json.loads(text)


def _commit_message() -> str:
    try:
        return _git("log", "-1", "--pretty=%B")
    except Exception:
        return ""


def main(argv: list[str]) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if len(argv) == 3 and argv[1] == "--git-baseline":
        path = argv[2]
        old = _baseline(path)
        if old is None:
            return 0
    elif len(argv) == 3:
        path = argv[2]
        with open(argv[1], encoding="utf-8") as f:
            old = json.load(f)
    else:
        print(__doc__)
        return 2
    with open(path, encoding="utf-8") as f:
        new = json.load(f)

    breaking, notes = compare(old, new)
    for n in notes:
        print(f"提醒：{n}")
    if not breaking:
        print("Agent API 相容 ✔")
        return 0
    allowed = "BREAKING-AGENT-API" in (_commit_message() + os.environ.get("AGENT_API_ALLOW_BREAKING", ""))
    for b in breaking:
        print(f"{'::warning::' if allowed else '::error::'}破壞性變更：{b}")
    if allowed:
        print("commit 訊息含 BREAKING-AGENT-API，只警告不擋")
        return 0
    print("有破壞性變更，語音總控與其他呼叫端可能出錯。依 docs/AGENT_PROTOCOL.md 改成相容做法。")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
