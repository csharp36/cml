#!/usr/bin/env python3
"""Grade an ANSWER.json against an instance's ground truth.
Usage: grade.py <answer.json> <instance.json> [--no-judge]  ->  prints a score dict (JSON)."""
import json, subprocess, sys

def prf(pred, gold):
    pred, gold = set(pred), set(gold)
    if not pred and not gold:
        return (1.0, 1.0, 1.0)
    tp = len(pred & gold)
    p = tp / len(pred) if pred else 0.0
    r = tp / len(gold) if gold else 0.0
    f1 = 2 * p * r / (p + r) if (p + r) else 0.0
    return (p, r, f1)

def norm_path(p):
    return p.strip().lstrip("./")

def norm_sym(s):
    cls, sep, meth = s.strip().partition("#")
    cls = cls.split(".")[-1]
    return f"{cls}#{meth}" if sep else cls

def grade_files(answer_files, truth_files):
    return prf({norm_path(x) for x in answer_files}, {norm_path(x) for x in truth_files})

def grade_symbols(answer_syms, truth_syms):
    return prf({norm_sym(x) for x in answer_syms}, {norm_sym(x) for x in truth_syms})
